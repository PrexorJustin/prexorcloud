<p align="center">
  <strong>java/</strong><br>
  <em>The JVM source tree — controller, daemon, modules, plugins.</em>
</p>

<p align="center">
  <a href="https://github.com/prexorjustin/prexorcloud/actions"><img src="https://img.shields.io/github/actions/workflow/status/prexorjustin/prexorcloud/ci.yml?branch=main&style=flat-square" alt="CI"></a>
  <a href="../LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License"></a>
  <a href="https://prexor.cloud"><img src="https://img.shields.io/badge/docs-prexor.cloud-0c8aa8?style=flat-square" alt="Docs"></a>
</p>

---

## What is this?

The Gradle multi-project that builds everything that runs on the JVM: the controller, the per-node daemon, the public SDK, the platform modules, and the Minecraft-side plugins. Kotlin DSL, Gradle 9, toolchain pinned to JDK 25 with preview features for the controller/daemon/modules and a Java 21 API surface for `cloud-api` and `cloud-modules:runtime` (so plugins and modules compile against a stable target).

## Quickstart

```bash
cd java
./gradlew :cloud-controller:shadowJar :cloud-daemon:shadowJar
java --enable-preview --enable-native-access=ALL-UNNAMED \
  -jar cloud-controller/build/libs/PrexorCloudController.jar
```

```
PrexorCloud Controller starting…
Initial admin password: <generated>
REST listening on :8080 · gRPC on :8090
```

## Layout

- `cloud-controller/` — the controller JVM (REST + gRPC + scheduler + auth + Raft control plane)
- `cloud-daemon/` — per-host agent (process management, daemon→controller gRPC)
- `cloud-api/` — public Java 21 SDK for plugin and module developers
- `cloud-common/`, `cloud-platform/` — shared utilities and the version-catalog BOM
- `cloud-protocol/` — generated gRPC/protobuf stubs (sources in `../contracts/`)
- `cloud-security/` — JWT, internal CA, mTLS, cosign verification
- `cloud-modules/` — first-party platform modules (`runtime` + `example` + 7 reference modules: stats-aggregator, player-journey, webhook-alerts, discord-bridge, backup-orchestrator, tablist, protocol-tap)
- `cloud-plugins/` — Minecraft-side code: `proxy/{velocity,bungeecord,geyser}` and `server/{spigot,paper,folia,fabric,neoforge}`
- `cloud-test-harness/` — integration tests against real Mongo/Redis
- `test-fixtures/test-daemon-module/` — synthetic module used by harness tests
- `build-logic/` — shared Gradle convention plugins (see [`build-logic/README.md`](build-logic/README.md))

## Usage

```bash
cd java
./gradlew assemble                                     # build all jars
./gradlew spotlessCheck build                          # format check + full test suite (needs JDK 25)
./gradlew :cloud-modules:stats-aggregator:shadowJar    # build one module
./gradlew :cloud-plugins:server:fabric:remapJar        # build the Fabric server mod
```

Integration tests under `cloud-test-harness` need a real Mongo + Redis. Point them at running instances with `PREXOR_TEST_MONGO_URI` / `PREXOR_TEST_REDIS_URI`; without them the harness skips (green, but proving nothing).

Add a new first-party module with `../scripts/new-module.mjs` — it scaffolds the project skeleton, manifest, plugin stubs, and Gradle wiring.

## Links

- [Architecture](https://prexor.cloud/internals/architecture/)
- [Module SDK reference](https://prexor.cloud/reference/module-sdk/)
- [Engineering conventions](../docs/engineering/conventions.md)
- [Contributing](../CONTRIBUTING.md)

## License

Apache 2.0 — see [LICENSE](../LICENSE).
