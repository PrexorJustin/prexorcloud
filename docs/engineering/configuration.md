# PrexorCloud Configuration Reference

---

## Controller Configuration (`controller.yml`)

**File:** `java/cloud-controller/src/main/resources/defaults/controller.yml`

The controller loads configuration from `config/controller.yml` on startup. All fields have defaults via the `ControllerConfig` record's compact constructor.

### HTTP Server

```yaml
http:
  host: "127.0.0.1"    # Bind address (use 0.0.0.0 for Docker)
  port: 8080            # REST API port
```

### gRPC Server

```yaml
grpc:
  host: "127.0.0.1"    # Bind address (use 0.0.0.0 for Docker)
  port: 9090            # gRPC port for daemon connections
```

### Network

```yaml
network:
  cors:
    origins: ["http://localhost:3000"]  # Allowed CORS origins
    credentials: true                   # Allow credentials
  rateLimiting:
    perIp: 100          # Max requests per IP per 60-second window
    perUser: 200        # Max requests per user per 60-second window
```

### Database

```yaml
database:
  uri: "mongodb://localhost:27017"
  database: "prexorcloud"
```

### Runtime Profile

```yaml
runtime:
  profile: "development"   # "production" requires Redis at startup
```

### Redis

```yaml
redis:
  uri: "redis://localhost:6379"
```

Redis may still be omitted in `development`, but the controller will report degraded readiness until Redis is available. `production` refuses to start without a configured Redis URI.

Runtime coordination keys are namespaced under `prexor:v1:`. The version suffix is reserved for forward-compatibility — every read and write uses this namespace today.

TTL and retention expectations are centralized in the controller and exposed through `GET /api/v1/system/redis/schema` for operators with `system.settings` permission.

Current defaults:

| Family | Prefix | TTL / retention |
| --- | --- | --- |
| Lease ownership | `prexor:v1:lease:` | scheduler-configured lease TTL |
| Lease fencing tokens | `prexor:v1:lease-token:` | no TTL |
| Runtime snapshots | `prexor:v1:node:` / `instance:` / `player:` | no TTL; removed on state cleanup |
| Plugin tokens | `prexor:v1:plugintoken:` | 15 minutes by default |
| JWT revocation | `prexor:v1:jwt:revoked:` | remaining JWT lifetime |
| Rate limits | `prexor:v1:ratelimit:` | 60 seconds |
| Console flood windows | `prexor:v1:console:window:` | 2x active flood window |
| Workload replay protection | `prexor:v1:workloadseq:` | workload token lifetime, 15 minutes by default |
| SSE tickets | `prexor:v1:sse:ticket:` | 30 seconds |
| SSE replay buffer | `prexor:v1:sse:sequence` / `replay` | no TTL; bounded by replay trim |
| Module Redis storage | `prexor:v1:platform:<moduleId>:` | module-managed |
| Login attempts / locks | `prexor:v1:login:fail:` / `prexor:v1:login:lock:` | failure-window / lockout-duration |

### Security

```yaml
security:
  jwtExpirationMinutes: 60    # JWT token lifetime
  jwtSecret: null              # Auto-generated if null (Base64, min 32 bytes)
  jwtPreviousSecrets: []       # Old secrets accepted during rotation
  lockout:
    enabled: true              # Account lockout on repeated failed logins
    maxAttempts: 5             # Failed logins before lock
    windowSeconds: 900         # Sliding failure window (15 min)
    lockoutSeconds: 900        # Lock duration (15 min)
  passwordReset:
    enabled: false             # Off by default; routes 404 when disabled
    tokenTtlMinutes: 30
    resetUrlBase: ""           # Dashboard URL; mailer appends /auth/reset-password?token=...
    smtp:
      host: ""                 # When blank, LogMailer writes the reset link to controller log
      port: 587
      startTls: true
      implicitTls: false
      username: ""
      password: ""
      from: ""                 # Defaults to no-reply@<host>
      connectTimeoutMs: 10000
      readTimeoutMs: 10000
```

