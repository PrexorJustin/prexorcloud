---
title: Production checklist
description: The pre-production gate for PrexorCloud — security hardening, TLS, DR readiness, performance verification, and the test suite to run before real players connect.
---

A green install is not a production install. Walk this gate before you expose PrexorCloud to real players. Each section names the exact config key, command, default, and the failure you avoid by getting it right.

The checklist is ordered the way you'd actually do it: lock the bootstrap, flip the runtime profile, close the network, prove TLS and signing, set up backups and observability, then verify DR and performance before you cut over.

## Before you start

- A reachable Controller you can run `prexorctl` against (`prexorctl login` succeeds).
- Write access to `controller.yml` on every Controller host (default `/opt/prexorcloud/controller/config/controller.yml` for native installs).
- The release artifacts you intend to run, plus `cosign` on the host that extracts them.

The final section is a copy-paste checklist. Read the body once, then drive launches from the list.

## 1. Rotate the bootstrap admin password

The Controller seeds one admin user on first boot. The password comes from `security.initialAdminPassword` in `controller.yml`; if that field is blank, the Controller generates one and writes it to a file in the config directory.

Within five minutes of first login, set a real password through the dashboard or by creating a fresh admin user and deleting the seeded one:

```bash
# Create a replacement admin, then remove the seeded account.
prexorctl user create
prexorctl user list
prexorctl user delete admin
```

`prexorctl user create` prompts for username, password, and role. There is no `set-password` subcommand on the CLI — change a password through the dashboard, the password-reset flow, or by replacing the account.

Then clear the seed so it can't be replayed:

```yaml
# controller.yml
security:
  initialAdminPassword: ""   # blank after first boot; never commit a real value
```

If a generated bootstrap password ever reached a backup or a commit, treat every operator credential as compromised and rotate.

## 2. Set the runtime profile to `production`

```yaml
# controller.yml
runtime:
  profile: production
```

`profile` accepts `development` (default) or `production`; any other value fails validation at startup with `runtime.profile must be one of [development, production]`. The profile is lowercased and trimmed before the check.

`production` is not cosmetic. `ConfigValidator` enforces two things only in this profile:

| Rule | Failure message |
|---|---|
| Redis/Valkey must be configured | `redis.uri must be configured when runtime.profile=production` |
| Module signing trust root must be set (unless you opt out) | `modules.signing.trustRoot must be configured when runtime.profile=production` |

The profile also flips the module-signing default to required (see §6). Never run `development` against real players — it permits an unconfigured trust root and a missing coordination store.

## 3. Configure Valkey/Redis for coordination

Production needs a coordination store. Set it explicitly:

```yaml
# controller.yml
redis:
  uri: "redis://valkey-1:6379"
```

The `redis` block is nullable — omitting it disables Redis entirely, which validation rejects under `runtime.profile=production`. A blank `redis.uri` when the block is present also fails (`redis.uri must not be blank when redis is configured`). Default when present is `redis://localhost:6379`.

Put Valkey behind its own auth and TLS. Coordination state (leases, JWT revocation, login lockout, rate-limit counters, SSE replay) lives here and is shared across Controllers — do not co-locate it with an unrelated cache.

## 4. Restrict network exposure

The Controller binds wide open by default. Two listeners, two defaults:

| Listener | Config | Default host | Default port |
|---|---|---|---|
| REST + dashboard (HTTP) | `http` | `0.0.0.0` | `8080` |
| Daemon gRPC | `grpc` | `0.0.0.0` | `9090` |

`http.port` and `grpc.port` must differ, or startup fails.

### Pin the allowed subnets

`network.allowedSubnets` gates **both** the REST API (via `SubnetGuardMiddleware`) and the gRPC server (via `SubnetGuardInterceptor`). The default is wide open:

```yaml
# controller.yml — DEFAULT (do not ship this)
network:
  allowedSubnets:
    - "0.0.0.0/0"
    - "::/0"
```

