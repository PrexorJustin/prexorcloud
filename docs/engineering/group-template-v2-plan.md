# Group & Template v2 — re-architecture plan

Status: proposed (2026-06-24). Owner: control-plane. Decision of record: [ADR 35](decisions.md#adr-35-group--template-v2-targeted-re-architecture-not-a-rewrite).

This plan turns the 2026-06-24 assessment of the Group/Template system into phased, independently-shippable work. The goal the operator set: the most modern, most comprehensive Group/Template system a Minecraft cloud can offer. The honest finding behind this plan: **the foundations are already ahead of CloudNet and Pterodactyl; the gaps are in four peripheral layers** — scaling intelligence, the variable system, config patching, and storage transport — plus an uneven operator surface. We rebuild those layers, on clean v2 authoring contracts, behind the seams that already work.

## What "everything that belongs to it" means here

The complete blast radius, mapped across six agent sweeps. A rewrite that misses any of these breaks something.

### Group touchpoints

| Layer | Components |
|---|---|
| Data model | `GroupConfig` (52-field record + 3 **test-only** legacy ctors), `GroupRuntimeTarget/Family/Resolver` |
| Persistence | `MongoGroupStore` (`groups` collection, `_id=name`) — **authoritative, Mongo-only**. `GroupManager` is an in-memory working set seeded from Mongo at boot. `GroupConfigLoader` (YAML `groups/*.yml`) is **dead/unwired legacy** — never instantiated; its "single source of truth — no database" javadoc is stale → **delete it**. `GroupStore` iface |
| Management | controller `GroupManager`; **public** `cloud-api` `GroupManager` (`createGroup`/`updateGroup`/`deleteGroup`/`setMaintenance`), `GroupCreateRequest`, `GroupUpdateRequest` |
| Scheduling | `ScalingEvaluator`, `SchedulerDesiredStatePlanner`, `InstancePlacementCoordinator`, `WeightedNodeSelector`, `PortAllocator`, `ResourceAccounting`, `CrashLoopDetector`, `EventChoreographer` |
| Composition bridge | `InstanceCompositionPlanner.plan(GroupConfig,…)` → `InstanceCompositionPlan` + `planHash` → `instance_composition_plans` collection |
| REST | `GroupRoutes` (CRUD, `/resolved`, `/start`, `/restart`), `DeploymentRoutes` |
| CLI | `cmd/group.go` (list/info/create/update/delete/scale/maintenance) |
| Dashboard | `groups/index.vue`, `groups/[name].vue`, `CreateGroupDialog.vue` — **no edit UI for existing groups** |
| Read models | `GroupView` (cloud-api domain, public), `GroupDto` (plugin wire) |
| Plugin consumers | `CloudStateCache` (GroupDto→GroupView+`ServerGroupMotd`), `NetworkRouter` (defaultGroup/fallback), `VelocityPingListener` (MOTD), proxy listeners (`fallbackChain`), `AbstractCloudApi` |
| Network Composition | `lobbyGroup`/`fallbackGroups`/`bedrockLobbyGroup`/`bedrockFallbackGroups` reference group **names** (string FKs) |
| Events | `GroupCreated/Deleted/Updated/MaintenanceChanged/CrashLoop/AggregatesUpdated` — only **`GroupCrashLoopEvent`** has a live external subscriber (`webhook-alerts`); rest are dashboard-internal |
| Modules (label only) | stats `GroupStat.group`, `tablist` `TablistTemplate.group` |
| Proto | `StartInstance.group`, `deployment_revision`, `CrashReport.group` |
| Geyser | `bedrockProxyGroup` → `BedrockRemoteResolver` |

### Template touchpoints

| Layer | Components |
|---|---|
| Data model | `TemplateConfig` (name/desc/platform/hash/sizeBytes), `TemplateVersion`, `TemplateVariable` |
| Controller | `TemplateManager` (scanAndHash/rehash/snapshots/WatchService/restoreSnapshot), `BaseTemplateGenerator`, `TemplateMerger`, `TemplateVariableProcessor` (`{{var}}`), `ops/ArchiveSearcher`, `SnapshotExtractor` |
| Composition | `InstanceCompositionPlanner.resolveTemplates` — chain `base → base-{platform} → {group} → {user…}`, `ResolvedTemplate{name,hash,source}` |
| Persistence | `templates` collection (`versions[]`, `variables[]`); files on disk `templates/<name>/files/` + `snapshots/<hash>.tar.gz`; **cross-controller needs shared FS/S3** |
| Delivery (proto) | `TemplateRequest`/`TemplateData`/`TemplateUpToDate`/`TemplateRef`/`TemplateCacheEntry`, `DaemonTemplateRequestHandler` |
| Daemon | `TemplateCache`, `JarCache`, `ArtifactCache`, `PaperBootstrapCache`, `TemplateUnpacker`, `ConfigMerger` (deep-merge), `VariableSubstitution` (`%VAR%`), `ServerConfigPatcher`; `TemplatePreparation` pipeline |
| REST | `TemplateRoutes` — file mgmt, versions/rollback, variables get/set/scan, inheritance, search, import/export |
| CLI | `cmd/template.go` — **list/versions/rollback only (skeletal)** |
| Dashboard | `templates/index.vue`, `templates/[name].vue`, Monaco editor, staged changes, version history |
| Events | `TemplateUpdatedEvent` (dashboard-only subscriber) |
| Deployment | template-hash change → `planHash` change → rolling deploy |

## Seams that stay frozen (the engine we keep)

These are proven live and are the hardest to re-establish. We build behind them, not through them.

1. **`InstanceCompositionPlanner.plan()` → `InstanceCompositionPlan` + `planHash` determinism.** Daemon dedup, failover-replayable dispatch, and idempotency all depend on the SHA-256 over the sorted plan. v2 authoring models **resolve down** to the planner's existing input via an adapter; the hash inputs do not change shape.
2. **Proto `PROTOCOL_VERSION = 1`.** Only additive `oneof`/optional fields. No required-field additions to `StartInstance`/`TemplateRef`/`CompositionPlan`.
3. **Public `cloud-api` contract** (`GroupManager`, `GroupCreateRequest`, `GroupUpdateRequest`, `GroupView`). Third-party modules compile against it — extend additively, never break.
4. **Plugin wire contract**: `GroupDto ↔ GroupView ↔ ServerGroupMotd`, and `NetworkComposition` routing fields (`lobbyGroup`/`fallbackGroups`/`bedrock*`).
5. **StateStore collection schemas** (`templates`, `groups`, `deployments`, `instance_composition_plans`) and the Mongo single-writer + `ownerEpoch` fence + change-stream reconcile.

## Target architecture

### New authoring contracts (the "fresh design" part)

`GroupSpec` v2 replaces the flat 52-field positional record with a nested, forward-compatible model. It **resolves to** the planner's existing input, so the frozen seam is untouched while the operator-facing shape is modern:

```
GroupSpec {
  identity   { name, parent, platform, platformVersion, jarFile }
  templates  [ TemplateLayerRef { name, pinnedHash?, vars? } ]
  scaling    ScalingPolicy {
               mode, min, max, maxPlayers,
               signals      [ ScalingSignal ],          // PLAYER_LOAD | TPS | CPU | MEM | CUSTOM(moduleKey)
               aggregation  ALL | ANY | AVG | P(percentile),
               targetUtilization, step,                 // scale-by-N toward a headroom target
               cooldownSeconds,
               warmPool     { minPrepared, ttlSeconds },
               predictive?  { model, lookaheadSeconds }
             }
  placement  { affinity, antiAffinity, spread, priority, topologyKeys }
  resources  { memoryMb, cpu, diskMb, jvmArgs, env }
  lifecycle  { startupTimeout, shutdownGrace, maxLifetime, drainOnShutdown }
  rollout    { strategy, waveSize, healthGate, autoRollback }
  ops        { maintenance, maintenanceMessage, maintenanceBypass,
               fallbackGroup, defaultGroup, dependsOn, startupWeight, motd… }
  variables  [ VariableDef ]                            // typed/validated, group scope
  modules / extensions { attached, enabled, disabled }
  bedrockProxyGroup
}
```

`TemplateSpec` v2 adds depth without breaking file packages:

```
TemplateSpec {
  name, description, platform, hash, sizeBytes,
  variables    [ VariableDef ],        // typed/validated (was untyped {key,value,desc})
  includes     [ Include ],            // URL/artifact pulled into files at build (CloudNet-style)
  install      InstallHook?,           // optional, sandboxed setup script
  parserRules  [ ConfigRule ],         // data-driven config patches (path/wildcard/regex)
  signature?, provenance?,             // cosign — reuse module signing
  storage      { backend, chunkManifest }
}
```

### Pluggable scaling engine

- `ScalingSignal` SPI: `double utilization(GroupRuntimeState)`. Built-ins: `PlayerLoadSignal` (today's behavior), `TpsSignal`, `CpuSignal`, `MemSignal`; `CUSTOM` lets a module contribute a signal.
- **TPS/MSPT becomes first-class** — the single most important Minecraft health metric. Requires an additive heartbeat field (plugins already run a tick task); proto-safe.
- Aggregation policy replaces today's all-or-nothing ("every instance must be saturated"). Scale **by N** toward `targetUtilization` instead of always +1.
- `WarmPoolManager`: keep `minPrepared` instances PREPARED-but-not-serving; promote on demand for instant join. **The biggest operator-visible win** — no JVM cold-start in the join path.
- Cooldown moves from the in-memory leadership-scoped map to Mongo (survives failover; closes the audit MED).

### Variable system v2

`VariableDef { key, type(STRING|INT|BOOL|ENUM|SECRET), default, required, validation(regex|range|enum), scope(TEMPLATE|GROUP|INSTANCE), visibility(ADMIN|OPERATOR), secret }`. One resolution pipeline: builtin → template → group → instance override → secret backend. Unify the `{{ }}` (build-time) / `%VAR%` (runtime) split under one definition model and document it. Secret backend SPI (env/file/Vault). Surface in CLI **and** Dashboard (today it is REST-only).

### Config model v2

`ConfigRule { file, format, path(dot/wildcard), op(set|replace|regex), value }` — data-driven, replacing the per-platform hardcoded `ServerConfigPatcher`. Platform config knowledge moves into catalog/template **data**, so a new platform needs no Java. Keeps the existing `ConfigMerger` deep-merge (already ahead of CloudNet) and adds Pterodactyl-parity path/wildcard/regex targeting.

### Storage v2

Content-defined chunking (FastCDC) into a content-addressed chunk store with dedup; the daemon fetches only **missing** chunks (delta sync); pluggable backend (local / S3); template signing via the existing cosign path. Snapshots become chunk manifests, not full tar.gz per version — kills the storage bloat and enables real cross-controller template consistency through a shared object store. Proto-safe: additive chunk fields on `TemplateRef`/`TemplateData`.

### Rollout v2

Implement `CANARY` (deploy one, observe crash/error/TPS, auto-rollback on regression); fix the `DeploymentReconciler.java:206-211` "continuing anyway" footgun (rollback or hard-stop on replacement timeout); expose rollback in CLI/Dashboard; add **deploy-back** ("save running instance → new template version", CloudNet-style).

### Operator surface parity

Generate the group **edit** form from the `GroupSpec` schema (closes the Dashboard read-only gap); variable editor UI; template version diff; CLI template file-ops + variable commands.

## Phases (prioritized, each independently shippable + fleet-validatable)

### Phase 0 — Foundations & safety net (enabler, low risk)

**In progress** on branch `feat/group-template-v2` (uncommitted):
- ✅ Deleted the dead, unwired `GroupConfigLoader` (YAML). Verified: no source references; `:cloud-controller` compiles and its planner suite stays green.
- ✅ Added a **golden `planHash` characterization test** to `InstanceCompositionPlannerTest` — pins the exact dispatch-idempotency hash (`5996131c…e4e993`) so any serialization drift in the frozen composition seam fails loudly. `:cloud-controller:test --tests InstanceCompositionPlannerTest` → 12 passed.
- ✅ Laid down the **v2 authoring contracts as a compiling proposal**: `controller/group/spec/GroupSpec.java` (nested `Identity`/`ScalingPolicy`/`Placement`/`Resources`/`Lifecycle`/`Persistence`/`Rollout`/`Ops`/`ModulePolicy` + scaling enums) and `controller/group/spec/VariableDef.java` (typed/validated/scoped/visibility). Every field grounded in an existing `GroupConfig` field; dead fields dropped (drainOnShutdown revived in `Lifecycle`); nothing consumes them yet. `:cloud-controller:compileJava` → SUCCESS. Shape confirmed (controller-internal; moderate nesting; STATIC mode kept separate from persistence).
- ✅ Wired **`GroupSpecAdapter.toGroupConfig()`** — the "resolves down to the legacy planner input" seam — via the proven Jackson field-name map (same pattern as `MongoGroupStore`), dropping v2-only fields the current engine can't act on yet. Field-by-field round-trip test across every nested policy + a legacy-invariant test. `GroupSpecAdapterTest` → 2 passed.
- ✅ Added **`TemplateSpec`** v2 (variables / includes / installHook / data-driven `parserRules` / signature / chunked `StorageRef`) + `toTemplateConfig()` resolve-down. `TemplateSpecTest` → 1 passed.

Remaining in Phase 0:
- **Characterization tests around the other frozen seams**: `ConfigMerger` layer-collision tests, deployment timeout / health-gate / scaling-during-rollout — close the THIN coverage before touching the engines.
- One-shot v1→v2 migration of the Mongo `groups`/`templates` collections + refuse-to-start on unmigrated data.
- Delete the 5 dead `@JsonIgnore` `GroupConfig` fields + 3 test-only legacy ctors (~50 test helpers); remove dead `GroupMaintenanceChangedEvent`; decide `maintenanceBypass`.
- One-shot migration of the Mongo `groups`/`templates` collections v1→v2, refuse-to-start on unmigrated data (matches the rewrite migration discipline / round-2 release gate). Groups are Mongo-authoritative — no YAML source of truth involved.
- Delete the 3 **test-only** legacy `GroupConfig` ctors and the 5 dead `@JsonIgnore` fields (`predictiveScaling`, `scaleUpMargin`, `burstCeiling`, `routing`, `drainOnShutdown` — drainOnShutdown returns as a real `lifecycle` field); delete the **dead, unwired `GroupConfigLoader`** (YAML) and its stale "source of truth" javadoc; remove dead `GroupMaintenanceChangedEvent` + decide `maintenanceBypass` (wire it or drop it). ~50 test helpers to update.

### Phase 1 (P0) — Scaling v2 + Warm Pool
Pluggable signals (TPS first-class), aggregation policy, scale-by-N, warm pool, cooldown→Mongo. Behind the `Scheduler`/`ScalingEvaluator` seam. **Highest operational leverage.**

**In progress** on branch `feat/group-template-v2` (uncommitted). Sequenced controller-only first (operator-chosen), wire/lifecycle changes deferred:
- ✅ **Aggregate-load scale-up + scale-by-N** in `ScalingEvaluator`: triggers on mean fleet utilisation vs the target (a single quiet instance no longer suppresses scale-up), and adds enough instances in one step to bring load back to target instead of a flat +1 (capped at `maxInstances`). Behaviour-compatible on every existing test; 3 new tests prove the divergent cases.
- ✅ **Cooldown → Mongo** (failover-safe). A small dedicated `ScaleActionStore` (interface + `MongoScaleActionStore` against a `scale_actions` collection) instead of growing `StateStore` — chosen partly to avoid entangling with the uncommitted #12 epoch-fence WIP that already touches `StateStore`/`MongoStateStore`. `ScalingEvaluator` keeps the in-memory fast path and seeds it lazily from the store (incl. a negative EPOCH entry) so steady-state checks never hit Mongo; a new leader reads the persisted cooldown. New `cooldownSurvivesFailover` test proves it. Full `:cloud-controller:test` green (ScaleUp 10 / ScaleDown 5 / Cooldown 4 / wiring 2).

- ✅ **TPS-aware scale-up.** Turned out to need **no** proto/plugin/daemon change: plugins already compute TPS (`PaperMetricsCollector.getTPS()`/`getAverageTickTime()`) and report it via REST `POST /api/plugin/metrics` into `InstanceMetrics`. The only gap was the last mile — `InstanceMetrics.tps1m` was never threaded into `InstanceInfo`. Added `tps1m` to `InstanceInfo` (+ `withTps`, `InstanceRegistry.updateTps`, threaded in `ClusterState.updateInstanceMetrics`), and `ScalingEvaluator` now scales up one instance when any running server is tick-starved (`tps1m` in `(0, 18.0)`) even at low player load. `tps==0` (no data / not a game server) is ignored, so all prior behaviour is unchanged. 3 new tests; full `:cloud-controller:test` green (ScaleUp 13). The `18.0` floor is a constant for now → becomes a per-group `ScalingPolicy` signal threshold when v2 `GroupSpec.scaling.signals` are wired into the engine.

- ✅ **Warm pool** (operator wants it). Design: a `boolean warm` flag on the instance (RUNNING but held back from routing), **not** a new `InstanceState` — a flag touches only the routing filter + DTO plumbing, while a new state would cascade through the FSM, validators, and UI. **Foundation done + committed:** `GroupConfig.warmPoolMinPrepared`, `InstanceInfo.warm` + `withWarm`, `InstanceRegistry.updateWarm`, and `ClusterState.servingInstances`/`warmInstanceCount`/`promoteWarmInstance`/`markInstanceWarm` (tested). `planHash` unchanged (golden test green). Done in two focused chunks:
  1. ✅ **Scheduler engine** (`7f7d24e`). `ScalingEvaluator` now excludes warm instances from serving capacity (they don't dilute utilisation or count toward min/max — tested). `GroupPlan` carries `warmPoolTarget`; `SchedulerDesiredStatePlanner.planGroup` sets it from `warmPoolMinPrepared`; the `Scheduler` executor **promotes a warm instance before cold-starting** on scale-up and refills the pool to its target via `placeWarmInstance`. Full `:cloud-controller:test` green (ScaleUp 15).
  2. ✅ **Proxy routing exclusion (Increment 2).** Threaded `warm` end-to-end: controller `WorkloadDtoMapper.toInstanceDto` emits it → plugin `InstanceDto.warm` → cloud-api `InstanceView.warm` (additive last field, **records can't be subclassed** so a field + `withState`/`withPlayerCount`/`withWarm` withers; the withers also make the `CloudStateCache` delta methods immune to silently dropping `warm`). Automatic routing now filters `RUNNING && !warm` at every pick site: `VelocityCloudClient`/`VelocityCloudPlayer`/`VelocityPlayerListener`, the BungeeCord equivalents (`BungeeCloudPlayer`/`BungeePlayerListener`), and the controller-side group-transfer pick in `PluginRoutes`. Warm instances stay **registered** with the proxy (the `RUNNING`-gated `AbstractProxyCloudPlugin` backend sync is warm-agnostic) so promotion is instant; rollout-sizing counts (`DeploymentRoutes`/`GroupRoutes`) deliberately still count warm instances. **Warm-flag broadcast wired:** a new additive `InstanceWarmChangedEvent` (`INSTANCE_WARM_CHANGED`) fires from `promoteWarmInstance`/`markInstanceWarm` — a RUNNING→RUNNING flip emits no `InstanceStateChangedEvent`, so without it proxies wouldn't learn an instance became routable until the next full snapshot. SSE auto-forwards it; `CloudStateStreamClient` applies it via `cache.applyInstanceWarmDelta`. New tests: `CloudStateStreamClientTest.appliesWarmDeltaAndPreservesItAcrossLaterDeltas` and `ClusterStateWarmPoolTest.broadcastsWarmFlagChanges`. `planHash` golden test green; `:cloud-controller:test` + `:cloud-plugins:internal:test` green; velocity/bungee/server modules compile.
  3. ✅ **Full proxy invisibility (Increment 2b, `7a26c88`).** Superseded the "stay registered" decision: a warm instance is now **never registered** as a proxy backend, so it's invisible/unjoinable (absent from `/server`) — not merely skipped by auto-routing. `AbstractProxyCloudPlugin` backend sync skips `instance.warm()` and unregisters one that flips to warm; `CloudStateCache.notifyInstanceListeners` now also fires on a warm-flag flip (a RUNNING→RUNNING promote/hold-back produces no add/remove/state signal) so promotion still registers the backend **instantly** via the existing `INSTANCE_WARM_CHANGED` SSE delta. `ClusterStateWarmPoolTest.broadcastsWarmFlagChanges` made order-independent (async `EventBus` doesn't preserve cross-publish order). **Live-validated on the Hetzner fleet 2026-06-25** via a real Velocity `edge` proxy: warm instance excluded at proxy startup; raising `min` promoted it and the proxy registered it instantly; a freshly-spawned warm instance was never registered. ⚠️ **Deployment gotcha found:** `BaseTemplateGenerator` bakes the bundled plugin jar into each `base-*` template **once** (skips if the template exists) and the daemon `TemplateCache` keys by template hash — so rebuilding the controller with an updated plugin does **not** propagate to instances. Work-around used: re-upload the new plugin jar via `POST /api/v1/templates/base-velocity/files/upload?path=plugins` (rehashes the template → daemon re-fetches). Candidate follow-up: on startup, re-inject a bundled plugin whose bytes differ from the stored template copy.

### Phase 2 (P0) — Variable system v2
Typed/validated defs, unified resolution, secrets SPI, surfaced in CLI + Dashboard. Builds on the
committed foundation (`VariableDef` typed/validated/scoped/visibility, `VariableValidator`,
`TemplateSpec.variables`). Today's system is two binding-time passes kept for back-compat: controller
`{{var}}` resolved in `TemplateMerger` from untyped `TemplateVariable{key,value,description}`, and
daemon `%VAR%` (7 builtins: PORT/INSTANCE_ID/INSTANCE_NAME/GROUP/NODE_ID/MEMORY/MAX_PLAYERS) in
`TemplatePreparation`. Increments:
1. ✅ **Scope-aware unified resolver** (`VariableResolver`) — layers template-default → group →
   instance, enforces each var's declared `Scope` (TEMPLATE fixed, GROUP group-only, INSTANCE either),
   rejects forbidden-scope and undeclared keys, then type-validates via `VariableValidator`. Pure logic
   + `VariableResolverTest` (8 cases). No persistence/wire change yet.
2. ✅ **Persist typed defs + validate-on-set** — the template's Mongo `variables` field is now typed
   `VariableDef` (legacy untyped `TemplateVariable` retired wholesale; `VariableDefCodec` reads a legacy
   `{key,value,description}` doc back as a STRING/INSTANCE/OPERATOR def so `{{}}` keeps working). Invalid
   definitions/values are rejected at the REST boundary (422). `GroupConfig.variableValues` carries
   per-group overrides; `GroupVariableResolver` is the single resolution owner for both the dispatch
   path and validate-on-set, so a value is judged identically everywhere.
3. ✅ **Secrets SPI** — `SecretBackend` interface (`group/spec/secret/`) + built-in `env://` and
   `file://` backends behind a `SecretResolver` registry (extensible: further backends — Vault, cloud
   secret managers — register through the same SPI). A `SECRET` variable's value is a `scheme://ref`
   reference (or an inline literal); the reference lives in the group config / composition plan / audit,
   and is resolved to plaintext **only at dispatch**, last-moment, by `GroupVariableResolver`
   `.resolveForDispatch` into the transient `StartInstance.resolved_variables` proto alone — never into
   a persisted plan, snapshot, or audit record, and never logged (only counts + key-named errors are).
   Validate-on-set stays secret-free (references are not fetched at set time, so setting
   `env://RCON_PASSWORD` never requires the var to exist on the controller yet). An unresolvable secret
   is dropped and recorded as an error rather than wedging the start — consistent with the rest of
   resolution. An unregistered scheme is a hard error (a typo'd backend never ships a bogus secret).
4. ✅ **CLI + REST surface** — `prexorctl template var list/set/rm` (typed flags) + group value set; REST
   GET/PUT typed defs with 422 validation.
5. ✅ **Dashboard UI** — typed `VariableEditor` (type/default/required/scope/visibility/per-type
   validation + SECRET masking) on the template panel; per-group key→value override editor on the group
   detail page; controller 422 messages surfaced to the operator.

### Phase 3 (P1) — Config model v2 ✅ (shipped 2026-06-26, `e2ac967`+`7b1d2bd`)
Data-driven parser rules; platform-as-data; deprecate hardcoded `ServerConfigPatcher`.
- ✅ `ConfigRule` (file/format/path/`op`{SET,REPLACE,REGEX}/value) + `ConfigRuleResolver` (collapses the template chain's parser rules + group `configPatches` into one ordered set — SET last-wins per (file,path), REGEX/REPLACE order-preserved) + `ConfigRuleValidator`. `PlatformConfigDefaults` holds the per-format base-platform rules (paper velocity-secret, spigot bungeecord) **as data**.
- ✅ Daemon `ServerConfigPatcher` rewritten as a **platform-agnostic (op,file,path,value) rule engine** — the per-platform `patchPaper/Spigot/Bungeecord/Geyser` methods are gone; Paper's cache-time velocity baking moved into `PaperBootstrapCache`; `%FORWARDING_SECRET%` resolved daemon-side from `forwarding.secret` (off-wire).
- ✅ Per-instance scalars (port/max-players/MOTD) ride the shipped files' `%VAR%` placeholders (MOTD now a `%MOTD%` resolved variable), retiring the redundant `autoConfigPatches` (its bungeecord branch had been writing junk top-level keys).
- ✅ proto `ConfigPatch` gains an additive `op` field (+ `ConfigPatchOp` enum); `java/cloud-protocol/contracts/proto-contracts.sha256` updated (gate = `:cloud-protocol:test`/`ProtoContractDriftTest`, **not** the repo-root copy); **planHash golden re-pinned** `5996131c…`→`9dea0a52…` (deliberate fleet re-roll). ADR 35 has a Phase-3 note. Controller+daemon+protocol suites green.
- ✅ Live-validated 2026-06-26: the new `ServerConfigPatcher` ran against the real fleet `paper-global.yml` (secret nesting + `proxies.velocity` vs `proxies.bungee-cord` dotted-YAML disambiguation correct); all 3 controllers + node-fra-2 rolled to the new code.

### Phase 4 (P1) — Storage v2
Chunked CAS + dedup + delta sync + S3 backend + template signing. Additive proto fields only.

### Phase 5 (P1) — Rollout v2
CANARY + auto-rollback; fix the timeout footgun; deploy-back; rollback UX. **In progress.**
- ✅ **Replacement-stall footgun fixed** (`e85b81b`). `DeploymentReconciler.waitForReplacement` no longer waits `evaluationIntervalSeconds*2` and then "continues anyway" — it returns READY/RETRY/FAILED, and a crashed replacement (now caught even with the health gate off) or one that isn't scheduled within the timeout **halts** the rollout (FAILED, or ROLLED_BACK when auto-rollback is set) instead of stopping more instances into an outage. The timeout is seeded from the group's `startupTimeoutSeconds` (carried in the deployment config snapshot via a new `replacementTimeoutSeconds`), so a slow-but-healthy boot isn't mistaken for a failure; old snapshots fall back to the interval default. Tests added / realism-fixed in `DeploymentReconcilerTest`.
- 🐛 **Finding — rollback is cosmetic.** Both the manual `POST …/deployments/{rev}/rollback` and the reconciler's auto-rollback only **relabel** the record (`updateDeploymentState(…, "ROLLED_BACK")`); neither reverts the group's templates/config nor re-deploys the previous revision, and `rollbackOf` is never populated. So "auto-rollback on failure" doesn't roll anything back — the bad config stays live and crashed instances keep being replaced with it.
- **Next — Inc 2: make rollback real.** Snapshot the full `GroupConfig` per deployment; have rollback (manual + auto) restore the previous good snapshot + re-deploy (rollbackOf linked). Then Inc 3: CANARY as a first-class strategy (bake one, observe crash + TPS regression via `InstanceInfo.tps1m`, then proceed or auto-rollback). Then Inc 4: rollback/pause/resume UX (CLI/Dashboard) + deploy-back.

### Phase 6 (P2) — UX parity + observability
Dashboard group-edit, variable UI, version diff, CLI template file-ops, per-group scaling metrics + "why (not) scaled" explainability.
- ✅ **Placement explainability** (2026-06-26): `NodeSelector.explainIneligibility(request, nodes)` → per-node reason (not-ONLINE / insufficient memory / no free port in range / missing-affinity / anti-affinity), and the `No eligible node available for group X` log now lists the per-node reasons — turning an opaque scheduling stall into an actionable diagnostic. `WeightedNodeSelector`'s reasons mirror `isEligible` check-for-check (shared `ineligibilityReason`, so diagnostics can't drift from the decision). Tests in `WeightedNodeSelectorTest`. Motivated by a live node-fra-2 warm-placement stall whose cause the bare log didn't reveal.
- ✅ **Scaling-decision explainability** (2026-06-26): `ScalingEvaluator.evaluateScaleUpDecision(group)` returns a `ScaleUpDecision(count, reason)` — every hold/scale branch carries its cause (manual / below-min / static / at-max / cooldown / load-below-target-and-no-TPS-degradation / load≥target / TPS-below-floor) instead of a bare `0`, so a group that stops scaling is self-explaining and the reason is a ready seam for a "why (not) scaled" status surface. `evaluateScaleUp` stays an `int` wrapper (no caller ripple) and logs the reason on a scale-up. Tests in `ScalingEvaluatorTest`.

## Risks & gates

- **`planHash` drift** invalidates every daemon cache and re-rolls the fleet. Gate every phase on the golden `planHash` tests from Phase 0.
- **cloud-api / plugin contract breakage** silently breaks third-party modules and proxy routing. Additive-only; contract tests in Phase 0.
- **Migration** is a hard release gate (round-2 audit): a tested v1→v2 migrator or documented wipe-and-rebuild + refuse-to-start, before anything merges to `main`.
- **Proto discipline**: additive `oneof`/optional only; never bump `PROTOCOL_VERSION` for new template/scaling fields; update `contracts/proto-contracts.sha256`.

## Why not a from-scratch rewrite

See [ADR 35](decisions.md#adr-35-group--template-v2-targeted-re-architecture-not-a-rewrite). Short version: the expensive, correctness-critical machinery (content-addressed versioning, `planHash` idempotency, Mongo single-writer + leases + change streams, inheritance resolution, weighted placement, dependency tiers, crash-loop) is proven on the live fleet across many sessions. A from-scratch rewrite re-risks all of it to gain nothing those layers are responsible for. The actual deficits live in four replaceable peripheral layers. We rebuild those — substantially, on clean v2 contracts — and keep the engine.
