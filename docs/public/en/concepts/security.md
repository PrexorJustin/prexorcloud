---
title: Security
description: The three auth paths — JWT for operators, mTLS for daemons, plugin tokens for in-server code — plus RBAC, cosign signing, and the threat model.
---

PrexorCloud has three authentication paths. Each one solves a different problem with different credentials. Mixing them up is the most common cause of an unexplained 401. This page is the reference for all three, plus the RBAC model, the cosign signing posture, and what the security model defends against.

## What you'll learn

- The three auth paths: operators (JWT), daemons (mTLS), in-server code (plugin tokens).
- How role-based access control works, the full permission list, and how to add a custom role.
- The cosign signing model — keyless for first-party releases, key-based for third-party modules — and offline Rekor SET enforcement.
- What the security model defends against and where it stops.

## The three auth paths

| Caller | Credential | Lifetime | Purpose |
|---|---|---|---|
| Operator (dashboard, prexorctl, scripts) | JWT (Bearer) | `security.jwtExpirationMinutes`, default 1440 (24 h) | REST calls under `/api/v1/*` |
| Daemon | mTLS client certificate | issued by the Controller CA; live-reloadable | gRPC stream to the Controller |
| In-server code (Plugin) | Plugin token (`ptk_…`) | per-instance, 15 min, refreshed | Plugin → Controller REST under `/api/proxy/*` and `/api/plugin/*` |

There is no OIDC, SAML, MFA, or passkey support. The Controller authenticates operators with username + password and issues a signed JWT.

## Operator auth: JWT

Operators log in with username plus password and receive an HS256-signed JWT.

### Login

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "username": "alice", "password": "…" }
```

The response carries the token and the user record. Passwords are hashed with bcrypt (`PasswordHasher`). `AuthManager.login` verifies the hash, checks lockout state, and issues the JWT.

On a failed login the per-user counter at `prexor:v1:login:fail:<username>` increments. After `security.lockout.maxAttempts` failures (default 5) inside `security.lockout.windowSeconds` (default 900), the account is locked for `security.lockout.lockoutSeconds` (default 900). The lock is reported with HTTP 423 and a `lockedUntil` epoch.

### Auth routes

The `/api/v1/auth` surface is small:

| Route | Method | Auth | Purpose |
|---|---|---|---|
| `/api/v1/auth/login` | POST | public | Exchange credentials for a JWT |
| `/api/v1/auth/logout` | POST | JWT | Revoke the current JWT by `jti` |
| `/api/v1/auth/refresh` | POST | JWT | Issue a fresh JWT with current role |
| `/api/v1/auth/me` | GET | JWT | Return the current user |
| `/api/v1/auth/change-password` | POST | JWT | Verify current password, swap the hash |

There is no standalone `/auth/revoke` route. Revocation happens through `logout`, password change, and per-`jti` server-side revocation.

### JWT shape

The token carries:

- `sub` — username
- `role` — the role name at issue time
- `iat` / `exp` — standard timestamps
- `jti` — the JWT ID, used for revocation

Permissions are not embedded in the JWT. `JwtAuthMiddleware` re-resolves the role's permission set from the role store on every request, so role changes apply immediately without a re-login.

### Refresh

`POST /api/v1/auth/refresh` with a valid JWT issues a new token with a fresh expiry and the user's *current* role. If the user was deleted, refresh is denied.

### Revocation

`prexor:v1:jwt:revoked:<jti>` is set on logout and password change. Its TTL matches the remaining JWT lifetime, so revocations expire on their own. `JwtAuthMiddleware` checks this key on every request.

In the `production` profile, revocation is shared across HA controllers through Valkey (`RedisJwtRevocationStore`). In `development` it is in-memory and lost on restart.

### Key rotation

`JwtManager` accepts a dual-key window. The active key (`security.jwtSecret`) signs new tokens; previous keys listed in `security.jwtPreviousSecrets` still validate existing tokens until they fall out of the window (`maxAcceptableKeys`, default 2). Rotate the secret, keep the old one in `jwtPreviousSecrets` for a tail, then drop it.

```yaml
security:
  jwtSecret: "<new base64 256-bit secret>"
  jwtPreviousSecrets:
    - "<old base64 secret>"
  jwtExpirationMinutes: 1440
