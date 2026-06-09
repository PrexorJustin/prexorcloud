<p align="center">
  <strong>PrexorCloud</strong><br>
  <em>Minecraft cloud orchestration, production-grade by default.</em>
</p>

<p align="center">
  Groups, instances, templates, networks — orchestrated like infrastructure.<br>
  Cosign-signed modules, nightly DR drills, perf baselines as CI gates, TLS rotation without restart.<br>
  Built for the op on call at 2 AM, not the demo.
</p>

<p align="center">
  <a href="https://github.com/prexorjustin/prexorcloud/actions"><img src="https://img.shields.io/github/actions/workflow/status/prexorjustin/prexorcloud/ci.yml?branch=main&style=flat-square" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License"></a>
  <a href="https://prexor.cloud"><img src="https://img.shields.io/badge/docs-prexor.cloud-0c8aa8?style=flat-square" alt="Docs"></a>
</p>

<p align="center"><sub>v1.1 — production-ready. <a href="https://github.com/prexorjustin/prexorcloud/releases">Release notes</a></sub></p>

---

## What is PrexorCloud?

PrexorCloud is an open-source platform for orchestrating Minecraft server infrastructure across many machines. One controller schedules instances onto nodes, scales groups up and down, rolls out deployments, recovers from crashes, and streams the whole thing to a dashboard and CLI — so you run a network of any size without babysitting individual servers.

```
                    ┌──────────────────────┐
                    │     Controller       │
                    │  REST API · gRPC     │
                    │  Scheduler · Events  │
                    └──────┬───────┬───────┘
                 mTLS/gRPC │       │ JWT/REST + SSE
            ┌──────────────┤       ├──────────────┐
            v              v       v              v
      ┌──────────┐   ┌──────────┐   ┌───────────────┐
      │ Daemon 1 │   │ Daemon N │   │ Dashboard/CLI │
      │ Node     │   │ Node     │   │ Operator UI   │
      └──────────┘   └──────────┘   └───────────────┘
            │              │
       MC instances   MC instances
```

## Features

| | |
|---|---|
| **Intelligent auto-scaling** | Dynamic, static, and manual scaling modes per group. Configurable thresholds, cooldowns, and routing strategies. |
| **Multi-node orchestration** | Weighted 4-factor node scoring (memory, CPU, instance count, group spread). gRPC bidirectional streaming with node draining, cordoning, and cache pre-warming. |
| **Rolling deployments** | Zero-downtime updates with pause, resume, and rollback. Crash-loop detection auto-pauses broken groups. |
| **HA control plane** | Optional multi-controller cluster on embedded Apache Ratis (Raft). Quorum-consistent config, leader leases for the scheduler/reconciler, token-based controller join. |
| **Zero-trust security** | Self-managed CA with mTLS for all internal traffic. JWT + RBAC with granular permissions. Join-token node bootstrap. Full audit logging. |
| **Template engine** | Layered template inheritance with variable substitution, SHA-256 versioned snapshots, instant rollback, and an in-browser file editor. |
| **Real-time dashboard** | Nuxt 4 SPA with SSE-powered live updates, server console streaming, player management, and a Reef-themed design system. |
| **Module system** | Build extensions with their own REST routes, events, database access, and Vue dashboard UIs — cosign-signed, hot-reloadable, with a registry and resource quotas. |
| **Broad platform support** | Cloud-aware plugins for Paper/Spigot/Folia, Velocity, and BungeeCord; server mods for Fabric and NeoForge; first-class Bedrock routing via a Geyser sidecar. |
| **Observability** | Prometheus metrics on `/metrics`, OpenTelemetry traces, structured JSON logging, an SSE event stream, and a queryable audit trail. |

## Quickstart

### Docker Compose (recommended)

```bash
git clone https://github.com/prexorjustin/prexorcloud.git
cd prexorcloud
docker compose up -d
```

The controller API serves on `localhost:8080`, the dashboard on `localhost:3000`. On first start the controller generates an admin password and prints it to the log:

```bash
docker compose logs controller | grep "Initial admin password"
```

For a guided install (native systemd or a generated Compose project), run `prexorctl setup` and follow the browser wizard. Native installs are Linux-only.

### From source

**Prerequisites:** OpenJDK 25, Node.js 22 with pnpm, Go 1.24.

```bash
# Controller + daemon jars
cd java && ./gradlew :cloud-controller:shadowJar :cloud-daemon:shadowJar

# Run the controller
java --enable-preview --enable-native-access=ALL-UNNAMED \
  -jar cloud-controller/build/libs/PrexorCloudController.jar

# Run a daemon (on each node)
java --enable-preview --enable-native-access=ALL-UNNAMED \
  -jar cloud-daemon/build/libs/PrexorCloudDaemon.jar

# Dashboard
cd dashboard && pnpm install && pnpm build

# CLI
cd cli && go build -o prexorctl .
```

### Adding a node

```bash
# On the controller: mint a join token for the new node
prexorctl token create --node worker-1
```

Set the printed token as `security.joinToken` in the node's `daemon.yml` (or pass `--daemon-join-token` to `prexorctl setup`) and start the daemon. It exchanges the token for mTLS certificates automatically — no manual PKI setup. The token is single-use and consumed on enrollment.

## Project structure

```
prexorcloud/
├── java/           Controller, daemon, modules, plugins (Gradle 9, JDK 25/21)
├── dashboard/      Admin UI (Nuxt 4, Vue 3, TypeScript, Tailwind, shadcn-vue)
├── installer/      Browser setup wizard (Vite + Vue), embedded into prexorctl
├── website/        Documentation site (Astro Starlight)
├── cli/            prexorctl (Go, Cobra)
├── design-system/  Canonical Reef tokens + voice/visual spec
├── docs/           Public docs, runbooks, engineering notes
└── deploy/         Reference Compose stack + systemd units
```

## Documentation

Full documentation lives at [prexor.cloud](https://prexor.cloud): installation, configuration, API reference, recipes, and module/plugin development guides.

Building an extension? See the [plugins concept page](docs/public/en/concepts/plugins.md) and the [modules overview](docs/public/en/concepts/modules/index.md) to choose between a standalone `@CloudPlugin` jar (`prexorctl plugin new`) and a full module (`prexorctl module new`).

## Contributing

```bash
cd java      && ./gradlew spotlessCheck build   # Java: formatting + tests (needs JDK 25)
cd dashboard && pnpm install && pnpm test        # Dashboard: lint + tests
cd cli       && go vet ./... && go test ./...    # CLI: vet + tests
```

See the [development setup guide](https://prexor.cloud/contributing/development-setup) for IDE configuration and debugging, and [CONTRIBUTING.md](CONTRIBUTING.md) for the PR workflow.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
