---
title: Contributing
description: Development setup, tech stack, and contribution guidelines for PrexorCloud.
---

This page gets you from a fresh clone to a green local build, then points you
at the right entry for the change you want to make. PrexorCloud is an Apache
2.0, single-maintainer project; bug fixes, recipe additions, documentation
improvements, and well-scoped features are welcome.

Evaluating PrexorCloud as an operator rather than hacking on it? Start at
[What is PrexorCloud](/getting-started/what-is-prexorcloud/) instead.

The repository root [`CONTRIBUTING.md`](https://github.com/prexorjustin/prexorcloud/blob/main/CONTRIBUTING.md)
is the canonical policy. This page mirrors the practical workflow and links
back to it for anything contractual (CLA, conduct, disclosure).

## What you'll need

PrexorCloud is a polyglot monorepo. To build the whole thing locally:

- **JDK 21 and JDK 25.** The Gradle wrapper drives every JVM target through
  toolchains, so the Gradle JVM only needs to be 21 or newer — but Spotless
  runs the Palantir formatter on JDK 25, so `spotlessCheck` and
  `spotlessApply` need a JDK 25 available. Controller, daemon, and modules
  build on Java 25 with `--enable-preview`; `cloud-api` and the in-server
  plugins target Java 21 to match Minecraft host runtimes (ADR 25).
- **Node.js 22+ with `pnpm`** (corepack-managed) — dashboard, website, and the
  SDK packages. Node 22+ matters: the doc generators use built-in TypeScript
  stripping.
- **Go 1.24+** — the `prexorctl` CLI.
- **Docker** — the reference Compose stack and the integration test harness.
- **`buf`** (optional) — protobuf tooling for `cloud-protocol`.
- **`cosign`** (optional) — only to sign module bundles locally; CI signs all
  release artifacts.

## How the repo is laid out

```
prexorcloud/
├── java/                       # Gradle multi-project (Kotlin DSL)
│   ├── cloud-api/                  # public SPI (Java 21)
│   ├── cloud-common/               # cross-cutting infra (Java 21)
│   ├── cloud-platform/             # java-platform / BOM
│   ├── cloud-protocol/             # generated gRPC + protobuf
│   ├── cloud-security/             # JWT, CA, mTLS, cosign verification
│   ├── cloud-controller/           # the controller JVM (Java 25 preview)
│   ├── cloud-daemon/               # the per-host daemon JVM (Java 25 preview)
│   ├── cloud-modules/              # first-party modules: runtime, example,
│   │                               #   stats-aggregator, player-journey,
│   │                               #   webhook-alerts, tablist, protocol-tap,
│   │                               #   backup-orchestrator, discord-bridge
│   ├── cloud-plugins/              # MC-side integrations
│   │   ├── proxy/{velocity,bungeecord,geyser,shared}/
│   │   ├── server/{paper,spigot,folia,fabric,neoforge,shared}/
│   │   └── internal/
│   ├── test-fixtures/              # shared test infra + test-daemon-module
│   ├── cloud-test-harness/         # integration tests (real Mongo/Redis)
│   └── build-logic/                # Gradle convention plugins
├── dashboard/                  # Nuxt 4 SPA + packages/ (api-sdk, module-sdk)
├── cli/                        # prexorctl (Go + Cobra)
├── website/                    # Astro Starlight (renders docs/public/en/)
├── installer/                  # browser setup wizard
├── design-system/              # canonical tokens (CI-guarded mirror)
├── docs/                       # docs/public/en/ is the site source of truth
├── contracts/                  # proto contract snapshots
├── deploy/                     # reference Compose + systemd
├── infra/perf/                 # committed perf baselines
├── tools/, scripts/            # generators and CI helpers
└── .github/workflows/          # CI pipelines
```

Modules link to each other through capability handles, never through shared
internal types — there is no shared "internals" module, and a PR that adds one
gets sent back (ADR 12).

Note: `java/cloud-plugins/` also carries a legacy `cloud-plugins-*` tree that
is **not** in the build. Edit the short-named directories (`proxy/`, `server/`,
`internal/`), not the `cloud-plugins-*` ones.

## Build it

```bash
git clone https://github.com/prexorjustin/prexorcloud.git
cd prexorcloud

# Java: controller, daemon, modules, plugins, test harness
cd java && ./gradlew build

# Dashboard
cd ../dashboard && pnpm install && pnpm dev      # :3000

# CLI
cd ../cli && go build -o prexorctl .

# Docs site
cd ../website && pnpm install && pnpm dev        # :4321
```

The first Java build is slow (dependency resolution + protobuf codegen);
incremental builds are fast. For a full local cluster:

```bash
docker compose -f deploy/compose/compose.yml up -d
```

The controller logs the initial admin password on first start; pull it from the
logs and use it with `prexorctl login`.

## Run the checks CI runs

Three commands cover the core surfaces:

```bash
# Java: formatting (JDK 25) + full test suite
cd java && ./gradlew spotlessCheck build

# Dashboard: lint + tests
cd dashboard && pnpm lint && pnpm test

# CLI: vet + tests
cd cli && go vet ./... && go test ./...
```

If `spotlessCheck` fails, run `./gradlew spotlessApply` (Palantir formatter,
JDK 25). For the frontend surfaces:

```bash
cd installer && pnpm format:check && pnpm typecheck && pnpm test
cd website   && pnpm format:check && pnpm check && pnpm check:links && pnpm build
```

Two gates worth knowing about:

- **`pnpm build` runs `sdk:check` first.** The dashboard `build` script is
  `pnpm sdk:check && nuxt build`, where `sdk:check` lints `docs/openapi.json`
  and regenerates the typed API SDK. If you changed a REST handler, regenerate
  the OpenAPI spec (`./gradlew :cloud-controller:syncOpenApi`) so the SDK stays
  in lockstep.
- **`pnpm lint:voice`** enforces sentence-case headings and the no-emoji rule on
  dashboard and docs prose. Apply the same voice to anything you write under
  `docs/public/en/`.

`@Tag("spike")` and `@Tag("perf")` tests are excluded from the default CI pass —
spikes never run in CI, and perf baselines run nightly (see
[Performance benchmarks](/benchmarks/)). The repo ships an optional
[`lefthook.yml`](https://github.com/prexorjustin/prexorcloud/blob/main/lefthook.yml)
pre-commit hook that runs Spotless and Prettier on staged files; CI runs the
same gates regardless.

## Conventions that matter

These hold across the codebase; the
[architecture decisions register](https://github.com/prexorjustin/prexorcloud/blob/main/docs/engineering/decisions.md)
records the why.

- **Constructor injection only.** No Spring, Guice, or Dagger.
  `PrexorCloudBootstrap` is the sole composition root (ADR 5). The one sealed
  exception is the Velocity plugin, where the host framework injects.
- **Jackson only** for JSON. **SLF4J only** for logging in the controller and
  daemon (no `System.out.println`).
- **No ORM.** MongoDB driver direct, or the module-facing `ModuleDataStore`.
- **Configuration is Java records with compact constructors** that apply
  defaults — no `Builder` companions (ADR 17). Adding a field touches every
  positional constructor site.
- **Mock only external boundaries** in tests (database, network, filesystem).
  Internal services run real; `cloud-test-harness` boots a real controller +
  daemon, so an integration test is the spec.

## Where to start a change

Pick the smallest entry point that fits:

- **Bug in the controller or daemon** — write the failing case as an integration
  test in `cloud-test-harness`, then fix the class.
- **New first-party module** — `prexorctl module new <name>` (or
  `scripts/new-module.mjs`) scaffolds the build file, `module.yaml`, and a
  starter `PlatformModule`. `stats-aggregator` is the reference for routes,
  capabilities, storage, and frontend.
- **New `@CloudPlugin` jar** — `prexorctl plugin new <name>
  --platform=paper|velocity|...`. See [Plugins](/concepts/plugins/) for which
  path you want.
- **Dashboard feature** — work in `dashboard/app/`. Composables are the
  controller integration points; Pinia stores consume SSE for reactivity.
- **CLI command** — work in `cli/cmd/`; the API client lives under
  `cli/internal/api/`.
- **Documentation or a recipe** — everything under `docs/public/en/` is the
  source of truth for the site; recipes live in `docs/public/en/recipes/`.

## Changing the gRPC contract

If you touch `cloud-protocol`, update the committed snapshot at
`java/cloud-protocol/contracts/proto-contracts.sha256` — `ProtoContractDriftTest`
fails the build otherwise. Do **not** bump `PROTOCOL_VERSION` for purely
additive `oneof` variants or scalar fields; older peers ignore them and still
handshake. A protocol-version bump is a breaking change and a coordinated
upgrade, called out in the [changelog](/changelog/).

## Opening a PR

- Branch from `main`, push your branch, open the PR against `main`.
- Use conventional-commit titles (`feat:`, `fix:`, `docs:`, `refactor:`,
  `chore:`, `test:`, `ci:`), scoped to the surface you touched.
- Keep it scoped — one feature or fix per PR. A change that fixes one thing and
  incidentally renames three classes gets split.
- Update the docs in the same PR. New config key → configuration reference. New
  CLI subcommand → help text and docs. Drift here is the most common review
  comment.
- Tests are mandatory for non-trivial changes — integration tests for behaviour,
  unit tests for pure logic, no mocking of internal services.

Some classes of change are out of scope by deliberate decision (a DI framework,
an ORM, per-instance container isolation, OIDC/SSO, a hosted control plane). If
you want to revisit one, open an issue and make the case against the relevant
ADR — they reflect trade-offs, not commandments.

## Reporting bugs and security issues

- **Functional bugs** — open a GitHub issue with the controller version
  (`prexorctl version`), Java runtime, MongoDB and Valkey versions, and the
  smallest reproduction you can produce.
- **Security issues** — do not open a public issue. Follow
  [`SECURITY.md`](https://github.com/prexorjustin/prexorcloud/blob/main/SECURITY.md)
  for the private disclosure channel.

## License and conduct

By contributing you agree your work is licensed under the
[Apache License 2.0](https://github.com/prexorjustin/prexorcloud/blob/main/LICENSE);
there is no separate CLA. The project follows the
[Contributor Covenant](https://github.com/prexorjustin/prexorcloud/blob/main/CODE_OF_CONDUCT.md).
</content>
