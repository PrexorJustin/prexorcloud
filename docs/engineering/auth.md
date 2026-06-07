# Auth model

PrexorCloud has three distinct authentication paths. Each one solves a specific problem and uses different credentials. Mixing them up is the most common cause of "why is this 401?"

## At a glance

| Caller | Credential | Lifetime | Purpose |
|---|---|---|---|
| Operator (dashboard, CLI, scripts) | JWT | configurable (default 60 min, refreshable) | Normal authenticated REST calls |
| Daemon | mTLS client certificate | rotating, issued by controller CA | gRPC stream to controller |
| MC plugin (server / proxy) | Plugin token (`ptk_...`) | per-instance, refreshable | Plugin → controller REST |

There is no OIDC, no SAML, no MFA, no passkeys. See [`decisions.md`](decisions.md) §"Username + password + JWT only."

## Operator auth (JWT)

### Login

`POST /api/v1/auth/login` with `{ "username": "...", "password": "..." }`. Returns:

```json
{ "token": "eyJhb...", "user": { "username": "...", "role": "ADMIN", ... } }
```

Passwords are bcrypt-hashed on creation. `AuthManager.login` verifies the hash, checks lockout state, and issues a JWT signed with the configured secret (HS256). On verification failure the account-lockout counter increments under `prexor:v1:login:fail:<username>`; after `security.lockout.maxAttempts` failures inside `security.lockout.windowSeconds`, the account is locked for `security.lockout.lockoutSeconds`.

### JWT shape

The JWT carries:

- `sub` (subject) — username
- `role` — current role at issue time
- `iat` / `exp` — standard
- `jti` — JWT ID, used for revocation

Permissions are *not* in the JWT. The middleware re-resolves the role's permission set on every request from `roles.yml`/`MongoRoleStore` so role changes take effect immediately.

### Refresh

`POST /api/v1/auth/refresh` (with the current JWT) re-issues a JWT with a fresh expiry. The middleware looks up the user's *current* role at refresh time and bakes it into the new token; if the user has been deleted, refresh is denied.

### Revocation

`prexorcloud:v1:jwt:revoked:<jti>` is set on logout, password change, and explicit revoke (`POST /api/v1/auth/revoke`). The TTL matches the remaining JWT lifetime, so revocations clean up automatically. `JwtAuthMiddleware` checks this on every request.

In `production` profile, revocation is shared across controllers via Valkey. In `development` it is in-memory and lost on restart.

### Key rotation

`JwtManager.rotate(newSecret)` swaps the signing key. Verification uses a dual-window: the new key is preferred, the previous keys are accepted up to their configured tail. `addPreviousKey` extends the tail when an operator wants to roll a key gradually. See [`runbooks/rotate-secrets.md`](runbooks/rotate-secrets.md).

### Password reset

Optional, off by default. Configure under `security.passwordReset`:

```yaml
security:
  passwordReset:
    enabled: true
    tokenTtlMinutes: 30
    resetUrlBase: "https://dash.example.com"
    smtp:
      host: smtp.example.com
      port: 587
      startTls: true
      username: "..."
      password: "..."
      from: "no-reply@example.com"
```

Flow:

1. `POST /api/v1/auth/password-reset/request { email }` — always returns 202. We deliberately do *not* reveal whether the email is known (no enumeration leak).
2. If the email matches a user with a local password, `PasswordResetManager` mints a 256-bit URL-safe token, stores it under `prexor:v1:pwreset:<token>` with a TTL, and emails the reset link.
3. The user clicks the link, enters a new password, and the dashboard calls `POST /api/v1/auth/password-reset/complete { token, newPassword }`.
4. `PasswordResetManager` consumes the token (`GETDEL`-style, single-use), validates the new password, swaps the bcrypt hash.

Mailer:
- `LogMailer` (default when SMTP unconfigured) writes the reset link to the controller log. Suitable for development and dry-run installs.
- `SmtpMailer` (`jakarta.mail`) supports STARTTLS, implicit TLS, and optional AUTH.

The whole flow is gated by `security.passwordReset.enabled`. When `false`, both routes return 404 and no manager is wired into the controller. There is no half-state.

## Roles and permissions

Three built-in roles, defined in `defaults/roles.yml` (operator-editable):

- **ADMIN** — `["*"]`. Wildcard, every permission.
- **OPERATOR** — most operational permissions (groups, instances, modules, deployments, networks, events).
- **VIEWER** — read-only across the system.

Permissions are constants on `Permission`. There are ~30 of them; see the full set with `prexorctl role list` or in `Permission.java`. They are not hierarchical — `groups.update` does not imply `groups.view`. The viewer/operator role definitions enumerate every permission they grant.

Custom roles: edit `roles.yml` (or call the role REST surface) to add a role with a curated permission list. Wildcards inside lists are not supported — `["*"]` is the only special string and is reserved for ADMIN-equivalent.

