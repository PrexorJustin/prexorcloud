# License Posture

PrexorCloud is self-hosted software an operator runs on their own
infrastructure. Most of the licensing decisions an operator inherits come
from the data and coordination stores they pick — Mongo, Redis or Valkey —
not from the platform code. This page is the source of truth for what those
choices imply for a typical operator.

This is operator-facing documentation, not legal advice.

## 1. PrexorCloud itself

| Component | License | Notes |
| --- | --- | --- |
| `java/` (controller, daemon, common, security, api, protocol, modules, plugins) | See [`LICENSE`](../../LICENSE) | Project license; applies to the Java tree, the CLI, the dashboard, and all docs. |
| `cli/` (`prexorctl`) | Same as above | Single-license repo. |
| `dashboard/` (Nuxt + Vue) | Same as above | Includes `@prexorcloud/module-sdk` published from `dashboard/packages/module-sdk/`. |

If we ever vendor third-party code into the tree, it goes under
`third_party/<name>/` with its upstream license file preserved.

## 2. Datastores (the load-bearing licensing decision)

PrexorCloud requires **MongoDB** for durable state and a **Redis-protocol**
coordination store. The platform never embeds either; the operator runs
them.

### MongoDB — SSPL

- License: **Server Side Public License v1 (SSPL)**.
- What this means for an operator: SSPL only triggers obligations if the
  operator runs MongoDB **as a service offered to third parties** (a hosted
  Mongo). PrexorCloud's intended deployment is a single-operator install
  driving a Minecraft network — this is not a "service" of MongoDB itself,
  and using MongoDB as a backing store is unaffected.
- What this means if you redistribute PrexorCloud as a SaaS: you ship and
  expose MongoDB behind it; SSPL §13 starts to matter. Read the SSPL or
  consult counsel.
- Alternative: MongoDB-compatible drop-ins (e.g. FerretDB on PostgreSQL) are
  **not** validated against PrexorCloud's driver usage. Contributions to
  certify a compatible alternative are welcome.

### Redis — BSL/SSPL since 7.4 (2024)

- License: **Redis Source Available License v2 (RSALv2) and SSPLv1 (dual)**
  for Redis 7.4 and later. Redis 7.2.x and earlier remain **BSD-3-Clause**.
- The reference Compose stack defaults to Valkey, so this licensing
  question does not arise for the default install. Operators who deliberately
  swap in Redis 7.4+ inherit the RSALv2 / SSPL terms; operators who stick
  with Redis 7.2.x or earlier are on BSD-3 unchanged.
- RSALv2 forbids running Redis as a competing managed-database service. For
  operating PrexorCloud, this is not a concern.

### Valkey — BSD-3 (the recommended default)

- License: **BSD-3-Clause** (the Linux Foundation fork of Redis 7.2).
- The recommended coordination store. The Compose stack defaults to Valkey;
  the wire protocol is identical, so `redis://` URIs continue to work for
  operators who prefer to stay on Redis.

## 3. Java runtime dependencies

Sourced from `java/gradle/libs.versions.toml`. Versions move; licenses do
not. Verified against upstream POMs / project sites on the doc date.

| Dependency | License |
| --- | --- |
| Javalin | Apache-2.0 |
| gRPC-Java, gRPC-netty-shaded, gRPC-protobuf, gRPC-services, gRPC-stub | Apache-2.0 |
| Protobuf-java | BSD-3-Clause |
| Jackson (core, databind, dataformats, datatypes, modules) | Apache-2.0 |
| MongoDB Java Driver (`mongodb-driver-sync`) | Apache-2.0 |
| Lettuce (Redis-protocol client) | Apache-2.0 (with MIT for some test deps) |
| JJWT (api / impl / jackson) | Apache-2.0 |
| BouncyCastle (`bcprov-jdk18on`, `bcpkix-jdk18on`) | MIT-style (BC license, OSI-approved) |
| Logback (classic) | EPL-1.0 / LGPL-2.1 (dual) |
| SLF4J | MIT |
| Argon2-jvm | LGPL-3.0 (binding around the BSD-3 reference C implementation) |
| Micrometer (core, prometheus registry) | Apache-2.0 |
| Adventure (api, text-serializer-legacy) | MIT |
| OSHI (`oshi-core-java25`) | MIT |
| Apache Commons Compress | Apache-2.0 |
| JavaPoet | Apache-2.0 |
| JUnit 5 | EPL-2.0 |
| Mockito | MIT |

LGPL note: Logback and `argon2-jvm` are LGPL. PrexorCloud uses both as
unmodified library dependencies; the LGPL's library-use clause is satisfied
by leaving the classes loadable at runtime, which is the default for both
the fat-jar and Compose distributions. Operators who fork either dependency
inherit standard LGPL obligations for that fork.

### Plugin-target APIs (compiled-against, not redistributed)

The Bukkit / Paper / BungeeCord / Velocity APIs we compile plugins against
are not bundled into PrexorCloud artifacts; the host server ships its own
copy. License of the plugin target (e.g. GPL-3 for Bukkit/Paper API) does
not transitively cover PrexorCloud's controller or daemon.

## 4. CLI (`prexorctl`) Go dependencies

Tracked under `cli/go.mod`. The CLI's runtime tree is small (Cobra, Viper,
golang.org/x packages, gRPC-Go, Protobuf-Go); all of these are Apache-2.0,
BSD-3-Clause, or MIT. SBOM generation under §5 is the source of truth — do
not hand-maintain a per-dep table here.

## 5. SBOM and CVE scanning

CI runs both on every push to `main` and pull request:

- **CVE scanning** — Trivy (`aquasecurity/trivy-action`) over the repo
  filesystem, the Java fat-jars produced by `:cloud-controller:shadowJar`
  and `:cloud-daemon:shadowJar`, and the `prexorctl` binary. Findings of
  HIGH / CRITICAL severity fail the job.
- **SBOM** — Syft (`anchore/sbom-action`) emits CycloneDX JSON for the
  Java aggregate, the CLI binary, and the dashboard build. Artifacts are
  attached to each CI run with 30-day retention.

The CI surfaces are defined in `.github/workflows/ci.yml`. Pull-request
authors should expect new HIGH/CRITICAL findings to block merge; the fix is
either an upgrade or, if upstream has not released, a documented suppression
in `.trivyignore` with an expiry date.

Tagged `prexorctl` releases (`v*`) ship signed under
`.github/workflows/release.yml`: GoReleaser (`cli/.goreleaser.yaml`)
produces a 6-target archive matrix with a CycloneDX SBOM per archive, and
cosign signs `checksums.txt` with keyless OIDC (Fulcio/Rekor, GitHub
Actions identity). `cosign verify-blob` against `checksums.txt.sig` +
`checksums.txt.pem` transitively covers every archive listed in the
checksum file. Container image signing for the controller / daemon /
dashboard images runs under `.github/workflows/release-images.yml` —
multi-arch images are pushed to GHCR with cosign keyless signatures by
digest and a self-verify step.

## 6. Reference

- MongoDB SSPL: <https://www.mongodb.com/licensing/server-side-public-license>
- RSALv2: <https://redis.io/legal/rsalv2-agreement/>
- Valkey project: <https://valkey.io>
- SPDX license list: <https://spdx.org/licenses/>
