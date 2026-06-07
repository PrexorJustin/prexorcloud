---
title: Production Checklist
description: Hardening, sizing, and pre-flight steps to walk before exposing PrexorCloud to real players.
---

A green install isn't a production install. This is the list to walk
before you put PrexorCloud in front of paying users — covering auth,
network exposure, signing, backups, alerts, and the boring details that
matter at 03:14.

## What you'll learn

- The exact knobs that flip a fresh install from "demo" to "production"
- Sizing rules of thumb for controllers, daemons, MongoDB, and Valkey
- The minimum alert set that pages when something is actually broken
- The bootstrap-secret hygiene every new operator forgets once

## 1. Lock down the bootstrap

Within five minutes of first login:

```bash
# Change the admin password.
prexorctl user set-password admin

# Shred the bootstrap file.
sudo shred -u /etc/prexorcloud/config/.initial-admin-password

# Confirm it is gone.
sudo ls -la /etc/prexorcloud/config/ | grep -i initial
```

If that file ever made it into a backup, treat the password as
compromised and rotate every operator credential.

## 2. Set the runtime profile to `production`

```yaml
# controller.yml
runtime:
  profile: production
```

`production` requires Valkey/Redis at startup. The wiring graph swaps
in real coordination accessors for leases, JWT revocation, login
lockouts, SSE replay, and rate limits. Without this flag the controller
runs single-writer with in-memory equivalents — never deploy that
profile to production.

## 3. Restrict network exposure

The controller listens on `0.0.0.0` by default. Pin it down:

```yaml
# controller.yml
network:
  allowedSubnets:
    - "10.0.0.0/8"        # operator VPN
    - "10.42.0.0/16"      # daemon subnet
http:
  cors:
    allowedOrigins:
      - "https://dash.example.com"
```

Put the REST + dashboard behind a TLS-terminating reverse proxy.
PrexorCloud serves plain HTTP — TLS termination is the proxy's job.
If you do that, configure trusted proxy CIDRs so `allowedSubnets`
evaluates the real client IP and not the proxy's.

Daemons connect over gRPC on `:9090`; that port should be reachable
from daemon hosts only.

## 4. Generate a real JWT signing secret

```bash
openssl rand -base64 48
```

Paste it into `controller.yml`:

```yaml
security:
  jwtSecret: "<generated>"
  jwtExpirationMinutes: 1440
  jwtPreviousSecrets: []      # populated only during rotation
```

