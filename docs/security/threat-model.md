# Threat model

STRIDE-style threat model for PrexorCloud v1. Updated whenever a new trust boundary is added or an existing assumption changes.

## 1. System overview

PrexorCloud is a single-tenant, self-hosted Minecraft cloud orchestrator.

```
                    +------------------+
                    |  Operator (CLI / |
                    |  Dashboard user) |
                    +--------+---------+
                             | HTTPS (REST/SSE) + login
                             v
+---------+   mTLS gRPC   +-----------+   Mongo wire    +----------+
| Daemon  | <-----------> | Controller| <-------------> | MongoDB  |
| (per   |                |  (1+ HA)  |                 +----------+
|  node)  |                |           |   Redis wire   +----------+
+--------+                +-----+-----+ <-------------> | Valkey   |
   |                            ^                       +----------+
   | spawns                     |
   v                            | TCP                   +----------+
+--------+                      +---------------------- | Velocity |
| MC     |                      proxy ↔ controller REST | / Bungee |
| server | <----- TCP --------- player traffic --------->| proxy    |
+--------+                                              +----------+
                                                             ^
                                                             |
                                                       +-----+----+
                                                       | Minecraft|
                                                       | client   |
                                                       +----------+
```

Controllers are active-active under shared MongoDB + Valkey; mutating work is gated by Valkey leases (see [`../architecture.md`](../architecture.md) §6).

## 2. Assets

| # | Asset | Confidentiality | Integrity | Availability |
|---|---|---|---|---|
| 1 | JWT signing secret (`security.jwtSecret`) | High | High | Med |
| 2 | Controller mTLS CA private key | High | High | Med |
| 3 | Per-daemon mTLS material | High | High | Med |
| 4 | MongoDB credentials in `controller.yml` | High | High | Med |
| 5 | Bootstrap admin password | High | High | Low |
| 6 | Audit log (Mongo) | Med | High | Med |
| 7 | Module bytecode (in-process) | Low | High | High |
| 8 | Module-owned per-module storage | Med | Med | Med |
| 9 | Composition plans (Mongo) | Low | High | High |
| 10 | Workflow intent records (Mongo) | Low | High | High |
| 11 | SSE replay buffer / event sequence | Low | Med | Med |
| 12 | Login attempt counters (Valkey) | Low | Med | Low |
| 13 | Player session/journey data (Mongo) | Med | Med | Med |
| 14 | Player game data inside MC instances | Operator-defined; PrexorCloud passes through |||
| 15 | Operator dashboard sessions (cookies) | High | High | Low |
| 16 | Module signing trust root | Low | High | Med |
| 17 | Password-reset email tokens (Valkey) | Med | High | Low |

## 3. Trust boundaries

1. **Internet ↔ Reverse proxy ↔ Controller REST** — operator browser / `prexorctl` traffic. Authenticated by JWT after `POST /api/v1/auth/login`. Optional password reset via signed email token.
2. **Controller ↔ Daemon (gRPC)** — mutual TLS; identity from per-daemon certificate issued by the controller CA. Revocation enforced by `MtlsEnforcementInterceptor` consulting `NodeCertificateRevocationStore`.
3. **Controller ↔ MongoDB** — TLS and credentialed wire connection (operator responsibility). MongoDB is trusted for storage but not for tenant isolation; PrexorCloud is single-tenant.
4. **Controller ↔ Valkey** — TLS optional, password optional; runs on a trusted operator network. Coordination state is treated as soft and wiped on suspected compromise without losing durable platform state.
5. **Controller ↔ Module classloader** — modules execute in-process. The trust boundary is the signed-package check at install time and the `CapabilityRegistry` at runtime. **Modules can compromise the controller**; operators must vet them.
6. **Proxy plugin (Velocity / Bungee) ↔ Controller REST** — plugin-token based; tokens are scoped, revocable, and replay-protected by Valkey sequence windows.
7. **MC instance ↔ Daemon (process)** — daemon spawns the JVM; cgroup / container isolation is **not** enforced. Daemon trusts its host kernel.
8. **Player ↔ Proxy ↔ Instance** — handled by Velocity / Bungee. PrexorCloud is not in this auth path.

