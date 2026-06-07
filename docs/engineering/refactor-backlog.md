# Refactor backlog + multi-session handoff

> Snapshot of what's in the working tree (uncommitted) and what's left to do, written at the end of a long refactor + cleanup run. Read this when you come back to the repo and want to know "where was I."

## Working-tree state right now

Single commit on `master`. Roughly 280+ modified files in the working tree, none yet committed. Everything in the tree compiles + tests green:

- Java: `./gradlew :cloud-controller:test` (119 test classes) green, `./gradlew :cloud-daemon:test` green.
- Go: `cd cli && make check` (vet + fmt-check + 242 tests) green.
- Astro: `cd website && pnpm build` produces 287 pages.
- Dashboard: pre-existing typecheck failures in unrelated files (MotdEditor, DiffViewer, CodeEditor); the auth-store change is clean.

## Suggested commit boundaries

Seven roughly-independent commits in this order:

1. **`ci: workflows track master + main; drift detectors; OpenAPI validator`**
   - `.github/workflows/ci.yml`, `website.yml` accept both branch names
   - new `drift-check` job (CLI + gRPC + OpenAPI route check)
   - `scripts/check-openapi-routes.sh` (Python AST-ish walker)
   - dashboard voice-lint job, Lighthouse + axe-core jobs

2. **`docs: reorg loose .md into engineering/; new README; ADR 25 + 26`**
   - move 14 loose `docs/*.md` → `docs/engineering/`
   - delete empty `docs/adr/`
   - new `docs/README.md` (three-tier layout: engineering / public / runbooks)
   - ADR 5 amended with Velocity Guice exception
   - ADR 25 (Java toolchain split), ADR 26 (`_generated/` policy)
   - `CONTRIBUTING.md` updated with the conventions

3. **`chore: purge stale binary blobs; tighten .gitignore`**
   - delete `dashboard/app.zip`, `cli/prexorctl.exe`, `design-system/*.zip`
   - `.gitignore` adds the patterns + test-harness snapshot dirs

4. **`cli: typed exits, HTTP retries, cosign verify, role CRUD, Makefile`**
   - `ExitCodeError` typed error + root dispatcher
   - jittered exponential backoff in `internal/api/client.go`
   - `install.sh` cosign verify-blob + Rekor
   - `internal/setup/cosign.go` + `DownloadAndVerify`
   - full `role` CRUD (show / create / update / delete)
   - `make fmt`, `make race`, `make cover` targets
   - `module new` wizard prompts labelled `[scaffold limitation]`

5. **`dashboard: cross-tab logout + server revocation`**
   - `app/stores/auth.ts` calls `POST /auth/logout`
   - `BroadcastChannel('prexor-auth')` listener

6. **`controller: build-logic version catalog; URL constants; refactors`**
   - `build-logic/build.gradle.kts` reads `libs.versions.toml`
   - URL constants in `Deployment/Backup/Event/PlayerJourney/Instance/System/Module` routes for the validator
   - `ModuleRoutes` → extracted `module/compat/` (5 files)
   - `TemplateRoutes` → extracted `template/ops/` (2 files)
   - `Scheduler` → `LeaseGate` interface + `StartRetryOrchestrator`
   - `Bootstrap` → `wireRedisEventBridge()` + `registerShutdownHooks()` helpers

7. **`daemon: extract ArtifactProvisioner + prep/ package; new tests`**
   - `process/prep/` package: `ResolvedStartSpec`, `ResolvedExtensionSpec`,
     `StartPreparationException`, `PreparationOperation`, `PreparationOps`,
     `ArtifactProvisioner`
   - `ProcessManager` 1256 → 991 LOC (six methods + static helpers extracted)
   - 5 new `ProcessManagerTest` cases covering each `StartPreparationStage` failure path

Each is reviewable in isolation; nothing depends on a later commit to compile. Pick whatever order makes review easiest.

---

## Refactor backlog — picked up + still pending

### Giant-file targets

| File | Original | Current | Δ | Status |
|---|---|---|---|---|
| `ModuleRoutes.java` | 1203 | 869 | −334 | ✅ done — extracted `module/compat/` |
| `TemplateRoutes.java` | 897 | 805 | −92 | ✅ done — extracted `template/ops/` |
| `Scheduler.java` | 846 | **622** | −224 | ✅ done — `LeaseGate` seam + `StartRetryOrchestrator` + `RecoveryOrchestrator` |
| `PrexorCloudBootstrap.java` | 988 | 1015 | +27 | ✅ done — `start()` 120→70; rest is composition-root, allowed |
| `ProcessManager.java` | 1256 | **783** | −473 | ✅ done — `ArtifactProvisioner` + `TemplatePreparation` + `WorkspaceManager` extracted; preparation logic now lives in 3 collaborators |
| `DaemonServiceImpl.java` | 1020 | **448** | −572 | ✅ done — 5 helpers extracted (`CommandAckHandler`, `CrashEventReceiver`, `CacheStatusReceiver`, `TemplateRequestHandler`, `ConnectionLifecycle`) |

