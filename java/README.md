# java/ — Gradle multi-project

JVM source tree for PrexorCloud. Gradle 8.x with the Kotlin DSL; toolchain pinned to JDK 25 (preview features enabled for controller/daemon/modules, Java 21 API for `cloud-api` and `cloud-modules/runtime`).

## Layout

- `cloud-controller/` — the controller JVM (REST + gRPC + scheduler + auth)
- `cloud-daemon/` — per-host agent (process management, daemon→controller gRPC)
- `cloud-api/` — public Java 21 SDK for plugin and module developers
- `cloud-common/`, `cloud-platform/` — shared utilities and BOM
- `cloud-protocol/` — generated gRPC/protobuf stubs (sources in `../contracts/`)
- `cloud-security/` — JWT, internal CA, mTLS, cosign verification
- `cloud-modules/` — first-party platform modules (runtime + example + 5 reference modules)
- `cloud-plugins/` — Minecraft-side plugins (Paper/Spigot/Folia + Velocity/BungeeCord)
- `cloud-test-harness/` — integration tests against real Mongo/Redis
- `test-fixtures/test-daemon-module/` — synthetic module used by harness tests
- `build-logic/` — shared Gradle convention plugins

## Common commands

```bash
cd java
./gradlew assemble            # build all jars
./gradlew test                # run unit tests
./gradlew :cloud-controller:run   # run the controller in dev mode
./gradlew :cloud-modules:stats-aggregator:shadowJar   # build one module
```

Add a new first-party module via `scripts/new-module.mjs`; the script wires the gradle project for you.
