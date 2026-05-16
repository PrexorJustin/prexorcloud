---
title: Security
description: The three auth paths — JWT for operators, mTLS for daemons, plugin tokens for in-server code — plus RBAC, cosign signing, and the threat model.
---

PrexorCloud has three distinct authentication paths. Each one solves a
specific problem and uses different credentials. Mixing them up is the
most common cause of "why is this 401?" — and getting them right is how
the cluster stays trustworthy. This page is the reference for all three,
plus the RBAC model, the cosign signing posture, and the threat model.

## What you'll learn

- The three auth paths: operators (JWT), daemons (mTLS), and in-server
  code (plugin tokens).
- How role-based access control works, what permissions exist, and how
  to add a custom role.
- The cosign signing model — keyless for our own releases, key-based for
  third-party modules — and how Rekor SET enforcement works.
- The threat model: what each control defends against and where it
  ends.

## The three auth paths

| Caller | Credential | Lifetime | Purpose |
|---|---|---|---|
| Operator (dashboard, CLI, scripts) | JWT | configurable, default 60 min | Normal authenticated REST calls |
| Daemon | mTLS client certificate | rotating, issued by controller CA | gRPC stream to controller |
| MC plugin (server / proxy) | Plugin token (`ptk_...`) | per-instance, refreshable | Plugin → controller REST |

There is no OIDC, no SAML, no MFA, no passkeys in v1. The reasons are in
the project's decisions
record.

## Operator auth: JWT

Operators authenticate with username plus password and receive a signed
JWT.

### Login

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "username": "alice", "password": "..." }
```

Returns:

```json
{
  "token": "eyJhb...",
  "user": { "username": "alice", "role": "OPERATOR", "..." }
}
```

Passwords are bcrypt-hashed on creation. `AuthManager.login` verifies
the hash, checks lockout state, and issues a JWT signed with the
configured HS256 secret. On verification failure the account-lockout
counter increments under `prexor:v1:login:fail:<username>`; after
`security.lockout.maxAttempts` failures inside
`security.lockout.windowSeconds`, the account is locked for
`security.lockout.lockoutSeconds`.

### JWT shape

The JWT carries:

- `sub` — username
- `role` — the role at issue time
- `iat` / `exp` — standard
- `jti` — JWT ID, used for revocation

**Permissions are not in the JWT.** Middleware re-resolves the role's
permission set on every request from the role store, so role changes
take effect immediately without a re-login.

### Refresh

`POST /api/v1/auth/refresh` with the current JWT issues a new JWT with
a fresh expiry. The middleware looks up the user's *current* role at
refresh time. If the user has been deleted, refresh is denied.

### Revocation

`prexor:v1:jwt:revoked:<jti>` is set on logout, password change, and
explicit revoke (`POST /api/v1/auth/revoke`). The TTL matches the
remaining JWT lifetime so revocations clean up automatically.
`JwtAuthMiddleware` checks this on every request.

In `production` profile, revocation is shared across controllers via
Valkey. In `development` it is in-memory and lost on restart.

### Key rotation

`JwtManager.rotate(newSecret)` swaps the signing key. Verification uses
a dual-window: the new key is preferred, the previous keys are accepted
up to their configured tail. See
[runbooks/rotate-secrets.md](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/rotate-secrets.md).

### Password reset (optional)

Off by default. When enabled (`security.passwordReset.enabled=true`),
the flow is:

1. `POST /auth/password-reset/request { email }` — always returns 202
   regardless of whether the email is known (no enumeration leak).
2. If the email matches a user, `PasswordResetManager` mints a 256-bit
   URL-safe token, stores it under `prexor:v1:pwreset:<token>` with a
   30-minute TTL, emails the link.
3. User clicks the link, posts the new password to
   `/auth/password-reset/complete { token, newPassword }`.
4. The token is consumed (single-use) and the bcrypt hash swapped.

Mailers: `LogMailer` (default, writes to controller log) or
`SmtpMailer` with STARTTLS / implicit TLS / optional AUTH.

## RBAC

Three built-in roles, defined in `defaults/roles.yml` (operator-editable):

- **ADMIN** — `["*"]`. Wildcard, every permission.
- **OPERATOR** — most operational permissions (groups, instances,
  modules, deployments, networks, events).
- **VIEWER** — read-only across the system.

There are roughly 30 permissions on `Permission`. They are *not*
hierarchical — `groups.update` does not imply `groups.view`. Role
definitions enumerate every permission they grant.

Custom roles: edit `roles.yml` (or call the role REST surface) to add a
role with a curated permission list. Wildcards inside lists are not
supported — `["*"]` is the only special string and is reserved for
ADMIN-equivalent.

```yaml
# Example: a role for module operators only
roles:
  - name: MODULE_OPERATOR
    permissions:
      - modules.view
      - modules.manage
      - system.health