### ProcessManager — preparation extractions complete

All three preparation collaborators have shipped (`ArtifactProvisioner`, `TemplatePreparation`, `WorkspaceManager`). ProcessManager is down from 1256 → 783 LOC. ProcessManagerTest still calls `ProcessManager.replaceDirectory / deleteDirectoryTree / copyDirectoryTree / stageStaticWorkspace` — those are thin delegators that forward to `WorkspaceManager`.

Natural follow-up if further shrinkage is wanted: extract a `ProcessLifecycleHandler` covering process spawn + cleanup (the `start/stop/onProcessExited/deleteInstanceDir` cluster). Not as cohesive as the prep collaborators — defer unless the file grows further.

### Scheduler — fully extracted

`RecoveryOrchestrator` landed this session: the three `reconcileRecoverable*` methods + the `pendingStartRecoveryBackoffUntil` backoff map + `isRecoverableStartState` moved into a sibling of `StartRetryOrchestrator`. Scheduler keeps thin delegators on `reconcileRecoverableStarts` + `reconcileRecoverableStartsForNode` for `DaemonServiceImpl` and `SchedulerStartRetryTest`. Scheduler is now 622 LOC.

### DaemonServiceImpl — done

1020 → **448 LOC** (−572). Five package-private helpers in `grpc/` now own most of the inner stream handler:

- `DaemonCommandAckHandler` — start/stop/shutdown acks. `ClusterState` + `Supplier<Scheduler>`.
- `DaemonCrashEventReceiver` — crash + error reports. `ClusterState`/`CrashStore`/`CrashLoopDetector`/`EventBus`.
- `DaemonCacheStatusReceiver` — node cache status. `ClusterState`.
- `DaemonTemplateRequestHandler` — template fetch (virtual thread). `TemplateManager` + `TemplateMerger`.
- `DaemonConnectionLifecycle` — handshake + cleanup + Redis ownership maintenance + reconcile + pre-warm cache hints. Returns a `HandshakeResult(sessionId, nodeId)` so the caller's `StreamObserver` keeps owning the per-stream state.

What stays inline in the anonymous `StreamObserver<DaemonMessage>`: the three per-stream fields (`sessionId`, `nodeId`, `handshakeComplete`), the `daemonFields()` logging helper, the small handlers `handleNodeStatus`/`handleInstanceStatus`/`handleConsoleOutput`/`handlePong`/`handleDaemonLogRecord`/`handleModuleStateUpdate`/`handleEventSubscribe`/`handleEventUnsubscribe`, and the local `verifyNodeOwnership` (used by the two `InstanceStatus`/`ConsoleOutput` handlers). Three duplicate copies of the 6-line `verifyNodeOwnership` predicate now exist (anonymous class + `DaemonCommandAckHandler` + `DaemonCrashEventReceiver` + private one inside `DaemonConnectionLifecycle`); promote to a shared `NodeOwnership.verify(...)` helper if/when a 5th caller appears or any of them grows.

**Pattern established for similar tear-downs:** top-level package-private class in the same package, named `Daemon<Cluster>Handler|Receiver|Lifecycle`, ctor takes only the collaborators the cluster touches, `Supplier<T>` for any field assigned via `attach*()` (late binding). Handlers take `String nodeId` as the first parameter rather than reading shared state. State-writing handlers return a result record; the anonymous class assigns it to its own fields. Static helpers used by the extracted classes (`extractPeerAddress()`, `parseConfigFormat()`) move to package-private on `DaemonServiceImpl`.

---

## Strategic items (multi-day, design-passes recommended)

### `prexorctl context` (multi-cluster, kubeconfig-style) — **done 2026-05-13**
- ADR 27 in [`decisions.md`](decisions.md) settled the four design questions.
- Shipped: `Config{CurrentContext, Contexts, Accent}` data model with lazy migration from the v1 flat shape (any pre-context `~/.prexorcloud/config.yml` is rewritten on next save). `Context{Controller, Token}` per entry.
- Resolution wired through `cfg.Resolve(flagController, flagContext)` / `cfg.ResolveToken(flagToken, flagContext)`. Global `--context` flag on root; honors `PREXOR_CONTEXT`. All call sites in `cli/cmd/` threaded.
- `prexorctl context list|current|use|add|remove` shipped (aliases: `ls`, `rm`, `delete`). `prexorctl login` writes into the active context via `cfg.SetCurrentAuth()`; `prexorctl logout` clears only the active context's token. `prexorctl config set/unset` now operates on the active context (also gained `accent` as a valid key).
- Tests in `cli/internal/config/config_test.go` cover migration, round-trip, every priority slot in `Resolve`/`ResolveToken`, `Use`, `Remove`, and `SetCurrentAuth`. CLI doc tree regenerated (86 files; 6 new under `context-*.md`).