Tighten it to your operator network and the Daemon subnet:

```yaml
# controller.yml
network:
  allowedSubnets:
    - "10.0.0.0/8"        # operator VPN
    - "10.42.0.0/16"      # Daemon subnet
```

The subnet guard evaluates the connecting peer's IP. There is **no** trusted-proxy / `X-Forwarded-For` CIDR setting; if you terminate TLS at a reverse proxy, the guard sees the proxy's address, so place the proxy inside an allowed subnet and enforce client restrictions at the proxy.

### Lock CORS

CORS origins live under `http.cors.allowedOrigins`. The default is a set of `localhost` dev ports — replace it with your real dashboard origin:

```yaml
# controller.yml
http:
  cors:
    allowedOrigins:
      - "https://dash.example.com"
```

Each origin must start with `http://` or `https://`, or validation fails with `cors.allowedOrigins: invalid origin '<value>'`.

## 5. Terminate TLS correctly

PrexorCloud has two transport planes with different TLS stories. Get both right.

### REST + dashboard: TLS is the proxy's job

The Controller serves plain HTTP on `http.port`. Put it behind a TLS-terminating reverse proxy and never expose `:8080` directly. The dashboard installer supports `--dashboard-tls-mode none | letsencrypt | custom | terminated-upstream` for the bundled nginx path; pick the mode that matches your edge.

### Controller ↔ Daemon gRPC: mTLS, already enforced

The gRPC plane (`:9090`) enforces mutual TLS. `MtlsEnforcementInterceptor` rejects any call without a verified client certificate:

- No TLS session → `UNAUTHENTICATED: mTLS required — no TLS session`
- No client cert → `UNAUTHENTICATED: mTLS required — no client certificate. Use BootstrapService to obtain one.`
- Revoked cert → `UNAUTHENTICATED: mTLS — client certificate revoked`

A Daemon obtains its certificate from the Controller's CA during join (the bootstrap and cluster-membership services are the only mTLS-exempt RPCs, authenticated by join-token HMAC instead). The Daemon stores its cert under `security.certificateDir` (default `config/security`). You don't configure mTLS — you confirm the gRPC port reaches Daemon hosts only and that the join flow completed.

## 6. Enforce module signatures

If you install Modules, fail closed on unverified signatures.

```yaml
# controller.yml
modules:
  signing:
    required: true
    mode: COSIGN_BUNDLE
    trustRoot: "/opt/prexorcloud/controller/config/security/module-trust.pem"
    rekor:
      policy: REQUIRE_SET
      publicKey: "/opt/prexorcloud/controller/config/security/rekor.pub"
```

Behavior and defaults, verified against `ModuleSigningConfig` and `ConfigValidator`:

| Key | Default | Notes |
|---|---|---|
| `required` | resolves to `true` in `production`, `false` in `development` | When `true`, install fails closed if a signature can't be verified. |
| `mode` | `KEYED` | `KEYED` uses a `<jar>.sig` sidecar against `PUBLIC KEY` blocks. `COSIGN_BUNDLE` uses a `<jar>.cosign.bundle` file. |
| `trustRoot` | unset | PEM bundle. Required when signing is required (validation error otherwise). |
| `rekor.policy` | `DISABLED` | `REQUIRE_SET` enforces an offline Rekor `SignedEntryTimestamp` without contacting Rekor. |
| `rekor.publicKey` | unset | Required when `policy` ≠ `DISABLED`. |

Two coupling rules the validator enforces:

- `rekor.policy=REQUIRE_SET` requires `mode=COSIGN_BUNDLE`. Mixing them fails startup.
- A required signing policy with no `trustRoot` fails startup.

Signature failure at install time returns `422 SIGNATURE_VERIFICATION_FAILED`. See [Cosign Pipeline](/internals/cosign-pipeline/).

## 7. Confirm lockout and rate limiting