## 4. STRIDE threats and mitigations

`M` = mitigated; `P` = partially mitigated (gap); `O` = open / out of scope.

### 4.1 Operator → Controller REST

| Threat | STRIDE | Status | Mitigation / Gap |
|---|---|---|---|
| Credential stuffing / brute force on `/auth/login` | S | M | Login lockout: configurable threshold / window / duration. Valkey-backed in production. |
| Stolen JWT replay after operator logoff | S | M | Server-side revocation via `RedisJwtRevocationStore`; logout revokes by JTI with TTL == remaining lifetime. |
| JWT signing-key compromise | S | M | Rotation via `security.jwtPreviousSecrets`. See [`../runbooks/rotate-secrets.md`](../runbooks/rotate-secrets.md). |
| Session fixation / CSRF | T | M | Bearer-token auth (no cookie auth on REST); SameSite + HttpOnly on dashboard cookie. CORS restricted to `network.cors.origins`. |
| Privilege escalation via REST | E | M | Permission-scoped routes, role-set re-resolved on every request. Custom roles editable via `roles.yml`. |
| Password-reset enumeration | I | M | `POST /auth/password-reset/request` always returns 202 regardless of whether the email exists. |
| Password-reset token replay | S | M | Single-use via `GETDEL` semantics on the Valkey-backed token store; 30-minute TTL. |
| Volumetric DoS | D | P | Per-IP and per-user rate limits via `RateLimitMiddleware` (Valkey-backed in production). Reverse-proxy WAF still recommended. |
| Information disclosure via verbose errors | I | M | Error envelopes scrub stack traces in production profile. |
| Forged events on SSE channel | T | M | Atomic SSE tickets; replay-protected by sequence numbers + Valkey retention. |
| Bypass of `network.allowedSubnets` | E | P | CIDR matcher applied at the HTTP layer; relies on `X-Forwarded-For` only when an explicit trusted-proxy list is set. Misconfigured reverse proxies can bypass — operator responsibility. |

### 4.2 Controller → Daemon (gRPC)

| Threat | STRIDE | Status | Mitigation / Gap |
|---|---|---|---|
| Rogue daemon impersonation | S | M | mTLS with controller CA; `MtlsEnforcementInterceptor` validates SAN/CN against issued cert. |
| Stolen daemon certificate | S | M | `POST /api/v1/nodes/{id}/revoke-cert`; in-memory + Valkey-backed `NodeCertificateRevocationStore`; new handshakes fail at TLS, existing connections fail on next RPC. |
| MITM downgrade | T,I | M | TLS context built by `TlsContextBuilder`; live reload via `ReloadableServerSslContext`; no PSK fallback. |
| Daemon enumeration via REST | I | M | `nodes` routes require authenticated operator with appropriate permission. |
| gRPC-level resource exhaustion | D | P | Default Netty limits + per-call deadlines; no explicit per-daemon quotas yet. |
| Replay of expired daemon credential | S | M | Workload-identity issuance / rotation / revocation tested; replay rejection covered. |

### 4.3 Controller ↔ MongoDB / Valkey

| Threat | STRIDE | Status | Mitigation / Gap |
|---|---|---|---|
| Unauthorized DB access | S, E | O | Operator responsibility — provision DB users, restrict to controller subnet. |
| Network sniffing | I | O | Operator responsibility — enable TLS on Mongo and Valkey. PrexorCloud accepts both `redis://` and `rediss://`. |
| Audit-log tampering | T | P | Append-only by convention; no cryptographic chaining. Tamper-evident audit log is a v2 conversation. |
| Coordination store wipe / hijack | T, D | M | Coordination state is non-durable by design. JWT revocation, login lockout counters, leases, and replay windows survive controller restart but tolerate Valkey loss with documented impact ([`../runbooks/recover-redis.md`](../runbooks/recover-redis.md)). |
| Mongo restore replay (rolling back beyond audit) | T | P | `RestoreExecutor` records a manifest; operator process must keep restore decisions auditable out-of-band. |

### 4.4 Controller ↔ Module

