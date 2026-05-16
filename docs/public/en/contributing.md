---
title: Contributing
description: Development setup, tech stack, and contribution guidelines for PrexorCloud.
---

PrexorCloud is an Apache 2.0-licensed open-source project and we welcome
contributions — bug fixes, recipe additions, documentation improvements,
and well-scoped features. This page covers what you need installed, how
the codebase is laid out, the conventions we enforce, and the workflow we
use to land a change.

If you are evaluating PrexorCloud as an operator rather than a
contributor, the [getting started guide](/getting-started/what-is-prexorcloud/)
is the right entry point instead.

## What you'll need

PrexorCloud is a polyglot monorepo. To build the whole thing locally you
need:

- **Java 25 (preview)** — controller, daemon, common, protocol, and
  security modules build with `--enable-preview` against Java 25.
- **Java 21** — the public API module (`cloud-api`) targets Java 21
  without preview features so plugin-side modules can consume it
  without unlocking preview flags.
- **Java 17** — the in-server plugin code targets 17 because that is
  the floor Minecraft servers run on.
- **Node.js 22+** with **pnpm** (corepack-managed). Used by the
  dashboard, the documentation site, and the module / dashboard SDK
  packages.
- **Go 1.24+** — used by the `prexorctl` CLI.
- **Docker** — required for the integration test harness, the
  reference Compose stack, and (optionally) for a local MongoDB +
  Valkey when iterating against `production` profile.
- **`buf`** — protobuf tooling for `cloud-protocol`. The repository
  uses `buf generate` to regenerate gRPC artefacts when proto files
  change.
- **`cosign`** — only required if you want to sign module bundles
  locally. CI signs all release artefacts.

A single Gradle wrapper handles every Java target — you do not need to
juggle JDK versions, only have at least one of each major version
available. Toolchains do the rest.

## Tech stack

The product is a Java 25 controller (REST + gRPC + scheduler + module
manager + SSE bus), a Java 25 daemon per host that supervises Minecraft
processes, a Nuxt 4 / Vue 3 dashboard, a Go CLI, and a layered set of
in-server / in-proxy plugins compiled against the public Java 21 API.
The two backing stores are MongoDB (durable state) and Valkey (or any
Redis-protocol-compatible store, for coordination).

The exhaustive list of cross-cutting choices lives in the
architectural decisions register.
The short version of what you should know before you start hacking:

- **Constructor injection only.** No Spring, no Guice, no Dagger.
  `PrexorCloudBootstrap` is the sole composition root and every
  dependency is wired by hand. If you find yourself reaching for
  `@Autowired`, you are on the wrong path
  (ADR 5).
- **Jackson only** for serialisation. No Gson, no manual JSON
  construction.
- **SLF4J only** for logging in the controller and daemon. No
  `System.out.println`. Plugin-side code uses
  `java.util.logging.Logger` because shading SLF4J into every plugin
  jar is out of scope.
- **No ORM.** MongoDB driver direct, with `Document`-based CRUD or
  the module-facing `ModuleDataStore`. No Hibernate, no Morphia, no
  Spring Data.
- **Records with compact constructors** for configuration. Compact
  constructors apply defaults; there are no `Builder` /
  `BuilderFactory` companions (ADR 17).
- **Mock only external boundaries** in tests — database, network,
  filesystem. Internal services run real, end-to-end. The
  `cloud-test-harness` subproject boots a real controller + daemon
  and exercises the full stack.

The conventions guide covers
naming, error handling, REST patterns, concurrency, and frontend
component organisation in more depth. It is the right reference when a
PR review asks "why does our project do X like that."

## Repository layout

