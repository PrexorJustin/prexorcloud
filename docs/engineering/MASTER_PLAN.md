# PrexorCloud Master Plan

This is the working master plan for bringing PrexorCloud to "undeniable" quality:
production-grade brand anchor, module system polished to perfection, all
platforms covered including Bedrock, while cutting features that bloat
maintenance for a solo project. **No architectural pivot. Discipline > novelty.**

> **Status (2026-05-14): all phases complete.** Phases 1–3 done; Phase 4 done
> — 4.1 superseded by `WEBSITE_PLAN.md` (engineering-complete), 4.2/4.3/4.4/4.5
> shipped, 4.6 descoped per ADR 22. No open code tracks remain across any
> plan. Remaining work is verification / launch-ops that needs a live cluster
> or production accounts — see [`LIVE_CLUSTER_GUIDE.md`](./LIVE_CLUSTER_GUIDE.md).

> Living document. Revisit before starting any new feature: does it support the
> brand anchor? If not, cut or defer.

---

## Context

PrexorCloud has shipped v1 (2026-05-05). The codebase is technically deep:
strong module system (cosign+Rekor signed install, classloader isolation,
capability registry with semver), comprehensive plugin SDK
(`@CloudPlugin` annotation processor generating platform bridges,
`@ForVersion` multi-version dispatch, multi-platform extension artifacts in
one bundle), production-grade ops infrastructure (DR drill, perf baselines,
TLS rotation), and a modern Vue 3 dashboard.

A 3-agent audit (`module system deep dive`, `plugin SDK + multi-version
dive`, `project structure + features audit`) plus direct reading of the
key files (`PlatformModule.java`, `CloudPluginProcessor.java`,
`VersionDispatcher.java`, the `cloud-module-example` reference module
including its `module.yaml`, `build.gradle.kts`, `ExamplePlatformModule`,
and the four plugin variants) revealed the project's situation:

- **Architecture is sound.** No pivot needed.
- **Plugin/module-system has concrete gaps**: no Bedrock/Geyser support,
  no shared proxy-plugin base (Velocity + BungeeCord duplicate ~100 LoC),
  `VersionDispatcher` works only on Bukkit (not on proxies), Folia
  diverges from `AbstractCloudPlugin` because of threading model,
  no REST body-parsing helpers (every route does manual try/catch),
  no module integration-test-harness, no CLI preflight for capability
  requirements.
- **Cruft is real.** `Adventure` dep declared but unused. `VersionAdapterRegistry`
  documented but dormant — JAR-split + `@ForVersion` cover its use cases.
  `PlayerJourneyService` (1 class) and `WebhookAlertService` (~150 LoC)
  are niche features bloating the controller.
- **Brand identity is unclear.** The cloud's strongest objective
  differentiators (signed installs, tested DR, perf-baselines-as-CI-gates,
  TLS-rotation-without-restart) are not surfaced anywhere.

**Intended outcome**: bring PrexorCloud's existing strengths to "undeniable"
quality (production-grade brand anchor, module system polished to perfection,
all platforms covered including Bedrock), while cutting features that bloat
maintenance for a solo project. No architectural pivot. No new big features
beyond Bedrock. Discipline > novelty.

---

## North-Star — Brand Anchor

> **PrexorCloud — Minecraft cloud orchestration, production-grade by default.**
>
> Groups, instances, templates, networks — orchestrated like infrastructure.
> Signed modules, tested disaster recovery, perf baselines as CI gates,
> TLS rotation without restart.

Every feature decision in this plan supports this anchor. Anything that
does not support it gets cut or deferred.

---

## Phase 1 — Cruft Cuts + Onboarding-Win (Day 1–40)

> Phase 1 is slightly extended (~40 days vs. the original 30) because it now
> includes the browser-setup-wizard. The wizard is the single biggest visible
> onboarding win and the first thing new adopters see — it earns its early
> slot. Everything else in Phase 1 stays as small/fast items.

### 1.1 Remove `adventure` dependency (5 min) — SUPERSEDED
- File: `java/gradle/libs.versions.toml:2` (version) and `:35-36` (libraries)
- ~~Remove `adventure` version + `adventure-api` + `adventure-text-serializer-legacy`~~
- Status: skipped — `adventure-text-serializer-legacy` is now an explicit
  dep of `cloud-plugins-proxy-bungeecord/build.gradle.kts` (used to build
  `BaseComponent[]` from chat strings). The original audit was outdated.
  `adventure-api` is not declared at all. No safe removal possible without
  breaking the BungeeCord proxy build.

### 1.2 Delete dormant `VersionAdapterRegistry` (15 min) — DONE
- File: `java/cloud-api/src/main/java/me/prexorjustin/prexorcloud/api/client/version/VersionAdapterRegistry.java`
- Audit confirmed: zero callers in repo. JAR-split (Paper v1_20/v1_21) and
  `@ForVersion` (Folia adapter pattern in `ExamplePlaytimeFoliaPlugin.V1_20`/`V1_21`)
  cover the documented use cases.
- After delete, run `./gradlew :cloud-api:compileJava` to confirm no breakage.

