# Security Policy

PrexorCloud is a self-hosted Minecraft cloud orchestrator. It holds operator
credentials, mTLS material between controller and daemons, JWT signing secrets,
audit logs, module bytecode that runs in-process, and persistent player data.
Security issues are taken seriously.

## Reporting a Vulnerability

**Do not file public GitHub issues for security reports.**

Preferred channel:

1. Open a private GitHub Security Advisory:
   <https://github.com/prexorjustin/prexorcloud/security/advisories/new>.
2. Or email `security@prexorjustin.me` with the subject `[prexorcloud] <short title>`.

In your report, include:

- A description of the vulnerability and the impact you believe it has.
- A minimal reproduction (commit/tag, config snippet, request payload).
- Whether the issue is exploitable remotely, locally, or only by an authenticated
  user. If authenticated, what role/permission is required.
- Whether you have already disclosed the issue elsewhere.

Acknowledgement targets:

- First response: within 5 business days.
- Triage decision (accept / reject / need-info): within 10 business days.
- Fix and coordinated disclosure: depends on severity (see below).

We will credit reporters in the release notes unless you ask us not to. We do
not currently run a paid bounty program.

## Supported Versions

PrexorCloud is pre-release. Until the M5 milestone tag, only `main` is
supported, and only the most recent release on `main` receives security fixes.
There is no LTS branch.

| Version          | Status         | Security fixes |
| ---------------- | -------------- | -------------- |
| `main` (HEAD)    | Pre-release    | Yes            |
| Tagged releases  | Pre-release    | Latest only    |
| Forks / vendored | Not supported  | No             |

After M5, this table will be updated with concrete supported version ranges
and an EOL policy.

## Scope

In scope:

- Controller (`java/cloud-controller`) — REST/gRPC surfaces, auth, lease and
  workflow handling, module lifecycle, recovery executor.
- Daemon (`java/cloud-daemon`) — process supervision, controller channel,
  certificate handling.
- `cloud-security` — JWT, CA, TLS context, login attempt store.
- `cloud-common`, `cloud-protocol`, `cloud-api` — shared platform code.
- `prexorctl` (`cli/`) — CLI auth, setup flow, module upload.
- Dashboard (`dashboard/`) — session handling, SSE auth, module SDK.
- First-party modules under `cloud-module-example/`.
- Build pipeline, signed-release tooling (when present), documented Compose
  / systemd configurations under `deploy/`.

Out of scope:

- Third-party modules installed by the operator. Modules run in-process with
  the controller; the operator is responsible for vetting them.
- Operator misconfiguration that violates documented requirements (e.g.
  exposing the controller REST port directly to the public internet without a
  reverse proxy, running with `runtime.profile=development` in production,
  reusing the bootstrap admin password).
- Issues that require physical access to the host or root on the controller
  host. The threat model assumes an honest host.
- Denial-of-service caused by client volume that exceeds documented rate
  limits. Rate limits are bypassable by an authenticated admin by design.
- Bugs in MongoDB, Redis/Valkey, the JVM, or the OS.

## Trust Model (short form)

- **Operator** is trusted. The operator runs the host, owns the bootstrap
  admin password, and can add modules. A hostile operator is not in the
  threat model.
- **Authenticated dashboard users** are partially trusted; their permissions
  are scoped by role. Privilege escalation by a non-admin user is in scope.
- **Daemons** are trusted within their lease. mTLS material identifies them;
  certificate revocation removes that trust on demand.
- **Modules** are trusted at install time (signed package check, when enabled
  via `modules.signing.required`). Bypassing the signature gate without
  config opt-out is in scope.
- **Players / Minecraft clients** are untrusted. They never speak to the
  controller directly; they reach instances via the proxy (Velocity /
  BungeeCord) only.
- **Network** is untrusted. mTLS protects controller↔daemon traffic.

For the full STRIDE-style threat model, see
[`docs/security/threat-model.md`](docs/security/threat-model.md).

## Hardening Checklist for Operators

This is not a substitute for the runbooks. See
[`docs/runbooks/`](docs/runbooks/) for full procedures.

- Run with `runtime.profile=production`. The development profile disables
  cross-controller coordination, JWT revocation persistence, login lockout
  persistence, and several other guarantees.
- Replace the bootstrap admin password (printed once on first start) before
  exposing the controller REST port to anyone else.
- Set `security.jwtSecret` to a cryptographically random value and keep it
  secret. Use `security.jwtPreviousSecrets` for rotation (see
  [`docs/runbooks/rotate-secrets.md`](docs/runbooks/rotate-secrets.md)).
- Enable `security.lockout` (default on, 5 attempts / 15 min window /
  15 min lockout). Tune for your operator profile.
- Set `network.allowedSubnets` to restrict REST/gRPC ingress to operator and
  daemon subnets.
- Enable `modules.signing.required=true` in production. Configure
  `modules.signing.trustRoot` to a path containing the public keys of module
  authors you trust. The current verifier is sidecar-`.sig`-based; the
  cosign-compatible verifier ships under M2.
- Put the controller REST port behind a reverse proxy that terminates TLS
  for browser clients; `controller↔daemon` traffic uses its own mTLS material
  and does not need to share the browser-facing certificate.
- Watch the audit log. Audit entries land in MongoDB; a CLI query and CSV
  export ship under M3.
- Run regular backups (`docs/runbooks/backup.md`).
- Subscribe to release notes for security advisories.

## Cryptographic Material at Rest

PrexorCloud stores the following secrets on disk by default:

- `config/controller.yml` — contains `security.jwtSecret`, MongoDB and Redis
  URIs (which may include credentials). `chmod 600`. Owned by the controller
  user.
- `data/certs/` — controller CA private key, daemon-issued certificates.
  `chmod 700`. Never check this directory into version control.
- `config/.initial-admin-password` — the bootstrap admin password, written
  on first start. **Delete this file after the first login**; the runbook
  [`docs/runbooks/install.md`](docs/runbooks/install.md) reminds operators.
- Module jars under the configured `modules.directory`. Treated as code, not
  secrets.
- Mongo and Redis data directories — depend on how those services are
  installed. PrexorCloud does not embed or ship them.

If any of the above is exposed (e.g. accidentally committed, leaked in a
backup), follow [`docs/runbooks/rotate-secrets.md`](docs/runbooks/rotate-secrets.md).

## Disclosure Examples

We will publish security advisories on GitHub under the project's Security
tab once the project leaves pre-release. Until then, advisories will be
embedded in the release notes for the affected tag and announced on the
project's primary communication channel.