```
prexorcloud/
├── java/                                 # Gradle multi-project
│   ├── cloud-api/                        # public types (Java 21)
│   ├── cloud-common/                     # cross-cutting infra (Java 21)
│   ├── cloud-platform/                   # Gradle java-platform / BOM
│   ├── cloud-protocol/                   # generated gRPC + protobuf
│   ├── cloud-security/                   # JWT, CA, mTLS, signing
│   ├── cloud-controller/                 # the controller JVM
│   ├── cloud-daemon/                     # the per-host daemon JVM
│   ├── cloud-modules:runtime/               # host-agnostic module runtime
│   ├── cloud-module/                     # first-party platform modules
│   │   ├── cloud-module-stats-aggregator/
│   │   ├── cloud-module-player-journey/
│   │   ├── cloud-module-webhook-alerts/
│   │   ├── cloud-module-tablist/
│   │   ├── cloud-module-protocol-tap/
│   │   ├── cloud-module-test-daemon/
│   │   └── cloud-module-example/
│   ├── cloud-plugin/                     # standalone @CloudPlugin jars
│   ├── cloud-plugins/                    # platform integration code
│   │   ├── cloud-plugins-server-paper/
│   │   ├── cloud-plugins-server-spigot/
│   │   ├── cloud-plugins-server-folia/
│   │   ├── cloud-plugins-proxy-velocity/
│   │   ├── cloud-plugins-proxy-bungee/
│   │   ├── cloud-plugins-server-shared/
│   │   ├── cloud-plugins-proxy-shared/
│   │   └── cloud-plugins-internal/
│   └── cloud-test-harness/               # integration tests
├── dashboard/                            # Nuxt 4 SPA
│   ├── app/
│   └── packages/module-sdk/              # @prexorcloud/module-sdk on npm
├── cli/                                  # prexorctl (Go + Cobra)
├── website/                              # Astro Starlight (this site)
├── docs/                                 # documentation
│   ├── public/en/                        # source-of-truth for the website
│   ├── runbooks/                         # operator runbooks
│   ├── security/                         # threat model, licence registry
│   └── openapi.json                      # REST API spec (generated by `./gradlew :cloud-controller:syncOpenApi`)
├── deploy/compose/                       # reference Compose stack
├── java/cloud-protocol/                  # gRPC service definitions (Protobuf)
└── .github/workflows/                    # CI pipelines
```

Cross-module classpath exposure between platform modules is forbidden.
Modules link through capability handles, never through shared internal
types. PRs that try to introduce a shared "internals" module will be
sent back.

## First build

Clone the repository and build everything in one go:

```bash
git clone https://github.com/prexorjustin/prexorcloud.git
cd prexorcloud

# Java side: controller, daemon, modules, plugins, test harness
cd java
./gradlew build

# Dashboard
cd ../dashboard
pnpm install
pnpm dev          # starts the dev server on :3000

# CLI
cd ../cli
go build -o prexorctl .

# Documentation site
cd ../website
pnpm install
pnpm dev          # starts the docs site on :4321
```

`./gradlew build` runs the full test suite plus formatting checks. The
first build is slow (Gradle dependency resolution + protobuf code
generation); incremental builds are fast.

To run a fully-local cluster end-to-end:

```bash
docker compose -f deploy/compose/compose.yml up -d
```

This brings up MongoDB, Valkey, the controller, and one daemon. The
controller logs the initial admin password on first start; pull it from
the logs and use it with `prexorctl login`.

## Running checks before pushing

Three commands cover everything CI runs:

```bash
# Java: formatting + full test suite
cd java && ./gradlew spotlessCheck build

# Dashboard: lint + tests
cd dashboard && pnpm lint && pnpm test

# CLI: vet + tests
cd cli && go vet ./... && go test ./...
```

If you only changed one surface, run only the matching block. CI will
run all three on the PR.

Format Java sources with `./gradlew spotlessApply` if `spotlessCheck`
fails. Spotless uses the Eclipse formatter configured in
`spotless/eclipse-formatter.xml`.

## Where to start a change

Pick the smallest entry point that fits your contribution:

- **Bug fix in the controller or daemon** — open the failing case as
  an integration test in `cloud-test-harness`, then fix the
  underlying class. The harness boots a real controller + daemon, so
  the test is the spec.