```

Permissions are checked in route handlers via
`ctx.requirePermission(...)`:

```java
reg.post("/admin/recompute", ctx -> {
    ctx.requirePermission(Permissions.MODULES_MANAGE);
    leaderboard.recompute();
});
```

## Daemon auth: mTLS

Daemons authenticate to the controller's gRPC server with client
certificates issued by the controller's internal CA. There is no shared
secret.

### First contact: the join token

A new daemon does not have a certificate yet. To bootstrap:

```bash
# On the controller
prexorctl token create --description "node-1" --ttl 1h
# -> Token: prxn_...

# On the daemon host
sudo prexorctl setup --role daemon \
    --controller-grpc <controller-host>:9090 \
    --join-token prxn_...
```

The daemon setup flow:

1. Calls the unauthenticated `BootstrapService.Register` RPC presenting
   the join token.
2. The controller verifies the token, generates a private key plus cert
   via `CertificateAuthority.issue(...)`, returns the cert chain plus
   private key in the RPC response.
3. The daemon writes the cert and key to `data/certs/`, deletes the
   join token from its config, switches to mTLS for all future gRPC
   calls.

Join tokens are **single-use**. Replay rejection happens server-side, so
you cannot accidentally register the same daemon twice.

### Cert lifecycle

mTLS material live-reloads when the on-disk cert and key change
(`ReloadableServerSslContext` + `TlsMaterialWatcher`). Operators rotate
without controller downtime.

Per-node revocation: `POST /api/v1/nodes/{id}/revoke-cert` (gated on
`nodes.manage`) adds the node's cert serial to the revocation store.
The mTLS interceptor rejects connections with revoked serials
immediately. Recovery is to revoke and re-bootstrap with a new join
token.

## Plugin auth: plugin tokens

The third path is used by code running inside MC server / proxy JVMs.

When the controller dispatches a `Start` to a daemon, it generates a
per-instance plugin token (`ptk_<UUID>`) and includes it in the
[composition plan](/concepts/groups-instances-templates/). The daemon
writes this into the instance's environment as `CLOUD_PLUGIN_TOKEN`.
The cloud plugin reads the env var on startup and presents it as a
Bearer token on `/api/proxy/*` and `/api/plugin/*` REST routes.

Why a separate credential:

- Plugin tokens are **per-instance** — a compromised server exposes only
  one instance's REST surface.
- Plugin tokens have a short TTL (15 minutes by default). The plugin
  refreshes proactively before expiry.
- Plugin tokens are **revoked when the instance stops**. There is no
  lingering "old plugin token still works" risk.
- Plugin tokens carry a **sequence window** for replay protection
  (`prexor:v1:workloadseq:`).

### Route segregation

| Route prefix | Auth | Caller |
|---|---|---|
| `/api/v1/*` | JWT (with specific exemptions) | Operator, dashboard, CLI |
| `/api/proxy/*` | Plugin token | Proxy plugin (Velocity / BungeeCord) |
| `/api/plugin/*` | Plugin token | Server plugin (Paper / Spigot / Folia) |

Plugin tokens never grant access to `/api/v1/*` and JWTs never grant
access to `/api/proxy/*` or `/api/plugin/*`. The middleware enforces
this — there is no "magic admin JWT" that skips plugin auth.

## Public routes

A few routes skip auth entirely:

- `/api/v1/auth/login`
- `/api/v1/auth/password-reset/request` and `/complete` (when password
  reset is enabled; 404 otherwise)
- `/api/v1/system/health` (liveness)
- `/api/v1/system/ready` (readiness)
- `/api/v1/system/version`
- `/api/v1/events/stream` — but this stream requires an SSE ticket
  query parameter; the ticket exchange itself is JWT-authenticated.
- `/metrics` — Prometheus exposition; deliberately not authenticated by
  default. Gate with a reverse-proxy ACL if needed.

Console SSE (`/services/{id}/console`), controller-log SSE
(`/system/logs/stream`), and daemon-log SSE
(`/nodes/{id}/logs/stream`) all use the same SSE-ticket pattern.

## Cosign signing

We sign two distinct artefact families with two distinct cosign flows.

### Keyless: our own releases

Each release tag (`v*`) signs `prexorctl` binaries plus container images
with **cosign keyless** using the GitHub Actions OIDC identity. We do
not maintain a long-lived private key. Verification proves "this
artefact was signed by *that* GitHub Actions workflow on this repo."

```bash
cosign verify-blob \
  --certificate-identity-regexp "^https://github.com/prexorjustin/prexorcloud/.github/workflows/release.yml@refs/tags/" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  --signature checksums.txt.sig \
  --certificate checksums.txt.pem \
  checksums.txt

sha256sum -c checksums.txt
```

The release workflow runs `cosign verify` against its own freshly-signed
images as the last step, so a broken signature fails CI before
operators ever see it.

### Key-based: third-party modules

Module signing is a different problem — third-party authors with their
own keys, not us. The verifier accepts two formats:

- `<jar>.cosign.bundle` — the new sign-blob bundle format. Supports
  raw-keyed signatures, embedded-cert signatures with PKIX validation,
  and embedded-cert pinning against raw pubkey trust roots.
- `<jar>.sig` — legacy keyed PEM sidecar. Deprecated; supported for
  back-compat.

Configure under `modules.signing`:

```yaml
modules:
  signing:
    required: true                     # production default
    mode: COSIGN_BUNDLE                # COSIGN_BUNDLE | KEYED
    trustRoot: "config/cosign-roots.pem"
    rekor:
      policy: REQUIRE_SET              # DISABLED | REQUIRE_SET
      publicKey: "config/rekor.pub"
```

`REQUIRE_SET` enforces **offline Rekor SET verification**: the
controller loads Rekor's public key locally, parses the bundle's
`SignedEntryTimestamp`, reconstructs the canonical JSON of the Rekor
payload, and rejects bundles whose SET does not verify. **No network
access is required.**

Inclusion-proof Merkle-path verification is *not* implemented. SET is
enough — see the decisions
record.

A signature failure on install returns HTTP 422
`SIGNATURE_VERIFICATION_FAILED`. The integration test
`CosignSignedModuleInstallTest` exercises the full path.

### Daemon-side enforcement

Daemons that re-receive modules over the gRPC stream can verify
signatures locally. The `cloud-security/signing` package is shared
between controller and daemon; the daemon writes the inbound jar plus
sidecar to a temp directory as siblings (the on-disk shape the
verifier expects) and runs `verify()` before commit. Configure under
`daemon.modules.signing`.

## Threat model

The detail is in the project's [security threat model
doc](https://github.com/prexorjustin/prexorcloud/blob/main/docs/security/threat-model.md).
The short version of what auth defends:

| Threat | Mitigation |
|---|---|
| Brute-force operator login | Lockout (configurable threshold, window, duration), shared via Valkey across HA controllers |
| Stolen JWT | Short lifetime, revocable. Caller must know the JWT is stolen for revocation to fire. |
| Stolen daemon key | Per-node revocation; mTLS interceptor enforces immediately. Recovery: revoke, re-bootstrap. |
| Stolen plugin token | Short TTL, per-instance scope, revoked on stop, sequence-window replay protection. |
| Operator account takeover via reset email | Reset token single-use, 30-minute TTL, bound to the user. Compromised email is still a real risk. |
| Compromised module supply chain | Cosign verification with optional Rekor SET enforcement. Unsigned modules reject in production. |

What auth does *not* defend against:

- A compromised controller host (root on the controller is game over).
- A compromised daemon host (the daemon's instances are compromised;
  the cluster's other instances are not, modulo proxy-side player
  data).
- Operator misuse of a legitimate ADMIN account. Audit logs reveal it
  after the fact; nothing prevents it.

## Production hardening checklist

Within five minutes of first install:

1. Change the bootstrap admin password.
2. Shred the `.initial-admin-password` file.
3. Restrict `network.allowedSubnets` to operator and daemon subnets.
4. Terminate TLS at a reverse proxy if the controller REST is exposed
   beyond a private network. Set `http.trustedProxyCidrs` so
   `network.allowedSubnets` evaluates the real client IP.
5. Enable module-signing enforcement
   (`modules.signing.required=true`) plus a configured trust root if
   you plan to install third-party modules.

The full checklist lives at [Operations / Production
Checklist](/operations/production-checklist/).

## Next up

- [Cluster Model](/concepts/cluster-model/) — what Valkey holds, what
  the lease-and-fencing model protects.
- [Module Lifecycle](/concepts/modules/lifecycle/) — what happens at
  signature-verification time on install.
- [Plugins](/concepts/plugins/) — plugin token issuance and route
  segregation in detail.
- [Operations / Authentication](/concepts/security/) — the
  operator-facing how-to.