```

`security.jwtExpirationMinutes` must be between 1 and 43200 (30 days); the Controller refuses to start otherwise. See [`docs/runbooks/rotate-secrets.md`](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/rotate-secrets.md).

### Password reset (optional)

Off by default. Enable it under `security.passwordReset`:

```yaml
security:
  passwordReset:
    enabled: true
    tokenTtlMinutes: 30          # default 30
    resetUrlBase: "https://dash.example.com"
    smtp:
      host: smtp.example.com     # blank host => LogMailer (writes link to controller log)
```

When `enabled` is false the `/api/v1/auth/password-reset/*` routes return 404. The flow:

1. `POST /api/v1/auth/password-reset/request { email }` — always returns 202, whether or not the email is known. No enumeration leak.
2. If the email matches a user, `PasswordResetManager` mints a URL-safe token, stores it at `prexor:v1:pwreset:<token>` with a `tokenTtlMinutes` TTL, and emails the link (`resetUrlBase` + `/auth/reset-password?token=…`).
3. The user posts the new password to `/api/v1/auth/password-reset/complete { token, newPassword }`.
4. The token is consumed single-use and the bcrypt hash swapped.

Mailers: `LogMailer` (default — writes the link to the Controller log when `smtp.host` is blank) or `SmtpMailer` with STARTTLS / implicit TLS / optional AUTH.

## RBAC

Permissions are flat strings defined as constants in `Permission`. Roles map a name to a set of those strings. There is no hierarchy: `groups.update` does not imply `groups.view`. A role grants exactly what it enumerates.

### Built-in roles

Four roles ship built in. The first three are operator-facing; `DAEMON_HOST` is a machine principal.

| Role | Grants |
|---|---|
| `ADMIN` | Every permission reflected off `Permission`, **except** `cluster.manage` (withheld so a compromised admin token can't reshape the cluster) |
| `OPERATOR` | Day-to-day operations: groups (view/create/update/start), instances (view/stop/command/console), networks (view/create/update), templates (view/create/update), players, modules (view), catalog (view), metrics, events, share invoke/revoke, node view/drain |
| `VIEWER` | Read-only across nodes, groups, networks, instances (view/console), players, templates, crashes, modules, catalog, metrics, events |
| `DAEMON_HOST` | Issued to a daemon host's CLI when its join token is redeemed: read cluster state plus stop/command/console instances on its own node, no cluster-wide write |

`ADMIN` is derived reflectively from `Permission`, so a new permission constant lands in `ADMIN` automatically — except the explicitly excluded `cluster.manage` (issue/revoke join tokens, eject members, rotate the seed secret).

### Full permission list

| Domain | Permissions |
|---|---|
| Nodes | `nodes.view`, `nodes.drain`, `nodes.revoke-cert` |
| Groups | `groups.view`, `groups.create`, `groups.update`, `groups.delete`, `groups.start` |
| Instances | `instances.view`, `instances.stop`, `instances.command`, `instances.delete`, `instances.console` |
| Players | `players.view`, `players.transfer` |
| Networks | `networks.view`, `networks.create`, `networks.update`, `networks.delete` |
| Templates | `templates.view`, `templates.create`, `templates.update`, `templates.delete` |
| Crashes | `crashes.view` |
| Share | `share.invoke`, `share.revoke` |
| Tokens | `tokens.view`, `tokens.create`, `tokens.revoke` |
| Users | `users.view`, `users.create`, `users.update`, `users.delete` |
| Roles | `roles.view`, `roles.manage` |
| Modules | `modules.view`, `modules.manage` |
| Catalog | `catalog.view`, `catalog.manage` |
| Audit | `audit.view` |
| System | `system.settings`, `system.logs.view` |
| Metrics | `metrics.view` |
| Events | `events.stream`, `events.view` |
| Backups | `backups.view`, `backups.manage`, `backups.restore` |
| Cluster | `cluster.view`, `cluster.config.write`, `cluster.manage` |

### Custom roles

Define custom roles in role config (loaded by `RoleConfigLoader` / `RoleStore`). A custom role lists every permission it grants — explicitly, no wildcards. `Role.permissionsFor` checks the dynamic store first, then falls back to built-in defaults, and caches the result; the cache clears when role config changes.

```yaml
# A role for module operators only
roles:
  - name: MODULE_OPERATOR
    permissions:
      - modules.view
      - modules.manage
      - metrics.view
```

### Enforcement

Route handlers gate themselves with `JwtAuthMiddleware.requirePermission`:

```java
post("/{id}/revoke-cert", this::revokeNodeCert);
// …
private void revokeNodeCert(Context ctx) {
    JwtAuthMiddleware.requirePermission(ctx, Permission.NODES_REVOKE_CERT);
    // …
}
```

A missing permission returns HTTP 403.

## Daemon auth: mTLS

Daemons authenticate to the Controller's gRPC server with client certificates issued by the Controller's internal CA (`CertificateAuthority`). There is no shared secret on the steady-state path.

### First contact: the join token

A new Daemon has no certificate yet. Bootstrap with a join token.

Create one on the Controller:

```bash
prexorctl token create --node node-1 --ttl 1h
```

```
Join Token Created
  Token ID    …
  Join Token  pxr_…
  Node ID     node-1
  Expires At  …
```

`token create` POSTs to `/api/v1/admin/tokens` and needs `tokens.create`. The plaintext token is prefixed `pxr_` and shown once. `--node` is optional; `--ttl` defaults to `1h`. List and revoke with `prexorctl token list` and `prexorctl token revoke <id>`.

Install the Daemon and hand it the join token through the setup wizard:

```bash
sudo prexorctl setup
```

`setup` opens a loopback browser wizard (or falls back to a TTY form on headless hosts). Pick the Daemon component, point it at the Controller's gRPC endpoint, and paste the join token.

### The exchange

The Daemon redeems the token over the unauthenticated `BootstrapService.ExchangeJoinToken` RPC (`cloud-protocol/.../bootstrap_service.proto`):

1. The Daemon sends `join_token` plus its `node_id`.
2. The Controller validates the token (single-use, not expired, HMAC against the cluster seed), issues key material via `CertificateAuthority.issue(...)`, and returns:
   - `pkcs12` — the Daemon's keystore (cert chain + private key)
   - `pkcs12_password` — the keystore password
   - `ca_certificate_pem` — the Controller CA cert, so the Daemon can verify the Controller
   - `cli_token` — a `DAEMON_HOST`-scoped JWT, so the host's CLI is pre-logged-in (optional; older peers ignore it)
3. The Daemon writes the keystore to disk and switches to mTLS for all later gRPC calls.

Join tokens are single-use; replay is rejected server-side. The plaintext is redacted in logs (`JoinToken.toString`, covered by `JoinTokenRedactionTest`).

### Cert lifecycle

mTLS material live-reloads when the on-disk cert and key change (`ReloadableServerSslContext` plus a material watcher), so operators rotate without Controller downtime. The TLS context is built by `TlsContextBuilder`; there is no PSK or downgrade fallback.

### Per-node revocation

```http
POST /api/v1/nodes/{id}/revoke-cert      # requires nodes.revoke-cert
POST /api/v1/nodes/{id}/unrevoke-cert    # requires nodes.revoke-cert
```

Revocation adds the node cert's serial (and CN) to the revocation store — in-memory plus Valkey-backed (`prexor:v1:nodecert:revoked:…`). The mTLS enforcement interceptor rejects revoked serials: new handshakes fail at TLS, and an existing connection fails on its next RPC. Recovery is to revoke, then re-bootstrap with a fresh join token.

## Plugin auth: plugin tokens

The third path is for code running inside MC server / proxy JVMs.

When the Controller schedules an Instance, `WorkloadIdentityRegistry` mints a per-instance plugin token (32 random bytes, prefixed `ptk_`) and the placement coordinator includes it in the start payload. The Daemon writes it into the instance environment as `CLOUD_PLUGIN_TOKEN` (`ServerProcess` sets `fullEnv.put("CLOUD_PLUGIN_TOKEN", …)`). The Plugin reads the env var on startup and presents it as a Bearer token.

Why a separate credential:

- **Per-instance.** A compromised server exposes only one instance's REST surface.
- **Short-lived.** Default TTL is 15 minutes (`RedisKeys.defaultPluginTokenTtl`). The Plugin refreshes before expiry via `/api/proxy/auth/refresh` or `/api/plugin/auth/refresh`.
- **Revoked on stop.** When the Instance stops, its token is dropped from the registry. No lingering valid token.
- **Replay-protected.** Each token carries a sequence window (`prexor:v1:workloadseq:<instance>`); out-of-window sequences are rejected.

### Route segregation

| Route prefix | Auth | Caller |
|---|---|---|
| `/api/v1/*` | JWT (with public exemptions) | Operator, dashboard, prexorctl |
| `/api/proxy/*` | Plugin token | Proxy plugin (Velocity / BungeeCord / Geyser) |
| `/api/plugin/*` | Plugin token | Server plugin (Paper / Spigot / Folia / Fabric / NeoForge) |

Plugin tokens never reach `/api/v1/*`, and JWTs never reach `/api/proxy/*` or `/api/plugin/*`. The middleware enforces the split; there is no admin JWT that skips plugin auth. The proxy and plugin surfaces expose only what in-server code needs — player join/leave, transfers, group/instance reads, metrics, message passing, and an events ticket exchange.

## Public routes

A few routes skip auth:

- `/api/v1/auth/login`
- `/api/v1/auth/password-reset/request` and `/complete` (when password reset is enabled; 404 otherwise)
- `/api/v1/system/health` (liveness), `/api/v1/system/ready` (readiness), `/api/v1/system/version`

### SSE tickets

Streaming endpoints don't take a Bearer header on the stream itself; they take a short-lived ticket query parameter. The ticket *exchange* is JWT-authenticated:

| Stream | Ticket exchange |
|---|---|
| `/api/v1/events/stream` | `POST /api/v1/events/ticket` |
| `/api/v1/services/{id}/console` | console ticket |
| `/api/v1/system/logs/stream` | `/api/v1/system/logs/ticket` |
| `/api/v1/nodes/{id}/logs/stream` | `/api/v1/nodes/{id}/logs/ticket` |

Tickets are single-use with a 30-second TTL (`RedisKeys.sseTicketTtl`). The plugin / proxy surfaces have their own `/api/{proxy,plugin}/events/ticket`.

Prometheus metrics are exposed via the Controller's metrics endpoint; gate it with a reverse-proxy ACL if the Controller is reachable beyond a private network.

## Cosign signing

Two artefact families, two cosign flows.

### Keyless: first-party releases

Each release tag (`v*`) signs `prexorctl` binaries and container images with **cosign keyless** using the GitHub Actions OIDC identity. There is no long-lived private key. Verification proves the artefact was signed by that workflow on this repo.

```bash
cosign verify-blob \
  --certificate-identity-regexp "^https://github.com/prexorjustin/prexorcloud/.github/workflows/release.yml@refs/tags/" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  --signature checksums.txt.sig \
  --certificate checksums.txt.pem \
  checksums.txt

sha256sum -c checksums.txt
```

The release workflow runs `cosign verify` against its own freshly-signed images as the last step, so a broken signature fails CI before operators see it.

### Key-based: third-party modules

Module signing is the third-party case — authors with their own keys. The verifier (`PlatformModuleSignatureVerifier`) accepts two sidecar formats next to the module jar:

- `<jar>.cosign.bundle` — cosign sign-blob bundle JSON (`mode: COSIGN_BUNDLE`). Trust root may hold `PUBLIC KEY` blocks (raw cosign-keyed) and/or `CERTIFICATE` blocks (cosign-keyed certs validated by PKIX against internal CAs).
- `<jar>.sig` — Base64 SHA-256-with-RSA or -EC sidecar against a `PUBLIC KEY` trust bundle (`mode: KEYED`, the default and back-compat path).

Configure under `modules.signing`:

```yaml
modules:
  signing:
    required: true                 # default: true in production, false in development
    mode: COSIGN_BUNDLE            # KEYED (default) | COSIGN_BUNDLE
    trustRoot: "config/cosign-roots.pem"
    allowUnsignedDevelopment: false
    rekor:
      policy: REQUIRE_SET          # DISABLED (default) | REQUIRE_SET
      publicKey: "config/rekor.pub"
```

When `required` is true but no trust root is configured, the Controller installs the **fail-closed** verifier (`PlatformModuleSignatureVerifier.failClosed()`): every install is rejected with a clear error rather than silently accepted. The development NOOP verifier is rejected at production startup by config validation.

### Rekor SET enforcement

`rekor.policy: REQUIRE_SET` adds offline transparency-log enforcement (only meaningful with `COSIGN_BUNDLE`). The Controller loads Rekor's public key from `rekor.publicKey`, parses the bundle's `SignedEntryTimestamp`, reconstructs the canonical Rekor payload, and rejects bundles whose SET does not verify. No network access is required. Inclusion-proof Merkle verification is not implemented; the SET is the enforced control.

A signature failure on install returns HTTP 422 `SIGNATURE_VERIFICATION_FAILED`. The integration test `CosignSignedModuleInstallTest` exercises the full path.

### Daemon-side enforcement

The `cloud-security/signing` package is shared between Controller and Daemon. A Daemon that re-receives modules over the gRPC stream verifies them locally: it writes the inbound jar plus sidecar as siblings in a temp directory (the on-disk shape the verifier expects) and runs verification before commit. Configure under `daemon.modules.signing` with `required`, `mode`, and `trustRoot`.

## What the security model defends

The detail lives in the [threat model](https://github.com/prexorjustin/prexorcloud/blob/main/docs/security/threat-model.md). The short version:

| Threat | Mitigation |
|---|---|
| Brute-force operator login | Lockout: `maxAttempts` per `windowSeconds`, locked for `lockoutSeconds`; Valkey-shared across HA controllers |
| Stolen JWT | Short lifetime; revocable by `jti` on logout / password change |
| JWT signing-key compromise | Dual-key rotation window via `jwtPreviousSecrets` |
| Rogue / stolen daemon cert | mTLS with the Controller CA; per-node serial revocation enforced at the interceptor |
| Stolen plugin token | 15-minute TTL, per-instance scope, revoked on stop, sequence-window replay protection |
| Reset-email takeover | Single-use token, default 30-minute TTL, bound to the user; no enumeration leak |
| Module supply chain | Cosign verification, fail-closed in production, optional Rekor SET enforcement; unsigned installs return 422 |

What it does **not** defend against:

- A compromised Controller host. Root on the Controller is game over.
- A compromised Daemon host. That node's instances are compromised; other nodes are not (modulo proxy-side player data). There is no cgroup or container isolation between instances and the Daemon host in v1 — run the Daemon under a low-privilege user.
- Misuse of a legitimate `ADMIN` account. Audit logs reveal it after the fact; nothing prevents it.
- Multi-tenant isolation. PrexorCloud is single-tenant; do not share a Controller across hostile tenants.

## Production hardening checklist

Within five minutes of first install:

1. Change the bootstrap admin password.
2. Shred the `.initial-admin-password` file.
3. Restrict the Controller's allowed subnets to operator and daemon networks.
4. Terminate TLS at a reverse proxy if the REST surface is exposed beyond a private network, and set the trusted-proxy CIDR list so subnet checks evaluate the real client IP.
5. Set `modules.signing.required=true` with a configured `trustRoot` if you install third-party modules.

## Next up

- [Cluster model](/concepts/cluster-model/) — what Valkey holds and what the lease-and-fencing model protects.
- [Module lifecycle](/concepts/modules/lifecycle/) — what runs at signature-verification time on install.
- [Plugins](/concepts/plugins/) — plugin token issuance and route segregation in detail.