### 1.3 Move `PlayerJourneyService` into an optional module — DONE
- `java/cloud-module/cloud-module-player-journey/` ships with `PlayerJourneyModule`,
  `JourneyRecorder`, `MongoPlayerJourneyTracker`, and a `JourneyRepository`/`JourneyDoc`
  data layer. The capability `prexor.player.journey` is provided here now;
  `cloud-module-stats-aggregator`'s `JourneyEnricher` consumes it unchanged.
- `cloud-controller` no longer holds `PlayerJourneyService` — the previous
  in-controller wiring is gone. The earlier "blocked on PlatformModuleContext"
  status was stale: `ModuleContext.events()` already exposes the full
  `EventBus` (subscribe + publish), so no API extension was needed.
- Tests live in `cloud-module-player-journey/src/test/java/.../JourneyRecorderTest`.

### 1.4 Move `WebhookAlertService` into an optional module — DONE
- `java/cloud-module/cloud-module-webhook-alerts/` ships with `WebhookAlertsModule`
  subscribing via `ModuleContext.events()`. Controller no longer holds
  `WebhookAlertService` — extracted alongside 1.3 once the "missing API"
  concern turned out to be a stale assumption.

### 1.5 Catalog UI page collapse + Settings/Theme behind feature flag (3–4 hours) — DONE
- `dashboard/app/pages/catalog/index.vue` and `[platform].vue` → delete
- Inline catalog selector becomes an embedded component used in
  `dashboard/app/components/groups/CreateGroupDialog.vue` (already does
  platform/version selection — finish wiring it to the catalog API)
- `dashboard/app/pages/settings/*` → guard with `runtimeConfig.public.experimentalThemeCustomization`,
  default `false`. UI hides settings link from `dashboard/app/lib/navigation.ts`
  when flag is false.

### 1.6 CVE-Scan + SBOM → nightly only (2 lines) — DONE
- `.github/workflows/ci.yml`: remove `cve-scan` and `sbom` jobs from PR trigger
- `.github/workflows/nightly.yml`: add same jobs there
- PR feedback loop: ~50 min → ~30 min.

### 1.7 Brand anchor on landing page + repo README — DONE
- `website/content/index.md` (or wherever the landing-page hero text lives):
  swap headline to the production-grade anchor above
- `README.md` (root): same headline, plus 1-paragraph intro citing
  signed-modules, DR drill, perf baselines, TLS rotation as proof points

### 1.8 Browser-based setup wizard + one-line install (~1–2 weeks, biggest single win in Phase 1) — DONE
> Implementation note: shipped `prexorctl setup --browser` with two operating
> modes — loopback (default, plain HTTP, no auth) and `--public` (token-authed
> HTTPS exposed to the network, for remote-VPS bootstrap). Wizard front-end is
> a self-contained HTML/JS single-page app embedded via `embed.FS` — no Nuxt
> build dependency.
>
> **Loopback mode** (`prexorctl setup --browser`):
> - Binds 127.0.0.1:9100, no token, plain HTTP
> - Right answer for desktop / local-VM installs
> - Recommended remote-VPS pattern: SSH port-forward
>   (`ssh -L 9100:127.0.0.1:9100 user@host`)
>
> **Public mode** (`prexorctl setup --browser --public [--public-host=<dns-or-ip>]`):
> - Binds 0.0.0.0:9100, generates ephemeral self-signed ECDSA cert (24 h validity)
> - Generates a 32-byte cryptographic token via `crypto/rand`, prints it in the
>   URL fragment (`#token=…`) so it never hits server logs / Referer headers
> - JS reads `window.location.hash`, sends the token as `Authorization: Bearer`
>   on every `/api/*` call (constant-time comparison via `subtle.ConstantTimeCompare`)
> - Rate limit: 10 failed token attempts per remote IP → 60s lockout
> - Single-use: wizard auto-exits 60s after a successful install
> - Idle shutdown: defaults to 30 min (`--browser-idle-timeout` overrides)
> - Loud firewall + cert-fingerprint warning printed before binding
> - Refuses to bind a non-loopback address without `--public` (and refuses to
>   bind loopback *with* `--public`)
>
> **Component coverage:**
> - Controller compose install: writes config + docker-compose.yml, downloads
>   jar from the release pipeline, reuses `internal/setup` helpers (no
>   logic duplication)
> - Daemon compose install: same shape, with `nodeId` + `joinToken` validation
>
> **One-liner:**
> - `curl -fsSL https://get.scharbau.me/cli | bash` installs prexorctl AND
>   launches the wizard by default (auto-setup is the default after switching
>   away from the opt-in env var pattern)
> - `--no-setup` (or `PREXORCTL_AUTO_SETUP=0`) opts out for "binary only"
>
> **Browser auto-launch:** honors `$BROWSER` first, then falls back to
> `xdg-open` / `wslview` / `firefox` / `google-chrome` / `chromium` / `brave`.
> Hyprland + minimal Wayland hosts work as long as `$BROWSER` is set or any
> of the named browsers is on PATH.
>
> Deferred to Phase 4.2 (onboarding polish):
> - Native-mode install via the browser (requires interactive package-manager
>   prompts and elevated privileges; today the wizard returns a 400 with a
>   "run prexorctl setup in a terminal" message for native mode)
> - Visual design pass moving from plain HTML to dashboard-consistent styling
> - Server-Sent-Events progress stream (current returns the full install log
>   as a single JSON envelope when the install completes)
> - SSH-aware messaging in loopback mode (detect `$SSH_CONNECTION`, suggest
>   the port-forward command for that specific host)
- One-line install: ship a `get.prexor.cloud/install.sh` (or the static-asset
  host you already use per the in-flight `release.yml`) that downloads
  `prexorctl` for the right OS/arch and execs `prexorctl setup --browser`.