- **New first-party module** — `prexorctl module new <name>`
  scaffolds the build file, the `module.yaml`, and a starter
  `PlatformModule` implementation. Look at
  [`cloud-module-stats-aggregator`](https://github.com/prexorjustin/prexorcloud/tree/main/java/cloud-modules/stats-aggregator)
  as the reference for routes + capabilities + storage + frontend.
- **New `@CloudPlugin` jar** — `prexorctl plugin new <name>
  --platform=paper|velocity|...` scaffolds a standalone plugin. See
  [plugin vs. module](/concepts/plugins/) for which path you
  want.
- **Dashboard feature** — work in `dashboard/app/`. Composables under
  `composables/` are the integration points to the controller; pages
  live in `pages/`. Pinia stores in `stores/` consume SSE events for
  reactive updates.
- **CLI command** — work in `cli/cmd/`. Each subcommand is its own
  file; the API client lives under `cli/internal/api/`. Cobra is the
  command framework.
- **Documentation** — every public docs page lives under
  `docs/public/en/` and is rendered by the Astro Starlight project in
  `website/`. The "Edit this page" link on every doc page points at
  the right file.
- **Recipe** — recipes live in `docs/public/en/recipes/`. Each recipe
  is a self-contained "do this, get that" guide; the structure is
  enforced informally (what you'll build → prerequisites →
  step-by-step → verification → next steps).

## PR conventions

We do not enforce conventional-commits, but the commit messages and PR
titles in the existing history follow a consistent shape — short, imperative,
scoped to the surface (`controller: …`, `dashboard: …`, `cli: …`,
`docs: …`). Mirror what you see.

For non-trivial changes:

1. Open an issue first, especially if the change touches the public API
   (REST, gRPC, the module SDK, the plugin SDK). v1 is a stability
   commitment — additive changes are fine; breaking changes need a
   discussion.
2. Keep the PR scoped. A 30-file PR that fixes one thing and
   incidentally renames three classes will get split.
3. Update the relevant docs in the same PR. If you add a config key,
   it has to land in the configuration reference. If you add a CLI
   subcommand, the help text and the docs both have to update. Drift
   here is the most common review comment.
4. If you change the gRPC contract, update
   `java/cloud-protocol/contracts/proto-contracts.sha256` and avoid bumping
   `PROTOCOL_VERSION` for purely additive `oneof` variants.
5. If you add a new module configuration key, follow the compact-record
   convention so defaults stay close to the field
   (ADR 17).
6. Tests: integration tests for behaviour, unit tests for pure logic,
   no mocking of internal services
   (conventions §Testing).

CI runs spotless, the full Java test suite, the dashboard lint + test
suite, the CLI vet + tests, and the documentation build. PRs need a
green run before merge; flaky tests should be reported on the issue
tracker, not silently retried.

## Reporting bugs and security issues

- **Functional bugs** — open a GitHub issue with the controller version
  (`prexorctl version`), Java runtime, MongoDB and Valkey versions, and
  the smallest reproduction you can produce.
- **Security issues** — do *not* open a public issue. Email the
  maintainer (see the security policy in the repository) and we will
  respond before discussing publicly.

## What we will not merge

To keep the project legible and the maintenance burden bounded, some
classes of change are out of scope by policy. The full list lives in
the architectural decisions register;
the high-impact ones for contributors are:

- A DI framework, an ORM, or per-instance container isolation
  (ADR 5,
  ADR 7).
- OpenTelemetry, distributed tracing, or a custom signing scheme that
  is not Cosign
  (ADR 9,
  ADR 15).
- A bundled Grafana dashboard pack
  (ADR 10).
- A WASM module runtime, an OIDC / SAML / SSO path, or a hosted
  PrexorCloud control plane
  (ADR 1,
  ADR 8,
  ADR 13).
- A built-in module marketplace
  (ADR 14).
- An LLM "chat with your cluster" feature
  (ADR 21).

If your idea sits in one of these spaces and you have a strong reason
to revisit the decision, open an issue and make the case — the ADRs are
not tablets from a mountain, they reflect deliberate trade-offs that
can be re-examined when the trade-off changes.

## License

By contributing to PrexorCloud you agree that your contribution is
licensed under the Apache License 2.0. There is no separate CLA. See
[`LICENSE`](https://github.com/prexorjustin/prexorcloud/blob/main/LICENSE)
for the full text.