| Threat | STRIDE | Status | Mitigation / Gap |
|---|---|---|---|
| Malicious module installs (unsigned) | E, T | M | Production fail-closed via `PlatformModuleSignatureVerifier.failClosed()`. Cosign-compatible verifier with raw-keyed + embedded-cert PKIX paths. Optional offline Rekor SET enforcement when `modules.signing.rekor.policy=REQUIRE_SET`. |
| Module exfiltrates secrets via classloader reflection | I | P | Module classloader is isolated (URLClassLoader child of platform parent). Modules cannot read other modules' per-module storage. **They can still call `System.getenv` and read the controller's own config** — operators must treat modules as trusted code at install time. |
| Module classloader leak after unload | D | M | Try-with-resources on `LoadedRuntime.closeable`; `ModuleClassLoaderTracker` PhantomReference queue + force-cleanup REST. |
| Capability provider impersonation | S | M | `CapabilityRegistry` is single-provider per capability; binding is recorded with module id + version. |
| Cross-module classpath leakage | T | M | Modules link via capability handles only; no shared-classpath escape hatch. |
| Per-module storage scope escape | E, I | M | Storage prefixes derived server-side from authenticated module id; tested. |

### 4.5 Operator host

| Threat | STRIDE | Status | Mitigation / Gap |
|---|---|---|---|
| Compromise of `controller.yml` | I, T | O | File-permission hardening documented in install runbook. Outside threat model if root is hostile. |
| Compromise of CA private key | T, S | O | Rotation procedure in [`../runbooks/rotate-secrets.md`](../runbooks/rotate-secrets.md); root compromise out of scope. |
| Local user reads `.initial-admin-password` | I | M | `chmod 600` by setup; runbook reminds operator to delete after first login. |
| MC instance escapes its sandbox | E | O | No cgroup / container isolation in v1. Operator should run the daemon under a low-privilege user. |

### 4.6 Player → Proxy → Instance

PrexorCloud is **not** on the player-auth path. Velocity / Bungee plugins own player authentication, transfer routing, and chat moderation. Player data integrity inside the MC instance is the operator's responsibility.

The PrexorCloud surface visible to players is indirect: the Player Journey Bus records typed transitions (see [`../mc-domain.md`](../mc-domain.md)). Per-player metadata is covered under asset 13.

## 5. Out-of-scope scenarios

The following are deliberately not in the v1 threat model:

- Multi-tenant isolation. PrexorCloud is single-tenant; do not share a controller across hostile tenants.
- Kernel / hypervisor compromise on the controller or daemon host.
- Side-channel attacks (timing, cache, Spectre-class).
- Supply-chain attacks on transitive Java / Go / JS dependencies beyond the existing CVE scan + SBOM in CI.
- Quantum-capable adversaries. JWT and mTLS use classical algorithms.

## 6. Known gaps

| Gap | Notes |
|---|---|
| Cgroup / container isolation for MC instances | Out of scope for v1. See [`../decisions.md`](../decisions.md) §"No Kubernetes-style isolation." |
| Tamper-evident audit log | Append-only by convention; cryptographic chaining is a v2 conversation. |
| OpenTelemetry tracing across boundaries | Not in scope. See [`../decisions.md`](../decisions.md) §"Prometheus only, no OpenTelemetry." |
| OIDC / SSO / MFA | Not in scope. See [`../decisions.md`](../decisions.md) §"Username + password + JWT only." |
| Daemon PKCS12 keystore-password rotation | The PKCS12 password is delivered in the gRPC bootstrap response and never rotated. Confidentiality relies on the mTLS channel that carries it. A future hardening could rotate via a follow-up RPC once the daemon's first launch confirms the keystore was unsealed; not urgent at single-tenant scale. |
| Join-token plaintext returned in-band | `BootstrapService.create` returns the plaintext join token inside the RPC reply (`JoinTokenResult`). Confidentiality relies on the operator-side TLS that carries it (REST → controller) and on log redactors not stringifying the result. `JoinTokenResult.toString` and `JoinToken.toString` redact the plaintext, covered by `JoinTokenRedactionTest`. |

## 7. Review cadence

This document is reviewed:

- On every release.
- Whenever a new trust boundary is introduced.
- After any reported security issue, regardless of whether the fix is shipped.