- New CLI command: `prexorctl setup --browser` extends the existing flows in
  `cli/cmd/setup.go` (currently CLI/huh-only). It binds a local HTTP server
  on `127.0.0.1:9100` (configurable), opens the user's browser, and serves
  a small Nuxt-rendered (or pure-HTML) wizard that walks the user through:
  - install mode (native vs compose)
  - controller config defaults (admin user, TLS, ports)
  - daemon enrolment (or "no daemons yet, I just want a controller")
  - service registration (systemd) + startup validation prompts
- Reuse: the existing `setup_controller.go`, `setup_daemon.go`,
  `setup_native.go`, `setup_compose.go`, `setup_systemd.go`, `setup_helpers.go`
  (already split per the prior cleanup). The wizard is a thin presentation
  layer that calls the existing setup substages. Do NOT duplicate logic —
  the browser is just an alternative front-end.
- Front-end of the wizard: keep simple. Either embed plain HTML+JS as Go
  static-assets (no Nuxt build dependency for the bootstrap path), or, if
  reusing dashboard components, ship a tiny Nuxt static export inside the
  CLI binary via `embed.FS`. Lean toward plain HTML for the MVP — zero
  Nuxt-build dependency for first-run.
- Acceptance: a fresh user runs `curl get.prexor.cloud/install.sh | bash`,
  the script puts `prexorctl` on the path and runs `prexorctl setup --browser`,
  the user gets a working controller in <5 minutes without ever opening the
  CLI/huh TUI.

**Phase 1 acceptance**: `gradle build`, `go build && go test`, `pnpm typecheck`
all pass. `docs/MASTER_PLAN.md` exists. Landing-page anchor live. Browser-based
setup-wizard works on a fresh VM via one-line install.

---

## Phase 2 — Module-System Lücken schließen (Day 31–60)

### 2.1 `AbstractProxyCloudPlugin` shared base — DONE
- New: `cloud-plugins-proxy-shared/.../AbstractProxyCloudPlugin.java`
  owns controller-client + state-cache lifecycle, backend-server sync via
  `stateCache.addListener`, `pollPendingTransfers`, `pollPendingMessages`
  (gated by `supportsCrossProxyMessages()`), `reportProxyMetrics`.
- New: `VelocityCloudCore` (delegate held by the `@Plugin`-annotated
  `PrexorCloudVelocity`) — Velocity-side concrete implementation. Overrides
  `supportsCrossProxyMessages()`/`deliverMessage()` for cross-proxy chat.
- New: `BungeeCloudCore` (held by `PrexorCloudBungeeCord extends Plugin`) —
  BungeeCord-side concrete implementation; preserves the Bungee-only
  "skip self-targeted transfers" behavior in its `transferPlayer` hook.
- `PrexorCloudVelocity.java` → 27-line adapter; `PrexorCloudBungeeCord.java`
  → 14-line adapter.

### 2.2 `VersionDispatcher` on proxies — DONE
- File: `java/cloud-api/src/main/java/me/prexorjustin/prexorcloud/api/plugin/annotation/CloudPluginProcessor.java`
- In `generateVelocityBridge()` (line 290): inject
  `impl.initVersionDispatcher(new VersionDispatcher(server.getVersion().getVersion()))`
  after the impl construction.
- In `generateBungeeBridge()` (line 391): inject
  `impl.initVersionDispatcher(new VersionDispatcher(net.md_5.bungee.api.ProxyServer.getInstance().getVersion()))`.
- `CloudPluginBase.adapt()`: drop the proxy-throws guard
- Add a note in `docs/modules-multi-version.md` (new file) about Velocity 3.3
  vs 3.4 API drift as a real-world use case.

### 2.3 Folia threading divergence — make Folia a first-class `AbstractCloudPlugin` — DONE
> Implementation note: moved `TickCounter`, `BukkitMetricsCollector`,
> `BukkitServerCloudApi`, `BukkitCommandRegistry`, `BukkitPlayerListener`,
> `BukkitPluginContext`, `BukkitPluginScheduler`, `BukkitCommandSender`,
> `BukkitCloudClient`, and `AbstractCloudPlugin` to a new
> `me.prexorjustin.prexorcloud.server.shared.bukkit` package in
> `cloud-plugins-server-shared`. `BukkitServerCloudApi` now takes a
> `Function<JavaPlugin, PluginScheduler>` factory so Folia can inject
> `FoliaPluginScheduler` (region-thread-safe) while Spigot/Paper get the
> default `BukkitPluginScheduler`. `AbstractCloudPlugin` exposes three
> overridable hooks: `usesTickCounter()`, `createScheduler(JavaPlugin)`,
> and `scheduleMetricsReporting(BukkitMetricsCollector, Consumer<InstanceMetricsPayload>)`.
> Folia overrides all three; Spigot/Paper only override the abstract
> `createMetricsCollector(TickCounter)`. `FoliaMetricsCollector` now extends
> `BukkitMetricsCollector` (passing `null` for the unused `TickCounter`).
> Removed redundant Folia duplicates: `FoliaCloudClient`,
> `FoliaCommandRegistry`, `FoliaCommandSender`, `FoliaPlayerListener`,
> `FoliaPluginContext`, `FoliaServerCloudApi`. `cloud-plugins-server-paper`
> no longer depends on `cloud-plugins-server-spigot`.
- Files: `cloud-plugins-server-shared/src/main/java/.../bukkit/AbstractCloudPlugin.java`
  + nine sibling `Bukkit*` classes; `cloud-plugins-server-folia/.../PrexorCloudFolia.java`
  (now ~50 LoC, extends `AbstractCloudPlugin`).