Auto-generated secrets work for first boot but rotate to a managed
value before going live. See [Rotate Secrets](https://github.com/prexorjustin/prexorcloud/blob/main/docs/runbooks/rotate-secrets.md).

## 5. Turn lockout on (it's on by default — confirm)

```yaml
security:
  lockout:
    enabled: true
    maxAttempts: 5
    windowSeconds: 900
    lockoutSeconds: 900
```

Production-profile lockout state lives in Valkey and is shared across
controllers — one IP can't cycle controllers to bypass.

## 6. Enforce module signatures

If you plan to install third-party modules:

```yaml
modules:
  signing:
    required: true
    mode: COSIGN_BUNDLE
    trustRoot: "/etc/prexorcloud/config/security/module-trust.pem"
    rekor:
      policy: REQUIRE_SET    # offline Rekor SET enforcement
      publicKey: "/etc/prexorcloud/config/security/rekor.pub"
```

`required=true` makes module install fail-closed when a signature
cannot be verified. `REQUIRE_SET` binds the signature to a Rekor log
entry without contacting Rekor at install time. See
[Cosign Pipeline](/internals/cosign-pipeline/).

## 7. Configure backups before deploying real workloads

Schedule the CLI backup as a cron / systemd timer on the controller host:

```bash
# /etc/cron.hourly/prexorcloud-backup
prexorctl backup create --scope mongo

# /etc/cron.daily/prexorcloud-backup-full
prexorctl backup create --scope full
```

Recommended baseline:

| Frequency | Scope | Retention |
|---|---|---|
| Hourly | Mongo only | 24h |
| Daily | Full (Mongo + Valkey + filesystem) | 14 days |
| Weekly | Full + off-host ship | 90 days |
| Pre-upgrade | Full | Until next stable upgrade window |

Ship the dailies off-host. A backup that lives only on the controller
is one disk failure from useless.

## 8. Wire Prometheus + alerts

Scrape `/metrics`:

```yaml
# prometheus.yml
scrape_configs:
  - job_name: prexorcloud
    metrics_path: /metrics
    scrape_interval: 15s
    static_configs:
      - targets: ['controller-1:8080', 'controller-2:8080']
```

Install at minimum these alerts (full set in [Monitoring](/operations/monitoring/)):

```yaml
groups:
  - name: prexorcloud-must-page
    rules:
      - alert: PrexorCloudControllerDown
        expr: up{job="prexorcloud"} == 0
        for: 2m
        labels: { severity: critical }
      - alert: PrexorCloudCrashLoop
        expr: increase(prexorcloud_crash_loops_total[1h]) > 0
        labels: { severity: critical }
      - alert: PrexorCloudSchedulerLag
        expr: prexorcloud_scheduler_last_tick_lag_ms > 30000
        for: 2m
        labels: { severity: warning }
      - alert: PrexorCloudHttpErrorBudget
        expr: |
          sum(rate(prexorcloud_http_requests_total{status_class="5xx"}[5m]))
            / sum(rate(prexorcloud_http_requests_total[5m])) > 0.05
        for: 5m
        labels: { severity: critical }
```

PrexorCloud does not ship a Grafana dashboard pack — the metrics are
stable and labelled, build the panels you need.

## 9. Size the boxes

Rule-of-thumb starting points; trend with your own load.

| Resource | Headroom |
|---|---|
| Controller CPU | 1 vCPU per ~500 instances + 1 vCPU per 10k SSE clients |
| Controller heap | 1 GiB baseline + 1 MiB per active instance + module overhead |
| Daemon CPU | Driven by hosted MC heaps; daemon overhead negligible |
| Daemon memory | Σ MC instance heaps + ~256 MiB daemon overhead |
| MongoDB | ~1 GiB per 100 instances per month of audit retention |
| Valkey | ~50 MiB per 1000 instances; SSE replay buffer dominates |

Set JVM `-Xmx` slightly below the cgroup / container limit so the
killer doesn't reach for the controller before it OOMEs cleanly.

## 10. Ulimits and host hygiene

For the daemon process specifically (it spawns many MC JVMs):

```ini
# /etc/security/limits.d/prexorcloud-daemon.conf
prexorcloud  soft  nofile  131072
prexorcloud  hard  nofile  131072
prexorcloud  soft  nproc   unlimited
prexorcloud  hard  nproc   unlimited
```

systemd reference units in `deploy/systemd/` already set
`LimitNOFILE=131072` and `TasksMax=infinity` on the daemon. Match the
limit if you're rolling your own units.

Disable swap on Mongo and Valkey hosts. Confirm `chronyd` or
`systemd-timesyncd` is enabled on every controller — fencing tokens
tolerate skew but lease *expiry* timing is real.

## 11. Plan controller HA

Single-controller is fine for staging. Production deserves at least
two controllers sharing one MongoDB and one Valkey. The HA model is
active-active with lease-scoped work, not active-passive.

```bash
# On controller-2:
sudo prexorctl setup --role controller \
    --mongo-uri "$EXISTING_MONGO_URI" \
    --redis-uri "$EXISTING_VALKEY_URI" \
    --bootstrap=false
```

`--bootstrap=false` skips admin-user creation and CA generation; the
new controller reads the existing CA from Mongo. See
[HA Setup](/operations/ha-setup/).

## 12. Run a DR drill before launch

```bash
cd java
./gradlew :cloud-test-harness:drDrill
```

The harness boots a real controller against an ephemeral Mongo +
Valkey, takes a backup, wipes both stores, restores, and asserts state
matches. Run it once before launch to confirm your local environment
isn't missing anything. The same job runs nightly in CI
(`.github/workflows/nightly.yml :: dr-drill`); a CI failure on that
job is a real DR regression.

A real-environment quarterly drill remains on top of CI — see
[Disaster Drill](/operations/disaster-drill/).

## 13. Verify cosign before extracting any release

Every release tag publishes:

- `prexorctl` archives signed via cosign keyless on `release.yml`
- GHCR images for controller / daemon / dashboard signed via cosign
  keyless on `release-images.yml`

Verify before you run:

```bash
cosign verify-blob \
  --certificate-identity-regexp "^https://github.com/prexorjustin/prexorcloud/.github/workflows/release.yml@refs/tags/" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  --signature checksums.txt.sig \
  --certificate checksums.txt.pem \
  checksums.txt
sha256sum -c checksums.txt
```

For images:

```bash
cosign verify \
  --certificate-identity-regexp "^https://github.com/prexorjustin/prexorcloud/.github/workflows/release-images.yml@refs/tags/" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  ghcr.io/prexorjustin/prexorcloud-controller:<semver>
```

Both must succeed. See [Cosign Pipeline](/internals/cosign-pipeline/).

## 14. Final pre-launch checklist

- [ ] `runtime.profile=production`
- [ ] `network.allowedSubnets` restricted to operator + daemon CIDRs
- [ ] Reverse proxy in front of REST/dashboard with `http.trustedProxyCidrs` matching
- [ ] `security.jwtSecret` set to a managed value (not auto-generated)
- [ ] `security.lockout.enabled=true`
- [ ] `modules.signing.required=true` with a configured `trustRoot`
- [ ] Bootstrap admin password rotated; bootstrap file shredded
- [ ] At least two controllers configured against the same Mongo + Valkey
- [ ] MongoDB and Valkey behind their own auth / TLS, not shared with other services
- [ ] Backup cron in place; off-host shipping configured
- [ ] Prometheus scraping `/metrics`; pager set up for the SEV-1 alerts
- [ ] DR drill green locally; CI nightly green for the last 7 days
- [ ] systemd unit hardened (`ProtectSystem=strict`, `NoNewPrivileges`, scoped `ReadWritePaths`)
- [ ] Cosign verification documented in the runbook for every operator on rotation

## Next up

- [Configuration Reference](/operations/configuration/) — every key, every default
- [Monitoring](/operations/monitoring/) — Prometheus + alert rules
- [HA Setup](/operations/ha-setup/) — multi-controller deployment
- [Disaster Drill](/operations/disaster-drill/) — walk a real scenario