When `runtime.profile=production`, lockout and password-reset token state
live in the coordination store and are shared across controllers. In
`development` the in-memory stores are used; locks and tokens are reset on
controller restart.

### Scheduler

```yaml
scheduler:
  intervalSeconds: 5     # Evaluation loop frequency
  defaultCooldownSeconds: 30  # Default scaling cooldown per group
```

### Heartbeat

```yaml
heartbeat:
  intervalMs: 10000      # Ping frequency to daemons
  timeoutMs: 30000       # Pong timeout before marking node dead
```

### Crash Detection

```yaml
crashes:
  loopThreshold: 3       # Crashes within window to trigger pause
  loopWindowSeconds: 300  # Sliding window for crash loop detection
```

### Metrics

```yaml
metrics:
  enabled: true          # Enable Prometheus /metrics endpoint
```

### Modules

```yaml
modules:
  directory: "modules"       # Path to module JAR directory
  dataDirectory: "modules/data"
  registries:                # Trusted module registries (signed JSON indexes)
    - "https://registry.prexorcloud.dev/index.json"
```

`registries` powers `prexorctl module search` and `prexorctl module install
<id>[@<version>]`. Each entry is the URL of a registry index — a static JSON
file listing `{moduleId, version, jarUrl, sha256, cosignBundleUrl, tags,
readme, …}`. On install the controller downloads the JAR, checks its SHA-256
against the index, and verifies the cosign/sig sidecar against
`modules.signing.trustRoot` — the registry is a discovery convenience, never a
trust anchor. Empty by default (registry install is opt-in); only configured
registry URLs are ever fetched.

### Maintenance

```yaml
maintenance:
  enabled: false         # Global maintenance mode
  message: null          # Message displayed during maintenance
  allowedGroups: []      # Groups exempt from maintenance
```

### Webhooks

```yaml
webhooks:
  - url: "https://hooks.example.com/alerts"
    events: ["INSTANCE_CRASHED", "NODE_DISCONNECTED"]
```

### Dashboard

```yaml
dashboard:
  # Dashboard-specific settings
```

### Paste Share (`share`)

