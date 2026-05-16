# Paste Share — pste integration for PrexorCloud

> Engineering plan. Status: **shipped in v1.0 (2026-05-05).**
> Companion runbook for verification: `LIVE_CLUSTER_GUIDE.md`.

## Context

PrexorCloud has solid *internal* observability but no easy way to get a **text
artifact off the cluster** for support — today that means emailing a tarball
or pasting JSON by hand.

The goal is a **support workflow**: an operator runs `prexorctl … --share`,
gets back a link, and sends it to the maintainer. The target is **pste.dev**
(the maintainer's own pastebin) — a maintainer-controlled, known-good endpoint
is more reliable for "send me a link" support than a user's possibly-broken
self-hosted instance.

For this to be *useful*, `prexorctl diagnostics bundle` is also expanded to
collect **everything that helps debug a user's problem** — full nodes,
instances, groups, templates, and the **filetree of every instance** — emitted
as one redacted **text** file (pste is text-only; a `.tar.gz` can't go there).

**On the 5 MB pste limit:** it is *not* too low — it's generous. The only thing
that can blow it is enumerating a Minecraft `world/region/` folder (30k+ files)
line by line. The fix is **not a bigger limit** — it's having the daemon
**summarize high-child-count directories** ("`world/region/` — 31,204 entries,
38.2 GB" as one line), which is also *more* useful for debugging. With
structure-only filetrees + directory summarization + entry/depth caps, 5 MB is
comfortable; `ShareService` still enforces a ~4 MB ceiling with a truncation
marker for headroom.

**Honest guardrails:**
- pste.dev is the **default**, not the **only** target — `share.pasteUrl` is
  configurable and the feature is disable-able.
- **Every send is an explicit, user-invoked command that prints the link** —
  never silent, never automatic.
- Only **text** artifacts; the diagnostics bundle is shared as a redacted,
  concatenated text document.
- Redaction is mandatory and server-side — logs / crash logTails / the
  diagnostics `logs.txt` are not redacted today.

## Decisions locked

| Decision | Choice |
|---|---|
| Scope | Text only — crash reports, log tails, and an **expanded** diagnostics bundle. |
| Diagnostics collection | Expand to full nodes + instances + groups (resolved configs) + templates list + templates filetree + **per-instance filetree** (structure only: path/size/isDir/mtime — no file contents; high-child-count dirs summarized). |
| Instance filetrees | Two **additive `oneof` variants** on the existing daemon bidi stream — **not** a new RPC. No `PROTOCOL_VERSION` bump. |
| Architecture | Controller-mediated. CLI + dashboard call controller endpoints; the controller holds the pste client, config, and the single redaction path. No Go pste client. |
| Surface | Uniform `--share` flag — `prexorctl crash info <id> --share`, `prexorctl logs controller\|daemon --share`, `prexorctl diagnostics bundle --share` — plus dashboard "Share" buttons. |
| Target | Ships `share.pasteUrl=https://pste.dev`, enabled. Repointable / disable-able. |
| Size | pste limit is 5 MB. `ShareService` ceiling ~4 MB; daemon walker caps `max_entries` (~5000), `max_depth` (~24), `summarize_threshold` (~500). |
| Redaction | Mandatory, unconditional, server-side. Default paste = private + short expiry. |
| pste auth | Anonymous works; optional `pasteToken` for higher rate limit. |

## Verified codebase facts

- **Daemon protocol is a single bidi stream** — `DaemonService.Connect(stream
  DaemonMessage) returns (stream ControllerMessage)` in
  `java/cloud-protocol/src/main/proto/prexorcloud/daemon_service.proto`. The
  controller has **no outbound daemon client**; daemons connect inbound and the
  controller holds a `StreamObserver<ControllerMessage>` per node. Communication
  is via the two `oneof`s: `DaemonMessage` (tags 1–16 used → **next = 17**),
  `ControllerMessage` (tags 1–14 used → **next = 15**). Additive `oneof`
  variants do **not** bump `PROTOCOL_VERSION` (`ProtocolConstants.java`); must
  recompute the `daemon_service.proto` hash in `contracts/proto-contracts.sha256`.
- **No async-correlation infrastructure exists** — every controller→daemon
  round-trip today (`StartInstance`→`StartInstanceAck`, `RequestCacheStatus`→
  `CacheStatus`) is fire-and-forget, correlated implicitly by instance id. A
  blocking "send + await reply" needs a new minimal `PendingRequestRegistry`.
- Controller→daemon send path: `NodeMessageDispatcher.dispatch(nodeId, ControllerMessage)`
  → looks up the `NodeSession` in `NodeSessionManager`, `session.send(...)`;
  returns `boolean delivered`; already handles the multi-controller HA Redis route.
- Daemon receive: `daemon/grpc/MessageDispatcher.java` switches on the
  `ControllerMessage` variant. Controller receive: `controller/grpc/DaemonServiceImpl.java`
  (`Connect`), with a `Deps` record bundling `NodeSessionManager`, `ClusterState`, etc.
- Instance working dirs: `instancesDir/<group>/<instanceId>` on the daemon;
  `ProcessManager` holds `Path instancesDir` (private — needs a getter). Path-
  traversal guard pattern: `normalize().startsWith(instancesDir.normalize())`.
- **Diagnostics is assembled inline** in `SystemRoutes.java:269-313` (`getDiagnostics`),
  building a `Map.of(...)` with **exactly 10 pairs — at `Map.of`'s ceiling**.
  Adding 5 sections needs `Map.ofEntries` / a builder. Permission gate:
  `Permission.SYSTEM_SETTINGS`. Confirmed accessors for the new sections:
  `controller.clusterState().getAllNodes()` / `.getAllInstances()`,
  `controller.groupManager().getAll()` / `.resolveGroup(name)`,
  `controller.templateManager().getAll()` / `.getTemplateFilesDir(name)` /
  `.listSnapshotFiles(name, hash, subPath)`.
- `/api/v1/system/settings` → `SystemDtoMapper.settingsDto(...)`
  (`SystemRoutes.java:134-143`) — extend the signature with `boolean shareEnabled`.
- `RestServer.java:274-294` registers exception handlers in the `app.create()`
  config block (`NotFoundException`→404, `IllegalArgumentException`→422,
  `IllegalStateException`→409, `Exception`→500); `errorResponse(code, message,
  status)` util at ~line 470. Add `ShareNotConfiguredException`→409,
  `PasteException`→502.
- `ControllerConfig` — record with a **19-arg canonical** ctor + a **17-arg
  convenience** ctor; new field threads through **both** + compact-ctor default.
  Fan-out: `PrexorCloudBootstrap`, harness `TestCluster`/`ModuleTestHarness`,
  tests `ConfigValidatorTest`/`BackupScopeTest`/`ControllerConfigRedactorTest`.
- `ControllerConfigRedactor` exposes `public static String redactUriUserinfo(String)`
  + a `REDACTED` constant — `LogRedactor` **composes with** these.
- `prexorctl diagnostics bundle` (`cli/cmd/diagnostics.go`) fetches
  `GET /api/v1/system/diagnostics` + optionally `GET /api/v1/system/logs`, and
  assembles a `.tar.gz` **client-side**. `config.json` is server-redacted;
  **`logs.txt` is raw**.
- **pste contract** (`POST /api/v1/paste`): raw body; headers `Authorization:
  Bearer <token>` (optional), `x-expiry` (**preset keys** — `1h`/`1d`/`30d`/
  `never`, invalid → 400), `x-language`, `x-burn-after-read` (`1`/`true`),
  `x-idempotency-key`. Success **201** → `{ id, url, rawUrl, deleteToken,
  expiresAt, language, burnAfterRead, e2e }`. Errors: 400 / 401 / 409
  (idempotency) / **413** (oversize) / **429** (`Retry-After`) / 451 (abuse).
- Canonical OpenAPI spec is **`docs/openapi.json`** (`@OpenApi`-generated); the
  api-sdk `generate` script still points at the deleted `docs/openapi.yaml`.
- Project conventions (`CONTRIBUTING.md`): Jackson only, SLF4J only, constructor
  injection only, respect module boundaries. Controller + daemon are Java 25
  `--enable-preview`.

## MVP scope

`--share` on crash detail, controller logs, daemon logs, and the **expanded**
diagnostics bundle. Fast-follows: instance-console share, paste deletion, e2e
pastes (`x-e2e`), file *contents* in filetrees, a dedicated `SHARE` permission,
cross-controller filetree reply routing, a `ControllerConfig` builder.

---

## Phase 1 — Controller foundation

**New files** under `java/cloud-controller/src/main/java/.../controller/`:

- `config/ShareConfig.java` — record `{ enabled, pasteUrl, pasteToken?,
  defaultExpiry, defaultPrivate, defaultBurnAfterRead }`. Defaults: `enabled=true`,
  `pasteUrl="https://pste.dev"`, `defaultExpiry="1d"` (a valid pste preset key),
  `defaultPrivate=true`, `defaultBurnAfterRead=false`. Modeled on `CrashConfig`.
- `share/PasteClient.java` — Java `HttpClient` + Jackson. `PasteResult
  create(String text, PasteOptions)`: `POST {pasteUrl}/api/v1/paste`, raw text
  body; headers `Authorization: Bearer <pasteToken>` (when set), `x-expiry`
  (preset key), `x-language: text`, `x-burn-after-read` (when set),
  `x-idempotency-key` (fresh UUID per share — prevents dup on retry). 201 →
  parse `{ id, url, rawUrl, deleteToken, expiresAt, … }` → `PasteResult`;
  413/429/400/401/451/timeout → `PasteException` with a clear message (429
  surfaces `Retry-After`). `HttpClient` is the mockable boundary.
- `observability/LogRedactor.java` — text-line scrubber, sibling to
  `ControllerConfigRedactor`, same package + `REDACTED` convention. Static
  `redactLine`/`redactLines`. Ordered, javadoc-documented catalogue:
  auth/bearer headers; JWT-like tokens (`eyJ…\.…\.…`); URI userinfo passwords
  (delegates to `ControllerConfigRedactor.redactUriUserinfo`); IPv4/IPv6;
  `password=|secret=|token=|apikey=` k/v pairs. Conservative — must not mangle
  normal stack traces / timestamps / IDs / file paths.
- `share/ShareService.java` — ctor-injected `ShareConfig` + `PasteClient` + clock:
  - `shareCrash(CrashRecord, ShareRequest)` — crash fields + **redacted** `logTail`.
  - `shareLogText(title, lines, ShareRequest)` — redacts every line.
  - `shareDiagnostics(DiagnosticsSnapshot, ShareRequest)` — concatenates all
    sections into one text doc with headers; `redactedConfig` already safe, log
    + path/host-bearing sections run through `LogRedactor`; enforces the ~4 MB
    ceiling with a `[truncated — …]` marker.
  Guard: `!enabled` → `ShareNotConfiguredException`. Redaction unconditional.

**Modified:**
- `config/ControllerConfig.java` — add `@JsonProperty("share") ShareConfig share`
  after `backup`; null-default in the compact ctor; thread through the 17-arg
  ctor + ~6 call sites. **Land this fan-out first, compiling green.**
- `PrexorController` — build `ShareService` once at bootstrap, expose
  `shareService()`.

## Phase 2 — Daemon instance-filetree round-trip

**Proto** (`java/cloud-protocol/src/main/proto/prexorcloud/daemon_service.proto`):
- `ControllerMessage` oneof += `WalkInstanceFiles walk_instance_files = 15;`
- `DaemonMessage` oneof += `InstanceFileTree instance_file_tree = 17;`
- New messages: `WalkInstanceFiles { string request_id; string group; string
  instance_id; int32 max_entries; int32 max_depth; int32 summarize_threshold; }`,
  `FileEntry { string path; int64 size_bytes; bool is_dir; int64 modified_at_ms;
  bool summary; int32 child_count; }`, `InstanceFileTree { string request_id;
  repeated FileEntry entries; bool truncated; string error; }`.
- Additive → **`PROTOCOL_VERSION` unchanged**. Regenerate via
  `./gradlew :cloud-protocol:build`; recompute the `daemon_service.proto` line
  in `contracts/proto-contracts.sha256`.

**Controller — async correlation (new):**
- `controller/grpc/PendingRequestRegistry.java` — generic thread-safe map of
  `request_id → CompletableFuture` with a scheduled timeout, plus
  `failAll(predicate, cause)`. Added to `DaemonServiceImpl.Deps`, constructed in
  `PrexorCloudBootstrap`. `complete(id, resp)` is a no-op for unknown/expired ids.
- `controller/grpc/DaemonFileTreeReceiver.java` — mirrors `DaemonCacheStatusReceiver`;
  validates the message and calls `registry.complete(requestId, msg)`. Wire a
  `case INSTANCE_FILE_TREE -> …` into `DaemonServiceImpl.connect(...)`'s switch.
- `DaemonConnectionLifecycle.cleanup(...)` — on disconnect, `pendingRequests.failAll`
  for that node so in-flight walks fail fast instead of waiting the full timeout.
- `controller/diagnostics/InstanceFileTreeService.java` +
  `InstanceFileTreeResult.java` — `walkInstanceFiles(nodeId, group, instanceId)`:
  register a future, `NodeMessageDispatcher.dispatch(...)`, await with a ~20 s
  timeout. **Never throws** — unreachable node / timeout / daemon `error` →
  an "unavailable" marker the snapshot embeds. Known limitation (fast-follow):
  in multi-controller HA the reply lands on whichever controller owns the
  session; a cross-controller request degrades to "unavailable".

**Daemon:**
- `ProcessManager` — add `public Path instancesDir()` getter.
- `daemon/process/InstanceFileTreeWalker.java` — resolves
  `instancesDir/<group>/<instanceId>` with the path-traversal guard +
  `InputValidator.requireSafeName`; manual recursive walk (not `Files.walk`) so
  depth/child-count are controllable; `readAttributes(..., NOFOLLOW_LINKS)` —
  symlinks recorded as leaves, never followed; **summarizes dirs with
  > `summarize_threshold` children** into one `summary=true` entry; enforces
  `max_entries`/`max_depth` → `truncated=true`; skips unreadable entries;
  unreadable root → `error="DIR_UNREADABLE"`, missing → `error="INSTANCE_NOT_FOUND"`.
- `daemon/grpc/MessageDispatcher.java` — `case WALK_INSTANCE_FILES ->
  handleWalkInstanceFiles(...)`, runs the walk on a virtual thread (like
  `handleRequestCacheStatus`), replies via `DaemonGrpcClient`.

## Phase 3 — Expand diagnostics collection

Extract the inline `getDiagnostics` body into a
`controller/diagnostics/DiagnosticsCollector.java` producing a
`DiagnosticsSnapshot` — consumed by **both** the existing `GET /system/diagnostics`
route and the new diagnostics-share endpoint (avoids `Map.of`'s 10-pair limit;
use `Map.ofEntries`/a builder). Keep all current keys; add:
- `nodes` — `controller.clusterState().getAllNodes()`.
- `instances` — `controller.clusterState().getAllInstances()`.
- `groups` — `controller.groupManager().getAll()` + `.resolveGroup(name)`.
- `templates` — `controller.templateManager().getAll()` + templates filetree
  via `getTemplateFilesDir` / `listSnapshotFiles`.
- `instanceFiles` — per-instance filetree via `InstanceFileTreeService`
  (Phase 2), keyed by instance id; best-effort, "unavailable" markers inline.
Apply collection-time caps (log-line cap, filetree caps). The expanded snapshot
flows to both the `prexorctl diagnostics bundle` `.tar.gz` (new entries) and the
share endpoint.

## Phase 4 — Controller REST share endpoints

**New DTOs** (`rest/dto/`): `ShareRequestDto { expiry?, private?, burnAfterRead? }`,
`ShareLogRequestDto { level?, logger?, limit?, …share fields }`,
`ShareResultDto { url, rawUrl, expiresAt, private, burnAfterRead }` (no
`deleteToken` — fast-follow). New exceptions `ShareNotConfiguredException`,
`PasteException` alongside `NotFoundException`.

**Modified routes** (each: `@OpenApi`, perm gate, `controller.shareService()`,
201/401/403/[404]/409/502):
- `CrashRoutes` — `POST /api/v1/crashes/{id}/share`, perm `CRASHES_VIEW`,
  `requireFound` the crash → `shareCrash`. opId `shareCrash`.
- `SystemRoutes` — `POST /api/v1/system/logs/share`, perm `SYSTEM_LOGS_VIEW`,
  reuse `getSystemLogs` filter logic → `shareLogText`. opId `shareControllerLogs`.
  **Also** `POST /api/v1/system/diagnostics/share`, perm `SYSTEM_SETTINGS`,
  builds the `DiagnosticsSnapshot` → `shareDiagnostics`. opId `shareDiagnostics`.
- `DaemonLogRoutes` — `POST /api/v1/nodes/{id}/logs/share`. opId `shareDaemonLogs`.
- `RestServer` — register `ShareNotConfiguredException`→409 (hint body) and
  `PasteException`→502 in the `app.create()` exception block (before the
  generic `Exception` handler), using `errorResponse(...)`.
- `SystemDtoMapper.settingsDto(...)` — add a `boolean shareEnabled` param +
  field; `getSystemSettings` passes `controller.config().share().enabled()`.

## Phase 5 — CLI (thin callers)

Uniform `--share` flag (+ `--expiry`, `--public`, `--burn-after-read`); the CLI
never fetches-then-uploads — the controller slices/redacts/shares. Print the
link via `theme`/`tui`; honor `--json`; 409 → friendly "sharing not configured",
502 → "paste service unreachable".
- `cli/cmd/crash.go` — `--share` on `crash info <id>` → `POST /api/v1/crashes/{id}/share`.
- `cli/cmd/logs.go` — `--share` on `logs controller` / `logs daemon`; error if
  combined with `--follow`.
- `cli/cmd/diagnostics.go` — `--share` on `diagnostics bundle` → `POST
  /api/v1/system/diagnostics/share`, prints the link. Without `--share`,
  unchanged (local `.tar.gz`, now with the expanded sections). `--share` +
  `--out` → share *and* keep the local copy.
- Regenerate CLI reference docs.

## Phase 6 — Dashboard

- Regenerate `@prexorcloud/api-sdk` from `docs/openapi.json` — **fix the
  `sdk:generate` script path** (points at the deleted `.yaml`).
- `stores/crashes.ts` — `shareCrash(id, opts)`; new `stores/share.ts` for
  `shareLogs` / `shareDiagnostics`. Surface `shareEnabled` from `/system/settings`.
- `pages/crashes/index.vue`, `pages/observability/logs.vue`, + the diagnostics
  surface — "Share" button (hidden/disabled when `!shareEnabled`); success →
  `toast.success` with the link + copy affordance. i18n keys under
  `store.crashes.*` / `store.share.*`.
- Tests (suite ~1105, keep parity): extend `stores/__tests__/crashes.test.ts`,
  add `stores/__tests__/share.test.ts`, extend the crashes-index +
  observability-logs page tests — button render/visibility, click → action,
  success/failure toasts.

## Phase 7 — Config defaults & docs

- `ShareConfig` record defaults make the feature live at pste.dev out of the box
  — no installer changes.
- Documented `share:` block in `deploy/compose/controller.yml` (defaults +
  repoint/disable instructions).
- `docs/configuration.md` — `share.*` schema, pste.dev default, privacy posture
  (every send explicit, redaction always applied, repointable/disable-able).

## Phase 8 — Cross-cutting & tests

- Regenerate `docs/openapi.json`; verify `scripts/check-openapi-routes.sh`.
- **Security-critical tests:**
  - `LogRedactorTest` — table-driven (`@ParameterizedTest`): each pattern
    positive + negative (must NOT mangle normal lines), JWT/bearer/URI-userinfo/
    IP/`password=` variants, idempotency, a realistic crash-`logTail` fixture
    asserting no known-secret substring survives.
  - `ShareServiceTest` (mocked `PasteClient`) — redaction always applied
    (incl. the diagnostics path), `enabled=false` throws, ~4 MB ceiling
    enforced + truncation marker, override merge.
  - `PasteClientTest` (mocked `HttpClient`) — 201 parse, 413/429/timeout →
    `PasteException`.
  - `InstanceFileTreeWalkerTest` (daemon) — basic walk; `max_entries`/`max_depth`
    caps → `truncated`; directory summarization (`summary`/`child_count`);
    symlink recorded-not-followed (incl. a symlink loop); unreadable entry
    skipped; unreadable root → `DIR_UNREADABLE`; missing/`../` instance id →
    `INSTANCE_NOT_FOUND`.
  - `PendingRequestRegistryTest` — complete; timeout → exceptional; unknown id
    no-op; `failAll` selective.
  - `InstanceFileTreeServiceTest` — unreachable / timeout / daemon-error all →
    "unavailable", never throws; happy path propagates `truncated`.

---

## Critical files

**New:** `controller/config/ShareConfig.java`, `controller/share/PasteClient.java`,
`controller/share/ShareService.java`, `controller/observability/LogRedactor.java`,
`controller/grpc/PendingRequestRegistry.java`, `controller/grpc/DaemonFileTreeReceiver.java`,
`controller/diagnostics/{DiagnosticsCollector,InstanceFileTreeService,InstanceFileTreeResult}.java`,
`rest/dto/Share{Request,LogRequest,Result}Dto.java`,
`daemon/process/InstanceFileTreeWalker.java`, `dashboard/app/stores/share.ts`,
`docs/engineering/paste-share-plan.md`.

**Modified:** `java/cloud-protocol/src/main/proto/prexorcloud/daemon_service.proto`,
`contracts/proto-contracts.sha256`, `controller/config/ControllerConfig.java`
(+ ~6 ctor sites), `controller/PrexorController.java`,
`controller/grpc/DaemonServiceImpl.java` (+ `Deps`), `PrexorCloudBootstrap.java`,
`controller/grpc/DaemonConnectionLifecycle.java`,
`daemon/grpc/MessageDispatcher.java`, `daemon/process/ProcessManager.java`,
`rest/route/{CrashRoutes,SystemRoutes,DaemonLogRoutes}.java`, `rest/RestServer.java`,
`rest/dto/SystemDtoMapper.java`, `cli/cmd/{crash,logs,diagnostics}.go`,
`dashboard/app/stores/crashes.ts`,
`dashboard/app/pages/{crashes/index,observability/logs}.vue`,
`dashboard/packages/api-sdk/package.json`, `deploy/compose/controller.yml`,
`docs/configuration.md`.

## Risks & sequencing

1. **`ControllerConfig` fan-out** — land isolated and green first (Phase 1).
2. **Proto change is a contract surface** — additive oneof variants (no
   `PROTOCOL_VERSION` bump), but `contracts/proto-contracts.sha256` must update
   and `:cloud-protocol:build` must regen before controller/daemon compile.
   Treat Phase 2 as its own landable unit.
3. **`PendingRequestRegistry` is new infra** — keep it small, generic, well-tested;
   it's the first async-correlation utility in the codebase.
4. **Size** — structure-only filetrees + directory summarization + caps +
   the ~4 MB `ShareService` ceiling with a truncation marker. 5 MB is adequate.
5. **Redaction completeness is the security risk** — more collected = more
   surface. Default private + short expiry, unconditional redaction, exhaustive
   table-driven tests, independent review of the `LogRedactor` catalogue.
6. **`--share` + `--follow`** must error cleanly.
7. **OSS trust posture** — docs must state plainly: sharing is always
   operator-invoked, redacted, repointable/disable-able.

## Verification (end-to-end)

1. **Build green:** `./gradlew :cloud-protocol:build :cloud-controller:build
   :cloud-daemon:build`, `go build ./cli/...`, `pnpm --filter dashboard build`.
2. **Unit:** `./gradlew test --tests '*LogRedactor*' --tests '*ShareService*'
   --tests '*PasteClient*' --tests '*InstanceFileTreeWalker*'
   --tests '*PendingRequestRegistry*' --tests '*InstanceFileTreeService*'` — green.
3. **Live (via `LIVE_CLUSTER_GUIDE.md` cluster), default config:**
   `prexorctl diagnostics bundle --share` returns a pste.dev link; open it and
   confirm it contains nodes, instances, groups, templates, and per-instance
   filetrees — with config secrets, tokens, IPs, and URI passwords redacted, and
   a large `world/region/` folder shown as a single summarized line.
   `prexorctl crash info <id> --share` and `logs controller --share` likewise.
   Dashboard "Share" on a crash → toast with link.
4. **Caps:** a cluster with a huge instance dir produces a bounded paste with a
   visible truncation marker — never a runaway upload or a 413.
5. **Configurability:** repoint `share.pasteUrl` to a local pste → links point
   there; `share.enabled=false` → endpoints 409, CLI friendly message, dashboard
   button hidden.
6. **Resilience:** stop a daemon, then `diagnostics bundle --share` → its
   instances' filetrees show "unavailable", the snapshot still succeeds.
7. `scripts/check-openapi-routes.sh` green; CLI reference + api-sdk regenerated.