Account lockout is on by default. Confirm the policy rather than assume it:

```yaml
# controller.yml — these are the defaults
security:
  lockout:
    enabled: true
    maxAttempts: 5
    windowSeconds: 900
    lockoutSeconds: 900
  rateLimiting:
    perIpPerMinute: 100
    perUserPerMinute: 300
    failOpenOnRedisError: false
```

| Key | Default | Validation floor |
|---|---|---|
| `lockout.enabled` | `true` | — |
| `lockout.maxAttempts` | `5` | `>= 1` |
| `lockout.windowSeconds` | `900` | `>= 1` |
| `lockout.lockoutSeconds` | `900` | `>= 1` |
| `rateLimiting.perIpPerMinute` | `100` | `>= 1` |
| `rateLimiting.perUserPerMinute` | `300` | `>= 1` |
| `rateLimiting.failOpenOnRedisError` | `false` | — |

In `production`, lockout and rate-limit state live in Valkey and are shared across Controllers, so an attacker can't cycle Controllers to bypass the count. Leave `failOpenOnRedisError=false` unless you'd rather drop rate limiting than reject traffic when Valkey is unreachable.

## 8. Set a managed JWT secret and a sane expiry

```bash
openssl rand -base64 48
```

```yaml
# controller.yml
security:
  jwtSecret: "<generated>"
  jwtExpirationMinutes: 1440      # 24h; default 1440
  jwtPreviousSecrets: []          # populated only mid-rotation
```

`jwtExpirationMinutes` must be `>= 1` and `<= 43200` (30 days); values outside that range fail validation. Auto-generated secrets work for first boot — rotate to a managed value before launch, using `jwtPreviousSecrets` to keep existing sessions valid during the cutover. See [Rotate Secrets](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/rotate-secrets.md).

## 9. Configure backups before real workloads land

The Controller dumps Mongo, the Redis-protocol store, and on-disk security/template/module state into a single bundle. The bundle stays **next to the Controller on disk** — the CLI does not transport it.

```bash
prexorctl backup create   # one bundle: Mongo + Redis + files. No --scope flag.
prexorctl backup list
prexorctl backup verify <id>
prexorctl backup prune --keep 14
prexorctl backup delete <id>
```

Backup behavior, verified against `BackupConfig` and the CLI:

| Key / flag | Default | Notes |
|---|---|---|
| `backup.directory` | `backups` | Controller-side path the bundles write to. |
| `backup.retentionCount` | `10` | `backup prune` keeps this many unless you pass `--keep`. |
| `backup create` | — | Takes no arguments; always a full bundle. |
| `backup prune --keep N` | server `retentionCount` | Keep the N most recent. |

Schedule the create as a systemd timer or cron on the Controller host, and ship the bundles off-host yourself (restic / rclone) — a backup that lives only on the Controller is one disk failure from useless.

Recommended baseline:

| Frequency | Retention | Off-host? |
|---|---|---|
| Hourly | 24h | no |
| Daily | 14 days | yes |
| Weekly | 90 days | yes |
| Pre-upgrade | until next stable window | yes |

Restore is scoped by flag, not by bundle:

```bash
prexorctl backup verify <id>          # check restorability first
prexorctl restore <id> --dry-run      # report planned changes, write nothing
prexorctl restore <id>                # both planes by default
prexorctl restore <id> --datastores=false   # filesystem only
prexorctl restore <id> --filesystem=false   # Mongo + Redis only
```

`--filesystem` and `--datastores` both default to `true`. A restore is rejected if the bundle fails verification, so run `backup verify <id>` first.

## 10. Wire Prometheus and the must-page alerts

`/metrics`, `/health`, and `/ready` are served unauthenticated at the top level of the HTTP listener — scrape and probe them directly.

```yaml
# prometheus.yml
scrape_configs:
  - job_name: prexorcloud
    metrics_path: /metrics
    scrape_interval: 15s
    static_configs:
      - targets: ['controller-1:8080', 'controller-2:8080']
```