### 2.4 REST body-parsing helpers — DONE
- File: `java/cloud-api/src/main/java/me/prexorjustin/prexorcloud/api/module/rest/RouteRegistrar.java`
- Add typed overloads:
  ```java
  <T> void post(String path, Class<T> bodyType, TypedHandler<T> handler);
  ```
  where `TypedHandler<T>` is `(ApiRequest req, T body, ApiResponse res) -> void`.
- On JSON parse failure: emit standard `{error: "invalid json body", details: <jackson msg>}` 400 response.
- Update `cloud-module-example/.../rest/PlaytimeRoutes.java` to use the
  new overloads — demonstrates the pattern + reduces 4 try/catch blocks to 0.
- Reuse: `ApiRequest.bodyAs()` already does the parsing (just wrap with error
  envelope at the registrar level).

### 2.5 `prexorctl module install --check-requires` preflight — DONE
- File: `cli/cmd/module_install.go`
- Add `--check-requires` flag. When set:
  1. Open the jar to read `module.yaml` (existing CLI does this for moduleId detection)
  2. GET `/api/v1/modules/platform/capabilities` (existing endpoint per
     `controller/rest/route/ModuleRoutes.java:256`)
  3. Diff requires vs available; print warnings with module-id hints from
     a `marketplace.json` or fallback to "no provider known"
- Reuse: `cli/internal/api/client.go::Get`, existing manifest parsing in
  `cli/internal/scaffold/`.

