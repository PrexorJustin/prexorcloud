# build-logic

Gradle convention plugins for the PrexorCloud build. Apply exactly one toolchain plugin per subproject; layered plugins (module / plugin-*) stack on top of a toolchain.

## Quickstart

In a subproject's `build.gradle.kts`, apply one toolchain plugin — and, for a module or in-MC plugin, layer the matching convention on top:

```kotlin
plugins {
    id("prexorcloud.java25-preview")   // controller / daemon / modules
}
```

```kotlin
plugins {
    id("prexorcloud.java21-compat")    // an in-server plugin...
    id("prexorcloud.plugin-paper-1-21") // ...plus its platform convention
}
```

No version numbers here: the plugins pull Java, Logback, Jackson, and the rest from `java/gradle/libs.versions.toml`, so every subproject stays on one source of truth. The tables below list every plugin and when to reach for it.

## Toolchain plugins (mutually exclusive — pick one per subproject)

| Plugin | Toolchain | Use for |
|---|---|---|
| `prexorcloud.java25-preview` | Java 25 with `--enable-preview` | controller, daemon, modules-core, controller modules |
| `prexorcloud.java21-compat` | Java 21 (compat) | in-server / proxy plugins (matches MC host runtimes) |
| `prexorcloud.java21-api` | Java 21 (public API) | `cloud-api` — the SPI every module compiles against |
| `prexorcloud.java-common` | shared baseline | applied by the above; do not apply directly |

The three-toolchain split is intentional. The controller and daemon target a modern JDK with preview features (records patterns, scoped values) because they run on hosts the operator controls. In-server plugins must match the JDK their MC host actually runs on (Paper/Spigot/Folia ship Java 21 today), so they're pinned to a stable Java 21 toolchain. `cloud-api` is the SPI every external module compiles against, so it stays on plain Java 21 with no preview flags to keep the public API surface portable.

## Module / plugin convention plugins (stack on a toolchain)

| Plugin | Adds |
|---|---|
| `prexorcloud.module` | Wiring for a first-party `cloud-modules/*` (manifest, shadow jar, signing hooks) |
| `prexorcloud.plugin-paper` | Paper 1.20 plugin classpath + shadow jar |
| `prexorcloud.plugin-paper-1-21` | Paper 1.21 plugin classpath + shadow jar |
| `prexorcloud.plugin-paper-paperweight-1-20` | Paperweight (NMS-aware) 1.20 plugin |
| `prexorcloud.plugin-paper-paperweight-1-21` | Paperweight (NMS-aware) 1.21 plugin |
| `prexorcloud.plugin-folia` | Folia plugin |
| `prexorcloud.plugin-spigot` | Spigot plugin |
| `prexorcloud.plugin-velocity` | Velocity proxy plugin (allows `@Inject` per ADR 5) |
| `prexorcloud.plugin-bungeecord` | BungeeCord proxy plugin |
| `prexorcloud.plugin-bedrock-geyser` | Bedrock/Geyser variant |

If you add a new subproject and aren't sure which to apply, copy the closest existing one in `settings.gradle.kts` and look at what `build.gradle.kts` declares.

## Links

- [java/](../README.md) — module hierarchy and build order
- [ADR 5: gRPC mTLS + plugin DI](../../docs/engineering/decisions.md)
- [Contributing](../../CONTRIBUTING.md)

## License

Apache 2.0 — see [LICENSE](../../LICENSE).