### Readiness probe

`GET /ready` returns `200` when ready, `503` when not. The body lists four boolean checks from `ControllerReadinessProbe`:

```json
{ "status": "READY", "checks": { "mongo": true, "redis": true, "scheduler": true, "platformModules": true } }
```

`READY` requires **all four** true. `/health` always returns `status: UP` with the same readiness block nested — use `/ready` for load-balancer health, `/health` for liveness. The same data is served authenticated at `/api/v1/system/ready` and `/api/v1/system/health`.

### Alert on the real metric names

Metrics export with Micrometer's dot-to-underscore naming. Use these exact names (verified against `MetricsCollector`):

```yaml
groups:
  - name: prexorcloud-must-page
    rules:
      - alert: PrexorCloudControllerDown
        expr: up{job="prexorcloud"} == 0
        for: 2m
        labels: { severity: critical }
      - alert: PrexorCloudSchedulerLag
        expr: prexorcloud_scheduler_last_tick_lag_millis > 30000
        for: 2m
        labels: { severity: warning }
      - alert: PrexorCloudHttpErrorBudget
        expr: |
          sum(rate(prexorcloud_http_requests_total{status_class="5xx"}[5m]))
            / sum(rate(prexorcloud_http_requests_total[5m])) > 0.05
        for: 5m
        labels: { severity: critical }
      - alert: PrexorCloudNoDaemonSessions
        expr: prexorcloud_grpc_daemon_sessions_active == 0
        for: 2m
        labels: { severity: critical }
```

Useful series for dashboards and further alerts:

| Metric | Type | Meaning |
|---|---|---|
| `prexorcloud_scheduler_last_tick_lag_millis` | gauge | Age of the last scheduler tick. |
| `prexorcloud_scheduler_tick_failures_total` | counter | Scheduler ticks that threw. |
| `prexorcloud_http_requests_total` | counter | Tags `method`, `status_class`. |
| `prexorcloud_crashes_total` | gauge | Instances currently in the crash store. |
| `prexorcloud_grpc_daemon_sessions_active` | gauge | Connected Daemon sessions. |
| `prexorcloud_nodes_total` / `prexorcloud_instances_total` / `prexorcloud_players_total` | gauge | Live cluster counts. |
| `prexorcloud_sse_clients_connected` | gauge | Open SSE clients. |
| `prexorcloud_coordination_lease_contentions_total` | counter | Lease contention under HA. |

There is no `prexorcloud_crash_loops_total` series — alert on `increase(prexorcloud_crashes_total[1h])` instead. PrexorCloud ships no Grafana dashboard pack; build the panels you need from the labelled series. See [Monitoring](/operations/monitoring/).

## 11. Capture a diagnostics bundle path for incidents

Confirm operators can pull a redacted diagnostics bundle before you're paged at 03:14:

```bash
prexorctl diagnostics bundle --out /tmp/diag.tar.gz --log-lines 500
```

The bundle is built server-side and contains `manifest.json`, `readiness.json`, `overview.json`, `settings.json`, `config.json` (secrets redacted: JWT secrets, admin password, URI credentials), `redis.json`, `leases.json`, and best-effort `logs.txt`. `--log-lines 0` skips logs. Add `--share` to upload via the share endpoint. Logs older than the in-memory log buffer aren't retrievable through this surface — scrape on-disk logs separately for older incidents.

## 12. Size the boxes

Rule-of-thumb starting points; trend against your own load.

| Resource | Headroom |
|---|---|
| Controller CPU | ~1 vCPU per 500 Instances + 1 vCPU per 10k SSE clients |
| Controller heap | 1 GiB baseline + ~1 MiB per active Instance + Module overhead |
| Daemon CPU | Driven by hosted MC heaps; Daemon overhead negligible |
| Daemon memory | Σ MC Instance heaps + ~256 MiB Daemon overhead |
| MongoDB | ~1 GiB per 100 Instances per month of audit retention |
| Valkey | ~50 MiB per 1000 Instances; SSE replay buffer dominates |