### Dashboard test coverage 26 % → 60 %+
- Mechanical work. Per-page tests for: `login.vue`, `groups/[name].vue`, `instances/[id].vue`, `templates/[name].vue`, `nodes/[id].vue`.
- Form-submission tests for: `CreateGroupDialog`, `EditTemplateDialog`, `UploadFilesDialog`.
- SSE edge cases for `useSseEventBus`: malformed JSON, network timeout mid-stream, reconnection.
- ~2 days; parallelisable across multiple sessions.

### `javalin-openapi-plugin` cutover — **done 2026-05-12**
- `io.javalin.community.openapi:javalin-openapi-plugin` (6.7.0-1) + its
  annotation processor wired into `cloud-controller/build.gradle.kts`.
- All 28 route files now expose named static handlers carrying
  `@OpenApi(...)`. The generated spec is published to `docs/openapi.json`
  via the `:cloud-controller:syncOpenApi` Gradle task (finalizedBy
  `compileJava`), 155 paths / 179 operations / 73 schemas.
- `docs/openapi.yaml` and `scripts/check-openapi-routes.sh` deleted —
  the build artefact is now the source of truth. Website (Astro
  starlight-openapi + Scalar) reads `docs/openapi.json`.
- Follow-ups, none blocking: form-upload field schemas (avatar, template
  upload) stay schema-less in the spec because the plugin's
  `@OpenApiFormParam` surface didn't accept a stable `name` argument
  shape on 6.7.0-1; runtime-boot smoke test of the live `/openapi` and
  `/swagger` endpoints once a controller comes up against this build.

### Module hot-reload (Layer 8 follow-up) — DONE
- Shipped per ADR 28. `ModuleLifecycleManager` now has a `RELOADING` state and
  an `ACTIVE → RELOADING → ACTIVE` fast path (`reload()`), beside `upgrade()`.
- Opt-in via the manifestVersion-2 `backend.controller.reloadable` flag, gated
  on an identical capability declaration (`reloadCompatible()`); new
  `PlatformModule.onReload` hook; `PlatformModuleManager.install()` picks the
  fast path when the previous module is `ACTIVE` and the gate passes.

### `lipgloss/v2` migration
- CLI imports both `lipgloss` v1.1.0 and v2.0.0-beta.2 simultaneously.
- Wait for v2 stable, then migrate v1 imports.
- Passive; no work until upstream stabilises.

---

## Patterns established (read before extracting more)

Every refactor in this run followed the same recipe. If you're picking up `TemplatePreparation` or `WorkspaceManager` or anything similar:

1. **Identify the cohesive concern.** Not "what's big," but "what shares dependencies + invariants." E.g. start-retry shared a state map + the lease abstraction.
2. **Formalise the seam.** Introduce an interface (`LeaseGate`) or a public type (`ResolvedStartSpec`, `PlatformCompatibilityReport`) so the collaborator depends on a contract, not a back-reference to the source class.
3. **Move types first, methods second.** Records + exceptions are cheap to relocate and unblock the rest.
4. **Static-import shared helpers** rather than passing them as constructor args. Less wiring, same effect.
5. **Run the existing test suite as the gate.** Each refactor in this run had a dedicated test file (`SchedulerStartRetryTest`, `ProcessManagerTest`) that locked in the behaviour. Run it after every move; if it fails, the move was wrong.
6. **Keep thin public delegators** for any method tests reach in by name. Don't perturb the test surface during structural refactors.
7. **Commit per extraction** — each collaborator should be one atomic commit, reviewable on its own.

The five extractions in this run (`module/compat/`, `template/ops/`, `LeaseGate + StartRetryOrchestrator`, `process/prep/` types, `ArtifactProvisioner`) all fit this recipe. Total: ~−824 LOC of business logic out of the giant files, into 14 new purpose-built sibling-package files.

---

## Quick context recovery

When you come back, run these in order to land on green:

```bash
cd /home/prexorjustin/dev/me/prexorjustin/prexorcloud
git status                                           # see the working tree (~280 files)
cd java && ./gradlew :cloud-controller:test          # 119 controller test classes
                ./gradlew :cloud-daemon:test         # daemon suite incl. 14 ProcessManager tests
cd ../cli && make check                              # vet + fmt-check + 242 Go tests
cd ../website && pnpm build                          # 287 Astro pages
```

All four should be `BUILD SUCCESSFUL` / `0 failures` / `Complete!`.

For the next refactor session, the natural pickup is the daemon: open `ProcessManager.java` and `applyTemplates` / `applyVariableSubstitution` / `patchConfigs`. Recipe above applies verbatim.