### 2.6 `prexorctl module new` wizard mode — DONE
> Implementation note: shipped with `--interactive` (huh multi-select for
> targets) and `--targets paper,folia,velocity` for non-interactive scripts.
> Supported targets reflect the example template's actual variants — bungeecord
> and bedrock-geyser are deferred to Phase 3.1 (the example doesn't ship them
> yet, so there's nothing to scaffold). Frontend toggle and Paper-MC-version
> selection are also deferred — the template currently includes both v1_20 and
> v1_21 unconditionally and a vue frontend; trimming those is its own pass once
> Phase 3.3 lands the paper-api split.
- File: `cli/cmd/module.go` and `cli/internal/scaffold/scaffold.go`
- When invoked without `--all-defaults`, run interactive `huh` form:
  - Module id (kebab-case)
  - Targets (multi-select: paper, folia, velocity, bungeecord, bedrock-geyser)
  - For each Paper target: which MC versions? (1.20, 1.21, custom)
  - Frontend? (vue / none)
  - Provides capabilities? (free text list)
- Generate only the chosen subprojects. Current scaffold drops the full 4-plugin
  template every time, which produces dead subprojects in modules that don't need them.
- Reuse: `cli/cmd/setup.go` already uses `huh` extensively — same pattern.

### 2.7 `prexorctl module doctor <jar|dir>` — DONE
> Implementation note: directory-input validation (build.gradle.kts cross-checking
> `extensionArtifacts` declarations) is deferred — the gradle plugin already
> enforces that on assembly, and the jar's `META-INF/prexor/module.yaml` is the
> authoritative copy on the install path.
- New file: `cli/cmd/module_doctor.go`
- Validates a module jar or source dir:
  - `module.yaml` parses + matches `manifestVersion: 1` constraint
  - All `extensionArtifacts` declared in `build.gradle.kts` exist in the JAR
  - All extension SHA-256 entries match actual file content
  - Backend entrypoint class exists in JAR
  - Capabilities `provides`/`requires` are well-formed semver
  - If JAR: signature present (cosign bundle or .sig sidecar)
- Exit codes: `0` clean, `1` warnings, `2` errors. CI-friendly.
- Reuse: existing manifest parser logic from `controller/.../PlatformModuleManifestParser.java`
  (extract a shared lib into `cloud-common` so CLI can call it via JNI? Or
  re-implement in Go using gopkg.in/yaml.v3 — simpler).

**Phase 2 acceptance**: All four module-system gap fixes ship and are
demonstrated in `cloud-module-example`.

---

## Phase 3 — Bedrock + DX + Tests (Day 61–90)

### 3.1 Bedrock/Geyser support — full integration — ✅ shipped (commit f177fda)
- New subproject: `java/cloud-plugins/cloud-plugins-bedrock-geyser/`
  - `build.gradle.kts` applies `prexorcloud.java21-compat`, depends on
    `org.geysermc.geyser:api` (compileOnly) and `cloud-api` (compileOnly + AP)
  - `AbstractGeyserCloudPlugin` extending `Extension` (Geyser's plugin base) +
    wiring `BaseControllerClient`, `CloudStateCache`
- New convention plugin: `java/build-logic/src/main/kotlin/prexorcloud.plugin-bedrock-geyser.gradle.kts`
- File: `java/cloud-api/src/main/java/me/prexorjustin/prexorcloud/api/plugin/annotation/CloudPluginProcessor.java`
  - Add `case "geyser"` in the platform switch (line 114)
  - Generate `GeyserBridge` extending `org.geysermc.geyser.api.extension.Extension`
  - Generate `extension.yml` (Geyser's descriptor format)
  - Add classpath auto-detection: `eu.getTypeElement("org.geysermc.geyser.api.GeyserApi") != null) return "geyser"`
  - Add to `detectPlatform()` priority list ahead of Velocity (Geyser can run
    standalone OR alongside Velocity, so explicit `-Acloud.platform=geyser` is
    recommended)
- Extension manifest target: extend `WorkloadExtensionManifest` to recognize
  `target: server/bedrock-geyser` or `proxy/bedrock-geyser` (Geyser can run
  in either standalone or as a Velocity plugin).
- Reference variant: add `cloud-module-example/plugin/bedrock-geyser/` showing
  how to handle Bedrock-specific player metadata.
- Documentation: extend `docs/engineering/modules.md` with Bedrock section.

### 3.2 Module integration-test-harness — ✅ shipped (commit 8582c8e)
- New file: `java/cloud-test-harness/src/main/java/.../module/ModuleTestHarness.java`
- Uses `org.testcontainers:mongodb:1.21.x` and `:redis` (add to
  `libs.versions.toml`).
- Brings up ephemeral Mongo + Redis, then a lightweight in-process controller
  (`PrexorCloudBootstrap` with test config), exposes `RouteRegistrar`,
  `ModuleDataStore`, `CapabilityRegistry` for tests.
- `cloud-module-example`: add an integration test using harness. Real Mongo
  round-trip for `PlaytimeRepository`, real REST round-trip for `/top`.
- Update `docs/engineering/modules.md` "Testing" section.
- Reuse: existing `TestCluster` in `cloud-test-harness` is the inspiration but
  too heavyweight (full daemon spawn). `ModuleTestHarness` is lighter — just
  controller + storage.

### 3.3 `paper-api` version split in `libs.versions.toml` — DONE
- Replaced single `paper-api` alias with `paperApi120` (1.20.4) +
  `paperApi121` (1.21.4) in `java/gradle/libs.versions.toml`. Catalog accessor
  naming is camelCase (not `paper-api-1-20`) because Gradle generates
  unreachable accessors when path segments are purely numeric.
- All existing callers (server-spigot, server-paper, server-folia,
  cloud-plugins-internal, the `prexorcloud.plugin-paper` and
  `prexorcloud.plugin-folia` convention plugins) pinned to `paperApi120`
  to preserve current behavior.
- New convention plugin:
  `java/build-logic/src/main/kotlin/prexorcloud.plugin-paper-1-21.gradle.kts`
  using `paperApi121`. `cloud-module-example/plugin/paper/v1_21/build.gradle.kts`
  switched to it. The v1_20 sibling continues to use
  `prexorcloud.plugin-paper`.

### 3.4 Frontend module-SDK package — DONE
- New: `dashboard/packages/module-sdk/` (mirrors existing `api-sdk` package)
- Exports: `useScopedApi`, `registerPage`, type `ModuleContext`, `defineModule`
- Existing module frontends (`cloud-module-example/frontend/`,
  `cloud-module-stats-aggregator/frontend/`) migrate to import from
  `@prexorcloud/module-sdk` instead of pulling Nuxt internals.
- `module.yaml` `frontend.sdkVersion: 1` already exists — formalize it
  as the contract.

### 3.5 Hot-reload during `prexorctl module dev` — ✅ shipped (commit 551e460)
- File: `cli/cmd/module_dev.go`
- Currently watches the jar and uploads on change (install first time, upgrade
  thereafter). Good for backend.
- Add: when a module-frontend file changes, the CLI uploads the new
  frontend bundle alone via a new endpoint `/api/v1/modules/platform/{id}/frontend/reload`
  (new endpoint in `controller/.../rest/route/ModuleRoutes.java`). Dashboard
  re-mounts the module page without full controller upgrade.
- Optional, can defer if time-constrained.

**Phase 3 acceptance**: Bedrock-Geyser plugin compiles + `cloud-module-example`
ships a Bedrock variant. `ModuleTestHarness` integration test passes.

---

## Phase 4 — Politur + Adoption (Day 91–180)

These are higher-level items, less code-detailed. They turn the project from
"technically good" to "actually adoptable".

### 4.1 Mintlify docs site — ⟳ superseded by `WEBSITE_PLAN.md`
- The "Mintlify" intent was superseded: the team locked **Astro Starlight**
  instead (`WEBSITE_PLAN.md` §"Decisions locked in"). The docs-site rewrite is
  tracked there, not as a separate MASTER_PLAN item — and it is now
  engineering-complete (270 pages build clean; remaining launch-ops captured
  in `LIVE_CLUSTER_GUIDE.md` §3).
- Categories, search, and the CloudNet migration guide all landed under the
  Astro Starlight content tree.

### 4.2 Onboarding polish (wizard already shipped in Phase 1.8)
- The browser-based setup wizard and one-line install already shipped in
  Phase 1.8. Phase 4's onboarding work is *polish*, not delivery:
  - Visual design pass on the wizard (move from plain HTML to dashboard-
    consistent styling, add progress bar, add inline "what does this mean?"
    tooltips for each setting).
  - Localized copy (DE/EN — folds into Phase 4.6 i18n work).
  - Better error states with "what went wrong + how to fix it" links to docs.

### 4.3 Capability deprecation lifecycle (`manifestVersion: 2`) — ✅ shipped
- `CapabilityDeclaration.Provides` carries optional `deprecatedSince` / `removedIn`; parser
  accepts both fields only when `manifestVersion: 2`, and rejects `removedIn` without
  `deprecatedSince`.
- `CapabilityRegistry` logs `WARN` on every resolution that lands on a deprecated provider
  and exposes a `deprecatedProviderResolutionCount` counter (`prexorcloud.capabilities.deprecated_resolutions`).
- `prexorctl module doctor` warns when the inspected manifest advertises a deprecated provide
  and rejects v1 manifests that try to use the new fields.

### 4.4 VS Code extension MVP — ✅ shipped
- `dashboard/packages/vscode-extension/` — a workspace package bundled with
  esbuild, consuming the workspace-local `@prexorcloud/api-sdk` for a typed
  controller client.
- **Connect to a controller**: `PrexorCloud: Connect to Controller` prompts for
  URL + credentials, calls `POST /api/v1/auth/login`, persists the URL in
  settings and the bearer token in `SecretStorage`.
- **Browse instances**: an activity-bar `TreeDataProvider` over
  `GET /api/v1/services`, grouped by group with live state / node / players.
- **Tail logs in editor**: per-instance Output channel — replays
  `console/history` scrollback, then tails the SSE console stream via the
  ticket flow (`POST /api/v1/events/ticket` → `GET …/console?ticket=`).
- **Edit templates inline**: a `prexorcloud-template:` `FileSystemProvider` so
  template files open and save natively against
  `GET/PUT /api/v1/templates/{name}/files/content`, fronted by a Templates tree.
- Out of MVP scope: file create/rename/delete, multi-context, lifecycle actions.

### 4.5 Public benchmark page — ✅ shipped
- `docs/public/en/benchmarks.md` rendered by `tools/gen-benchmarks.ts` from the
  committed `infra/perf/baselines.json`. Surfaces controller cold start,
  coordination SET+GET p50/p95, SSE event latency p50/p95, and scheduler tick
  p50/p95, with the drift threshold and snapshot date.
- `pnpm --filter prexorcloud-website gen:bench` regenerates the page; CI
  drift-check job re-runs the generator and fails on a working-tree diff —
  mirrors the CLI / gRPC drift pattern.
- Sidebar entry under "Project". Path on disk follows existing top-level
  files (`changelog.md`, `contributing.md`); the MASTER_PLAN's original
  `website/content/benchmarks.md` predates the move of content under
  `docs/public/en/`.
- Numbers: cold-start latency, gRPC throughput, instance-spawn time. Real data.

### 4.6 i18n DE/EN in dashboard — ⟳ descoped per ADR 22
- **Decision:** the dashboard is English-only (`decisions.md` ADR 22). Real
  i18n is a separate project (translation pipeline, RTL, locale-aware
  formatting) — revisit only on a request from a non-English audience.
- The scaffolding below *did* land and is harmless to keep, but no further
  page extraction is planned; the "Remaining" list is intentionally not being
  worked.
- `@nuxtjs/i18n` (v10) wired up: `no_prefix` strategy, EN fallback, bundled
  messages via `i18n.config.ts` (`i18n/locales/{en,de}.json`), cookie-backed
  browser-language detection.
- Extracted: `lib/navigation.ts` (group/item labels via `labelKey`/`titleKey`
  resolved in `AppSidebar`), shared components (`ConfirmDialog`,
  `FilterToolbar`), the three auth pages, the settings page, the
  component/page-level toast call sites, and **every toast in `app/stores/*.ts`
  + `app/composables/*.ts`** via a new `~/lib/translate` helper (`t()` backed by
  `useNuxtApp().$i18n`, usable outside component setup).
- New `SettingsLanguage` component ships a DE/EN picker under Settings →
  Preferences; choice persists via the i18n cookie.
- Vitest: `tests/vitest-setup.ts` installs a populated vue-i18n instance as a
  global plugin and `vi.mock`s `~/lib/translate` with the real catalogue, so
  component *and* store/composable tests keep asserting rendered copy. Full
  suite green (853 tests), typecheck clean, `nuxt build` green.
- Per-page inline strings: 11 pages fully extracted — `index`, `nodes/index`,
  `groups/index`, `instances/index`, `templates/index`, `crashes/index`,
  `audit/index`, `observability/{activity,logs,system}`, `users/index` —
  PageHeaders, filter labels, table headers, empty states, detail-sheet rows,
  dialog copy, pagination, and the page-level toasts they own. Keys live under
  `pages.*` (with a shared `pages.common` for pagination/relative-day labels).
- **Remaining**: ~23 detail/secondary pages — `*/[id].vue` & `*/[name].vue`
  detail pages, the rest of identity (`roles`, `tokens`, `certificates`,
  `workloads/credentials`), operations (`backups`, `maintenance`), `networks`,
  `catalog`, `cluster/players`, `profile`, `map`, `modules`,
  `workloads/deployments`. Same mechanical pattern — `useI18n()` + `pages.*`.

---

## Critical Files To Modify

### Java backend
- `java/gradle/libs.versions.toml` — adventure remove, paper-api split, testcontainers add
- `java/cloud-api/src/main/java/me/prexorjustin/prexorcloud/api/client/version/VersionAdapterRegistry.java` — DELETE
- `java/cloud-api/src/main/java/me/prexorjustin/prexorcloud/api/plugin/annotation/CloudPluginProcessor.java` — geyser case, proxy VersionDispatcher init
- `java/cloud-api/src/main/java/me/prexorjustin/prexorcloud/api/module/rest/RouteRegistrar.java` — typed body-parse overloads
- `java/cloud-controller/src/main/java/me/prexorjustin/prexorcloud/controller/journey/PlayerJourneyService.java` — remove (extracted to module)
- `java/cloud-controller/src/main/java/me/prexorjustin/prexorcloud/controller/webhook/WebhookAlertService.java` — remove (extracted to module)
- `java/cloud-plugins/cloud-plugins-proxy/cloud-plugins-proxy-shared/` — new `AbstractProxyCloudPlugin`
- `java/cloud-plugins/cloud-plugins-server/cloud-plugins-server-folia/.../PrexorCloudFolia.java` — extend `AbstractCloudPlugin`
- `java/cloud-plugins/cloud-plugins-server/cloud-plugins-server-shared/.../AbstractCloudPlugin.java` — overridable scheduler hook
- `java/cloud-test-harness/src/main/java/.../module/ModuleTestHarness.java` — new
- `java/build-logic/src/main/kotlin/prexorcloud.plugin-bedrock-geyser.gradle.kts` — new
- New subproject: `java/cloud-plugins/cloud-plugins-bedrock-geyser/`
- New subproject: `java/cloud-module/cloud-module-player-journey/`
- New subproject: `java/cloud-module/cloud-module-webhook-alerts/`
- `java/settings.gradle.kts` — register new subprojects

### Go CLI
- `cli/cmd/module.go` — wizard
- `cli/cmd/module_install.go` — `--check-requires`
- `cli/cmd/module_doctor.go` — new
- `cli/cmd/module_dev.go` — frontend hot-reload
- `cli/internal/scaffold/scaffold.go` — selective scaffold
- `cli/cmd/setup.go` + new `cli/cmd/setup_browser.go` + `cli/internal/setupweb/` — browser wizard

### Dashboard
- `dashboard/packages/module-sdk/` — new package
- `dashboard/app/pages/catalog/` — delete index.vue + [platform].vue
- `dashboard/app/pages/settings/*` — feature-flag guard
- `dashboard/app/lib/navigation.ts` — drop catalog link, conditional settings link
- `dashboard/app/components/groups/CreateGroupDialog.vue` — embed catalog selector

### CI
- `.github/workflows/ci.yml` — drop CVE + SBOM
- `.github/workflows/nightly.yml` — add CVE + SBOM

### Docs / repo root
- `docs/MASTER_PLAN.md` — this file
- `docs/engineering/modules.md` — extend with Bedrock + Testing sections
- `docs/modules-multi-version.md` — new
- `README.md` — brand anchor
- `website/content/index.md` — brand anchor

---

## Existing Functions/Utilities To Reuse

- `BaseControllerClient` (`cloud-plugins-internal/.../BaseControllerClient.java`)
  — auth + token refresh single-flight. Reuse for proxy plugin base.
- `CloudStateCache` (`cloud-plugins-internal/.../CloudStateCache.java`)
  — SSE + polling fallback. Reuse for proxy plugin base.
- `ProxyControllerClient` (`cloud-plugins-internal/.../ProxyControllerClient.java`)
  — `/api/proxy` REST surface. Already shared between Velocity + BungeeCord.
- `AbstractCloudPlugin` (`cloud-plugins-server-shared/.../AbstractCloudPlugin.java`)
  — server-side base. Make Folia extend it after the scheduler hook is added.
- `CapabilityRegistry` (`cloud-controller/.../module/platform/CapabilityRegistry.java`)
  — already supports `@controller`-sentinel moduleId for built-in capabilities.
  Reuse same pattern when extracting `prexor.player.journey` to a module
  (the consumer-side range stays the same).
- `PlatformModuleManifestParser` (`cloud-controller/.../module/platform/PlatformModuleManifestParser.java`)
  — strict YAML parse. The CLI `module doctor` needs equivalent logic.
  Either share via a `cloud-common`-extracted lib or re-implement in Go
  (preferred for solo build simplicity).
- `huh` form library (used in `cli/cmd/setup.go` extensively) — same library
  for `module new` wizard.
- `cli/internal/api/client.go::Get/GetList/Post` — existing typed HTTP client.
  Use directly in `module install --check-requires`.
- `ApiRequest.bodyAs()` — existing parse helper.
  `RouteRegistrar` typed overloads wrap it and add the error envelope.
- `WorkloadExtensionManifest.target` validator regex (in `prexorcloud.module.gradle.kts:106`) —
  extend the regex to accept `bedrock-geyser` as a target prefix.
- `CloudPluginProcessor.detectPlatform()` (`CloudPluginProcessor.java:146`) —
  add Geyser detection branch following the existing pattern.

---

## What NOT To Touch

These are working well or are core differentiators. Any change here is
out of scope for this plan:

1. **Cosign + Rekor signed install pipeline** (`PlatformModuleSignatureVerifier`,
   `cloud-controller/.../module/platform/`)
2. **Capability dynamic-proxy mechanism** in `CapabilityRegistry`
3. **`CloudPluginProcessor`** core generation logic — only ADD the `geyser` case
   and the proxy `VersionDispatcher` init, do not refactor the rest
4. **gRPC daemon protocol** (`cloud-protocol/` proto files)
5. **CDS archive cache** in daemon's `PaperBootstrapCache`
6. **DR drill** (`cloud-test-harness:drDrill`) and **perf-baseline runner**
7. **JWT + ticket-based SSE** (`controller/.../rest/sse/`)
8. **Manifest version=1 contract** — extending to v2 is Phase 4 work, do not
   break v1 compatibility
9. **Existing example modules** (`cloud-module-example`, `stats-aggregator`,
   `playtime-consumer`) — they are reference DX. Only ADD a Bedrock variant
   to the example; do not restructure the existing ones beyond migrating
   their frontends to `@prexorcloud/module-sdk` in Phase 3.4

---

## Verification

End-to-end checks per phase:

### Phase 1
- `cd java && ./gradlew build -x test && ./gradlew test` — green
- `cd cli && go build ./... && go vet ./... && go test ./...` — green
- `cd dashboard && pnpm typecheck && pnpm build` — green (typecheck error count
  should not increase from current pre-existing baseline)
- `git diff docs/MASTER_PLAN.md` — exists, contains this plan
- `cat README.md website/content/index.md | grep -i "production-grade"` —
  brand anchor present
- `.github/workflows/ci.yml` — no `cve-scan` or `sbom` jobs
- `prexorctl setup --browser` opens the wizard and bootstraps a controller
  end-to-end on a fresh VM

### Phase 2
- `cloud-plugins-proxy-velocity` and `cloud-plugins-proxy-bungeecord` each
  have <40 LoC of plugin-specific code (everything else is in `AbstractProxyCloudPlugin`)
- A module exposing a Velocity plugin can use `@ForVersion` and dispatch
  on Velocity 3.3 vs 3.4 — verified by adding a unit test in `CloudPluginProcessorTest`
- `PrexorCloudFolia` extends `AbstractCloudPlugin`, all integration tests still
  pass on Folia in `cloud-test-harness`
- `PlaytimeRoutes.java` no longer has try/catch around `bodyAs()` calls
- `prexorctl module install --check-requires examples/playtime-consumer.jar`
  prints meaningful warnings when run against a controller that lacks the
  `example-playtime-query` capability
- `prexorctl module new --interactive` produces only the chosen subprojects
- `prexorctl module doctor cloud-module-example/build/libs/example-playtime-1.0.0-SNAPSHOT.jar` — clean exit 0

### Phase 3
- `cloud-plugins-bedrock-geyser` compiles, `prexorcloud.plugin-bedrock-geyser`
  applies cleanly to a sample subproject
- `cloud-module-example/plugin/bedrock-geyser/` builds; the manifest's
  `target: server/bedrock-geyser` resolves on a Geyser-running daemon
- `cloud-module-example/src/test/java/.../IntegrationTest.java` (new) using
  `ModuleTestHarness` runs Mongo + REST round-trip in <30s on local Docker
- `paper-api-1-20` and `paper-api-1-21` are referenced separately by the two
  example Paper subprojects; no version-mismatch warnings
- `dashboard/packages/module-sdk` builds, `cloud-module-example/frontend`
  imports from it without dashboard internals
- (Optional) `prexorctl module dev` — editing a `.vue` file in
  `cloud-module-example/frontend/` triggers frontend-only reload

### Phase 4
- Mintlify site live at `docs.prexor.cloud` (or wherever)
- `curl get.prexor.cloud/install | bash` produces a working `prexorctl`
- Browser setup wizard accessible at `localhost:9100/setup` after
  `prexorctl setup --browser`
- `manifestVersion: 2` example module deploys; deprecation warnings appear in
  controller log
- VS Code extension installs from VSIX, lists instances of a connected controller
- `website/content/benchmarks.md` shows real perf-baseline numbers
- Dashboard switchable DE/EN

---

## Out Of Scope (Explicitly)

To keep this plan ship-able as a solo dev:

- No pivot to Minestom / WASM / Rust / k8s-native runtime.
- No Forge/NeoForge support (vanilla-modding ecosystem, not server-orchestration domain).
- No multi-region federation in this plan window.
- No in-browser plugin dev environment (Replit-style) — niche, defer to >180 days.
- No AI-assisted module generation in this plan window.
- No new dashboard themes / re-design — tighten what exists.
