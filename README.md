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
  <a href="https://prexor.cloud"><img src="https://img.shields.io/badge/docs-prexor.cloud-informational?style=flat-square" alt="Docs"></a>
</p>

<p align="center"><sub>v1.0 — production-ready. <a href="https://github.com/prexorjustin/prexorcloud/releases">Release notes</a></sub></p>

---

## What is PrexorCloud?

PrexorCloud is an open-source platform for orchestrating Minecraft server infrastructure across multiple machines. It handles instance scheduling, auto-scaling, rolling deployments, crash recovery, and real-time monitoring -- so you can run networks of any size without manual intervention.

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
| **Intelligent Auto-Scaling** | Dynamic, static, and manual scaling modes per group. Configurable thresholds, cooldowns, and routing strategies. |
| **Multi-Node Orchestration** | Weighted 4-factor node scoring (memory, CPU, instance count, group spread). gRPC bidirectional streaming with node draining, cordoning, and cache pre-warming. |
| **Rolling Deployments** | Zero-downtime updates with pause, resume, and rollback. Crash-loop detection auto-pauses broken groups. |
| **Zero-Trust Security** | Self-managed CA with mTLS for all internal traffic. JWT + RBAC with 28 granular permissions. Join-token node bootstrap. Full audit logging. |
| **Template Engine** | Layered template inheritance with variable substitution, SHA-256 versioned snapshots, instant rollback, and an in-browser file editor. |
| **Real-Time Dashboard** | Nuxt 4 SPA with SSE-powered live updates, server console streaming, player management, and customizable themes. |
| **Module System** | Build extensions with their own REST routes, events, database access, and Vue dashboard UIs -- all hot-reloadable. |
| **Plugin Ecosystem** | Cloud-aware plugins for Velocity, BungeeCord, and Paper. Automatic server registration, player transfers, and channel messaging. |
| **Observability** | Prometheus metrics export, structured JSON logging, 22 SSE event types, and a queryable audit trail. |

## Quick Start

### Docker Compose (recommended)

```bash
git clone https://github.com/prexorjustin/prexorcloud.git
cd prexorcloud
docker compose up -d
```

The controller API is available at `localhost:8080`, the dashboard at `localhost:3000`.

`prexorctl setup` also supports `--install-mode=compose` for operators who want
the CLI to generate a Compose project around the downloaded controller or daemon
JARs. Native `prexorctl setup` installs remain Linux-only.

On first start, the controller generates an admin password and prints it to the log:

```bash
docker compose logs controller | grep "Initial admin password"
```

### From Source

**Prerequisites:** Java 25+, Node.js 22+ with pnpm, Go 1.24+

```bash
# Controller + Daemon
cd java && ./gradlew :cloud-controller:shadowJar :cloud-daemon:shadowJar

# Run controller
java --enable-preview -jar cloud-controller/build/libs/cloud-controller-*-all.jar

# Run daemon (on each node)
java --enable-preview -jar cloud-daemon/build/libs/cloud-daemon-*-all.jar

# Dashboard
cd dashboard && pnpm install && pnpm build

# CLI
cd cli && go build -o prexorctl .
```

### Adding Nodes

```bash
# On the controller: generate a join token
prexorctl token create --node worker-1

# On the node: start the daemon with the token
java --enable-preview -jar cloud-daemon-all.jar --join-token <token>
```

The daemon exchanges the token for mTLS certificates automatically. No manual PKI setup required.

## Project Structure

```
prexorcloud/
├── java/           Controller, daemon, modules, plugins (Gradle, Java 25)
├── dashboard/      Admin UI (Nuxt 4, Vue 3, TypeScript, Tailwind, shadcn-vue)
├── website/        Documentation site (Astro Starlight)
├── cli/            CLI tool (Go, Cobra)
└── deploy/         Reference Compose stack + systemd units
```

## Documentation

Full documentation is available at [prexor.cloud](https://prexor.cloud) covering installation, configuration, API reference, recipes, and module/plugin development guides.

Building an extension? See the [Plugins concept page](docs/public/en/concepts/plugins.md) and the [Modules overview](docs/public/en/concepts/modules/index.md) for the decision between a standalone `@CloudPlugin` jar (`prexorctl plugin new`) and a full module (`prexorctl module new`).

## Contributing

```bash
# Build everything
cd java && ./gradlew build
cd dashboard && pnpm install && pnpm dev
cd cli && go build -o prexorctl .

# Run checks
cd java && ./gradlew spotlessCheck build       # Java: formatting + tests
cd dashboard && pnpm lint && pnpm test          # Dashboard: lint + tests
cd cli && go vet ./... && go test ./...         # CLI: vet + tests
```

See the [development setup guide](https://prexor.cloud/contributing/development-setup) for IDE configuration and debugging instructions, and [CONTRIBUTING.md](CONTRIBUTING.md) for the PR workflow.

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.