Controls the operator-invoked `--share` workflow that uploads redacted text
artifacts (crash reports, log tails, diagnostics bundles) to a pastebin and
prints the resulting link. Defaults target [pste.dev](https://pste.dev) with
private pastes and a one-day expiry. The feature is **disabled by default** —
flip `share.enabled` to `true` once you've decided sharing is acceptable for
this cluster.

```yaml
share:
  enabled: false                # Off by default; set true to opt in
  pasteUrl: "https://pste.dev"  # Repoint to any pste-compatible instance
  pasteToken: null              # Optional bearer token for higher upstream rate limits
  defaultExpiry: "1d"           # 1h | 1d | 30d | never
  defaultPrivate: true
  defaultBurnAfterRead: true    # Hint only — see "Burn-after-read posture" below
  e2e: false                    # End-to-end encrypt with pste (passes x-e2e header)
```

**Permissions.** `share.invoke` gates the per-surface `POST .../share`
endpoints; `share.revoke` gates `GET /api/v1/shares*` (list + view) and
`POST /api/v1/shares/{id}/revoke`. Both ride in the `ADMIN` and `OPERATOR`
roles by default. Pure `VIEWER` accounts cannot push artifacts off the
cluster even though they can view crashes and logs.

**Persistence + revocation.** Every successful upload is recorded in the
`shares` Mongo collection (30-day TTL) — id, kind, resource, urls, the
upstream pste delete token, size, originating user/IP. The dashboard
"Recent Shares" page and `prexorctl share list / view / revoke` use this
record to let operators audit and one-click nuke any past share. Revocation
calls pste `DELETE` server-side using the stored delete token; the local
record is then marked `revokedAt` and an audit entry is written.

**Metrics.** Micrometer exposes:
- `prexorcloud_share_attempts_total{kind,outcome}` — counter
- `prexorcloud_share_upstream_errors_total{status}` — counter (status = upstream HTTP code, or `network` for transport failures)
- `prexorcloud_share_upload_bytes` — distribution (UTF-8 byte size of each successful upload)
- `prexorcloud_share_revocations_total{outcome}` — counter (success / error / missing-token)

**429 handling.** `PasteClient` retries once on a 429, honoring the
`Retry-After` header up to a 5-second ceiling. Beyond that single retry the
error propagates as a `502 PASTE_UPSTREAM_ERROR`.

**Privacy posture.**
- Every send is operator-invoked (`prexorctl … --share` or a dashboard Share
  button) — never silent, never automatic.
- Redaction is mandatory and applied server-side before upload. JWT secrets,
  database/Redis URI passwords, Authorization headers, JWT-like tokens, IPv4/6
  addresses, and `password=` / `token=` / `secret=` / `apikey=` k/v pairs are
  replaced with `***REDACTED***`.
- Bodies are bounded to ~4 MB with a visible `[truncated — …]` marker, well
  below pste's documented 5 MB cap.
- Only text is shared. A `.tar.gz` archive cannot land on pste; the diagnostics
  share endpoint renders the bundle as a multi-section text document instead.
- Every successful share returns a one-shot `deleteUrl` alongside the paste
  link — `prexorctl` prints it as `revoke: <url>` so the operator can nuke
  the paste immediately if they shared the wrong artifact.

**Burn-after-read posture.** Crash and log shares always upload with
`x-burn-after-read: 1` because they represent a single-reader operator →
maintainer flow. Diagnostics shares never set burn-after-read because they
typically have multiple readers. Both behaviours are independent of the
`defaultBurnAfterRead` config field — it remains as a documented hint but does
not override the per-surface defaults. A per-request override on the REST API
or CLI (`--burn-after-read=false` etc.) still wins.

---

## Daemon Configuration (`daemon.yml`)

**File:** `java/cloud-daemon/src/main/resources/defaults/daemon.yml`

### Node Identity

```yaml
nodeId: "node-1"            # Unique node identifier (alphanumeric, 1-64 chars)
advertiseAddress: null       # Address controller should use to reach this daemon
```

### Controller Connection

```yaml
controller:
  host: "127.0.0.1"         # Controller gRPC host
  port: 9090                 # Controller gRPC port
```

### Instance Management

```yaml
instances:
  directory: "instances"     # Base directory for instance data
  portRange:
    start: 30000             # First available port
    end: 40000               # Last available port
```

### Node Labels

```yaml
labels:                      # Key-value pairs for affinity/anti-affinity
  region: "us-east"
  tier: "premium"
```

### Security

```yaml
security:
  certDirectory: "certs"     # Certificate storage directory
  joinToken: null             # One-time registration token
```

### Reconnection

```yaml
reconnect:
  initialDelayMs: 500        # First retry delay
  multiplier: 2.0            # Backoff multiplier
  maxDelayMs: 30000          # Maximum retry delay
```

---

## RBAC Roles (`roles.yml`)

**File:** `java/cloud-controller/src/main/resources/defaults/roles.yml`

Three built-in roles:

```yaml
roles:
  - name: ADMIN
    permissions: ["*"]       # All permissions

  - name: OPERATOR
    permissions:
      - nodes.view
      - nodes.drain
      - groups.view
      - groups.create
      - groups.update
      - groups.start
      - instances.view
      - instances.stop
      - instances.command
      - instances.console
      - templates.view
      - templates.create
      - templates.update
      - crashes.view
      - tokens.view
      - modules.view
      - catalog.view
      - audit.view
      - metrics.view
      - events.stream

  - name: VIEWER
    permissions:
      - nodes.view
      - groups.view
      - instances.view
      - templates.view
      - crashes.view
      - catalog.view
      - metrics.view
      - events.stream
```

---

## Group Configuration

Groups are configured via the REST API or YAML files in `config/groups/`. Key fields:

```yaml
name: "lobby"
parent: null                  # Inherit from another group
platform: "PAPER"             # PAPER, SPIGOT, VELOCITY, BUNGEECORD
version: "1.21.4"             # Platform version
category: "SERVER"            # SERVER or PROXY

# Scaling
scalingMode: "DYNAMIC"        # STATIC, DYNAMIC, MANUAL
minInstances: 1
maxInstances: 10
staticCount: null              # Fixed count for STATIC mode

# Resources
memoryMb: 1024
maxPlayers: 50
jvmArgs: []                    # Additional JVM arguments
env: {}                        # Environment variables

# Dynamic scaling
scaleUpThreshold: 0.8          # Scale when all instances > 80% full
scaleDownAfterSeconds: 300     # Scale down after 5 min empty
cooldownSeconds: null          # Per-group cooldown (overrides default)

# Templates
templates: []                  # Additional templates beyond base chain

# Affinity
affinityLabels: {}             # Node must have these labels
antiAffinityLabels: {}         # Node must NOT have these labels

# Dependencies
dependsOn: []                  # Groups that must have RUNNING instances first
startupWeight: 100             # Priority within dependency tier (higher = first)

# Maintenance
maintenance: false
maintenanceMessage: null

# Updates
updateStrategy: "rolling"     # How rolling restarts are performed

# MOTD
motds: []                      # MiniMessage-formatted MOTDs
motdMode: "STATIC"            # STATIC or ROTATING
motdIntervalSeconds: 30

# Routing
defaultGroup: false           # Whether proxies route here by default
```

---

## Environment Variables

### Controller

| Variable | Purpose |
|---|---|
| `PREXORCLOUD_HTTP_HOST` | Override HTTP bind address |
| `PREXORCLOUD_GRPC_HOST` | Override gRPC bind address |

### Daemon

| Variable | Purpose |
|---|---|
| `PREXORCLOUD_CONTROLLER_HOST` | Controller hostname |
| `PREXORCLOUD_CONTROLLER_GRPC_PORT` | Controller gRPC port |

### Instance (Injected by Daemon)

| Variable | Purpose |
|---|---|
| `CLOUD_INSTANCE_ID` | Instance identifier |
| `CLOUD_GROUP` | Group name |
| `CLOUD_PORT` | Assigned port |
| `CLOUD_NODE_ID` | Host node identifier |
| `CLOUD_CONTROLLER_URL` | Controller REST API URL |

### CLI

| Variable | Purpose |
|---|---|
| `PREXOR_CONTROLLER` | Default controller URL |
| `PREXOR_TOKEN` | Default auth token |
| `PREXOR_OUTPUT=json` | Enable JSON output |

### Dashboard

| Variable | Purpose |
|---|---|
| `NUXT_PUBLIC_API_BASE` | Controller REST API base URL |

---

## Docker Compose

**File:** `docker-compose.yml`

Three services with 6 named volumes:

| Service | Ports | Volumes |
|---|---|---|
| `controller` | 8080 (REST), 9090 (gRPC) | data, templates, certs |
| `daemon` | None (connects to controller) | instances, cache, certs |
| `dashboard` | 3000 (Web UI) | None |

**Service dependencies:** daemon → controller, dashboard → controller.

## Setup Support Matrix

`prexorctl setup` currently supports two operator paths:

| Setup mode | Supported platforms | Notes |
|---|---|---|
| `native` | Linux only | Installs Java/MongoDB/Redis on supported Linux distros and can register systemd services |
| `compose` | Linux, macOS, Windows | Requires Docker and Docker Compose; generates a Compose project around the downloaded JARs |

On macOS and Windows, use `prexorctl setup --install-mode=compose`.