## Daemon auth (mTLS)

Daemons authenticate to the controller's gRPC server with client certificates issued by the controller's internal CA. There is no shared secret.

### First contact: the join token

A new daemon does not have a certificate yet. To bootstrap:

1. Operator runs `prexorctl token create-join` (or via dashboard) — controller mints a one-time `JoinToken` and stores it under `JoinTokenStore` (file-backed by default).
2. Operator copies the join token into the daemon's `daemon.yml: security.joinToken`.
3. Daemon starts, calls the unauthenticated `BootstrapService.Register` RPC presenting the join token.
4. Controller verifies the token, generates a private key + cert via `CertificateAuthority.issue(...)`, returns the cert chain + private key in the RPC response.
5. Daemon writes the cert + key to `certs/`, deletes the join token from its config, switches to mTLS for all future gRPC calls.

Join tokens are single-use. Replay rejection happens in the bootstrap service.

### Cert lifecycle

- mTLS material live-reloads when the on-disk cert + key changes (`ReloadableServerSslContext` + `TlsMaterialWatcher`). Operators rotate without controller downtime.
- Per-node revocation: `POST /api/v1/nodes/{id}/revoke-cert` (gated on `nodes.manage`) adds the node's cert serial to `NodeCertificateRevocationStore`. The mTLS interceptor rejects connections with revoked serials immediately.

See [`runbooks/rotate-secrets.md`](runbooks/rotate-secrets.md).

## Plugin auth (plugin tokens)

The third path is used by code running inside MC server / proxy JVMs.

When the controller dispatches a `Start` to a daemon, it generates a per-instance plugin token (`ptk_<UUID>`) and includes it in the composition plan. The daemon writes this into the instance's environment as `CLOUD_PLUGIN_TOKEN`. The cloud-plugin reads the env var on startup and presents it as a Bearer token on `/api/proxy/*` and `/api/plugin/*` REST routes.

### Why a separate credential

- Plugin tokens are per-instance — if a server is compromised, only that one instance's REST surface is exposed.
- Plugin tokens have a short TTL (15 minutes by default). The plugin refreshes proactively before expiry.
- Plugin tokens are revoked when the instance stops. There is no lingering "old plugin token still works" risk.
- Plugin tokens carry a sequence window for replay protection (`prexor:v1:workloadseq:`).

### Plugin-token routes vs. JWT-auth routes

Permission and route segregation:

| Route prefix | Auth | Caller |
|---|---|---|
| `/api/v1/*` | JWT (or specific exemptions like `/auth/login`, `/system/health`, SSE-ticket-authenticated paths) | Operator / dashboard / CLI |
| `/api/proxy/*` | Plugin token | Proxy plugin (Velocity / Bungee) |
| `/api/plugin/*` | Plugin token | Server plugin (Paper / Spigot / Folia) |

Plugin tokens never grant access to `/api/v1/*` and JWTs never grant access to `/api/proxy/*` or `/api/plugin/*`. The middleware enforces this — no "magic admin JWT" can skip plugin auth.

## Public routes

A few routes skip auth entirely:

- `/api/v1/auth/login`
- `/api/v1/auth/password-reset/request` and `/complete` (when password reset is enabled; 404 otherwise)
- `/api/v1/system/health` (liveness)
- `/api/v1/system/ready` (readiness)
- `/api/v1/system/version`
- `/api/v1/events/stream` — but this stream requires a SSE ticket query parameter; the ticket exchange happens on a JWT-auth'd POST.
- `/metrics` — Prometheus exposition; deliberately not authenticated by default. If you need to gate this, put it behind a reverse proxy ACL.

Console SSE (`/services/{id}/console`), controller-log SSE (`/system/logs/stream`), and daemon-log SSE (`/nodes/{id}/logs/stream`) all use the same SSE-ticket pattern as the main event stream.

## Threat model summary

The detail is in [`security/threat-model.md`](security/threat-model.md). The short version of what auth defends:

- **Brute-force operator login.** Lockout (configurable threshold + window + duration), shared via Valkey across controllers in production.
- **Stolen JWT.** Short lifetime, revocable. Not bulletproof (caller must know the JWT is stolen), but the standard mitigation.
- **Stolen daemon key.** Per-node revocation; mTLS interceptor enforces immediately. Recovery: revoke + re-bootstrap with a new join token.
- **Stolen plugin token.** Short TTL, per-instance, revoked on stop. Sequence window blocks naïve replays.
- **Operator account takeover via reset email.** Reset token is single-use, 30-minute TTL, bound to the user. Compromised email is still a real risk — that is what the (optional) password change confirmation flow guards against. We do not have the latter built in; if your operator email is compromised, rotate the user's password manually via `prexorctl user reset-password`.