Set JVM `-Xmx` slightly below the cgroup / container limit so the OOM killer doesn't reach the Controller before it can OOME cleanly.

Two scheduler/heartbeat knobs worth knowing (defaults shown):

```yaml
# controller.yml
scheduler:
  evaluationIntervalSeconds: 15
  scalingCooldownSeconds: 60
  nodeTimeoutSeconds: 90
  auditRetentionDays: 90
heartbeat:
  intervalMs: 30000        # >= 1000
  missedThreshold: 3       # >= 1
```

## 13. Harden the host

The reference systemd units under `deploy/systemd/` already apply the hardening below. Match it if you roll your own.

Controller (`prexorcloud-controller.service`):

```ini
User=prexorcloud
Restart=on-failure
LimitNOFILE=65536
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/prexorcloud/controller/data /opt/prexorcloud/controller/templates /opt/prexorcloud/controller/modules /opt/prexorcloud/controller/logs /opt/prexorcloud/controller/config/security
```

Daemon (`prexorcloud-daemon.service`) — it spawns many MC JVMs, so its fd and task ceilings are higher:

```ini
User=prexorcloud
Restart=on-failure
LimitNOFILE=131072
TasksMax=infinity
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/prexorcloud/daemon/instances /opt/prexorcloud/daemon/cache /opt/prexorcloud/daemon/logs /opt/prexorcloud/daemon/config/security
```

Disable swap on Mongo and Valkey hosts. Confirm `chronyd` or `systemd-timesyncd` is enabled on every Controller — fencing tokens tolerate skew, but lease *expiry* timing is real wall-clock.

## 14. Plan Controller HA

Single-Controller is fine for staging. Production deserves at least two Controllers sharing one MongoDB and one Valkey. The HA model is active-active with lease-scoped work, not active-passive.

Bring up the second Controller with the setup wizard (non-interactive mode shown), pointing both at the existing stores:

```bash
sudo prexorctl setup \
  --non-interactive \
  --component controller \
  --controller-mongo-mode remote \
  --controller-mongo-uri "$EXISTING_MONGO_URI" \
  --controller-redis-mode remote \
  --controller-redis-uri "$EXISTING_VALKEY_URI"
```

The setup flags are `--component`, `--controller-mongo-uri`, `--controller-redis-uri`, `--controller-http-port`, `--controller-grpc-port`, `--controller-cors-origin`, and the install/service-mode flags — there is no `--role`, `--mongo-uri`, or `--bootstrap` flag. A second Controller reads the existing CA from Mongo; you don't re-bootstrap it. See [HA Setup](/operations/ha-setup/).

## 15. Run the DR drill before launch

```bash
cd java
./gradlew :cloud-test-harness:drDrill
```

`drDrill` (a `@Tag("dr")` suite, excluded from the default test run) boots a real Controller against an ephemeral Mongo + Valkey, takes a backup, wipes both stores, restores, and asserts state matches. Run it once locally before launch to confirm your environment isn't missing anything. The same job runs nightly in CI:

```text
.github/workflows/nightly.yml :: dr-drill  →  ./gradlew :cloud-test-harness:drDrill
```

A red nightly `dr-drill` is a real DR regression. A real-environment quarterly drill remains on top of CI — see [Disaster Drill](/operations/disaster-drill/).

## 16. Verify performance baselines

```bash
cd java
./gradlew :cloud-test-harness:perfBaselines
```

`perfBaselines` (a `@Tag("perf")` suite, also excluded from the default run) executes `PerformanceBaselineTest` and writes `java/cloud-test-harness/build/reports/perf-baselines/baseline-report.json`. CI runs it nightly and compares against the committed baseline with the drift comparator:

```text
.github/workflows/nightly.yml :: perf-baselines
  ./gradlew :cloud-test-harness:perfBaselines
  ./scripts/perf-baseline-check.sh .../baseline-report.json
```

Run it once on hardware comparable to production. A green local run plus a green nightly comparator over the last week means no known performance regression is shipping.

## 17. Run the pre-launch test suite

Before cutover, run the suite that gates the platform:

```bash
cd java
./gradlew check          # unit + integration
./gradlew :cloud-test-harness:drDrill
./gradlew :cloud-test-harness:perfBaselines
```

The default test run in `cloud-test-harness` excludes the `perf` and `dr` tagged suites, so `drDrill` and `perfBaselines` must run explicitly as above (the `spike` suite is also excluded in CI). Treat any failure as a launch blocker.

## 18. Verify cosign before extracting any release

Every release tag publishes cosign-signed artifacts:

- `prexorctl` archives signed via cosign keyless on `release.yml`
- GHCR images for controller / daemon / dashboard signed via cosign keyless on `release-images.yml`

Verify the checksums before you run a binary:

```bash
cosign verify-blob \
  --certificate-identity-regexp "^https://github.com/prexorjustin/prexorcloud/.github/workflows/release.yml@refs/tags/" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  --signature checksums.txt.sig \
  --certificate checksums.txt.pem \
  checksums.txt
sha256sum -c checksums.txt
```

And the images:

```bash
cosign verify \
  --certificate-identity-regexp "^https://github.com/prexorjustin/prexorcloud/.github/workflows/release-images.yml@refs/tags/" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  ghcr.io/prexorjustin/prexorcloud-controller:<semver>
```

Both must succeed. See [Cosign Pipeline](/internals/cosign-pipeline/).

## Final pre-launch checklist

- [ ] Bootstrap admin password rotated; `security.initialAdminPassword` blanked
- [ ] `runtime.profile=production`
- [ ] `redis.uri` configured (Valkey behind its own auth + TLS)
- [ ] `network.allowedSubnets` restricted to operator + Daemon CIDRs (not `0.0.0.0/0`)
- [ ] `http.cors.allowedOrigins` set to the real dashboard origin
- [ ] REST + dashboard behind a TLS-terminating reverse proxy inside an allowed subnet
- [ ] Daemon mTLS join completed; gRPC `:9090` reachable from Daemon hosts only
- [ ] `modules.signing.required=true` with a configured `trustRoot` (and `rekor.policy`/`publicKey` if used)
- [ ] `security.lockout.enabled=true`; rate limits confirmed
- [ ] `security.jwtSecret` set to a managed value; `jwtExpirationMinutes` ≤ 43200
- [ ] Backup timer in place; bundles shipped off-host; `backup verify` clean
- [ ] Prometheus scraping `/metrics`; `/ready` wired to the load balancer; must-page alerts on the real metric names
- [ ] At least two Controllers against the same Mongo + Valkey
- [ ] MongoDB and Valkey behind their own auth / TLS
- [ ] `chronyd` / time sync enabled on every Controller; swap off on Mongo/Valkey hosts
- [ ] systemd units hardened (`ProtectSystem=strict`, `NoNewPrivileges`, scoped `ReadWritePaths`)
- [ ] `:cloud-test-harness:drDrill` green locally; nightly `dr-drill` green for the last 7 days
- [ ] `:cloud-test-harness:perfBaselines` green; nightly perf comparator clean
- [ ] `./gradlew check` green on the release commit
- [ ] Cosign verification documented in the runbook for every operator on rotation

## Next up

- [Configuration Reference](/operations/configuration/) — every key, every default
- [Monitoring](/operations/monitoring/) — Prometheus + alert rules
- [HA Setup](/operations/ha-setup/) — multi-Controller deployment
- [Disaster Drill](/operations/disaster-drill/) — walk a real scenario
- [Cosign Pipeline](/internals/cosign-pipeline/) — the signing and verification chain
