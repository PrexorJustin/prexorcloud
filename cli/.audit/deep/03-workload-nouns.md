# prexorctl deep review — workload nouns (`group` / `instance` / `node`)

Scope: `cmd/group.go`, `cmd/instance.go`, `cmd/node.go`, `internal/api/types.go`,
`internal/tui/groupinfo.go`. Evaluated against ALL 13 rubric dimensions. These are
the **core workload verbs** an operator types all day — the bar is kubectl/flyctl,
not "it works".

Architectural facts assumed (from RUBRIC + prior pass): HA controller, any-member
read/forward-write, shared Mongo for app state, `isLeader()` not exposed, scale/start/
deploy are **async** (scheduler reconciles after the call returns), `GET /cluster/members`
is the failover substrate.

Backend naming note carried throughout: the CLI noun is **`instance`** but the REST
surface is **`/api/v1/services`** (and start/stop live under `/api/v1/groups/<g>/start`
and `/api/v1/services/<id>/stop|force-stop|command|console`). The CLI noun is the
better domain word; the *drift* is a documentation/consistency hazard, not a rename
target.

---

## `group` (group.go)

### prexorctl group list
Current: `GET /api/v1/groups` → themed table (GROUP/TYPE/STATUS/INSTANCES/PLAYERS/VERSION/UPDATED); client-side `--filter`/`--sort`; `--watch` = 2s screen-clear loop.

**Verdict: IMPROVE** — best-in-class target: `kubectl get groups` parity — server-driven
columns, `-o wide|yaml|name|jsonpath`, `--watch` as a real incremental stream, label
selectors, and a non-fabricated `UPDATED`.

**Findings:**
- `UPDATED` column is the hardcoded literal `"just now"` (group.go:465) — fabricated data presented as a timestamp; either serve `updatedAt` or drop the column. [dim 6][table-stakes][needs-server]
- `--json` path ignores `--filter`/`--sort` entirely (group.go:43-45) — scripts can't get the same filtered view a human sees; filtering should apply pre-serialization, or move server-side. [dim 6,7][table-stakes][client-only]
- `--filter` is a client-side substring on name only (group.go:412-418). Real target is a label/field **selector** (`-l env=prod`, `--field-selector status=DOWN`) so it composes with server paging. [dim 5,13][modern][needs-server]
- `--sort` is a hand-rolled insertion sort over 3 hardcoded keys (group.go:421-438); replace with `--sort-by=<jsonpath>` (kubectl grammar) over the decoded objects. [dim 6,13][modern][client-only]
- No `-o wide` (group.go has one fixed column set). Operators want MIN/MAX/MEM/ROUTING/MAINTENANCE without dropping to `--json | jq`. [dim 6][modern][client-only]
- `--watch` is `\033[2J\033[H` + `time.Sleep(2s)` (group.go:78-85): no configurable interval, full-screen flicker, no diff highlight, no "updated" line, Ctrl-C only exit, and it's silently ignored under `--json`. kubectl `--watch` streams row deltas. [dim 6,9][modern][needs-server for true streaming; client-only for interval/flag]
- No pagination controls though the client unwraps `{data:[]}` envelopes — large fleets get one unbounded GET. [dim 11][table-stakes][needs-server]
- `STATUS` is a 3-way client heuristic (UP/DRAIN/DOWN, group.go:440-448) that can't express SCALING/DEGRADED/PARTIAL; status should be server-authoritative. [dim 4,6][modern][needs-server]

### prexorctl group info [name]
Current: `GET /api/v1/groups/<name>` → bubbletea dashboard (CONFIG/SCALING/TEMPLATE panels + INSTANCES table + synthetic RECENT EVENTS); `d`/`r`/`↵` actions; static fallback when non-TTY; `--json` dumps raw map.

**Verdict: IMPROVE** — target: a clean `describe`-style static render by default with the
live TUI gated behind an explicit `--watch`/`--ui` flag; this is the **non-TTY TUI
problem** and the command's biggest design smell.

**Findings:**
- The command's *default* on a TTY is a full-screen alt-screen app (groupinfo.go:61 `tea.WithAltScreen()`). `kubectl describe` / `gh` print to stdout and exit. A read verb that hijacks the screen is surprising and un-pipeable; the live dashboard belongs under an opt-in `--watch` (or a separate `group top`/`group dashboard`), with static describe as the default. [dim 6,7,9,12,13][table-stakes][client-only]
- Non-TTY fallback exists (group.go:538 `printGroupInfoStatic`) — good — but it's a *second* renderer that can drift from the TUI; unify on one describe renderer that the TUI decorates. [dim 12][modern][client-only]
- RECENT EVENTS is synthesized from instance uptimes (group.go:630-649, comment admits "the controller has no per-group event feed") — fabricated provenance shown as an event log. Either wire a real event/audit feed or label it clearly. [dim 6,9][table-stakes][needs-server]
- INSTANCES table columns TPS/MEM/MATCH are hardcoded `—` (group.go:619-621, groupinfo.go:13-22) — three dead columns; drop until served. [dim 6][table-stakes][needs-server]
- Decodes into `map[string]any` then `str()/num()` stringly-types every field (group.go:105,519-532) instead of the existing `api.GroupResponse` struct — loses the type safety the sibling `list` has. [dim 6,12][modern][client-only]
- In-TUI `d` = stop, `r` = force-stop-as-restart (group.go:556-561) — "drain" and "restart" are aliased onto stop semantics with no per-instance restart verb; mislabeled actions on a destructive surface with no confirm inside the TUI. [dim 1,10][modern][needs-server]
- `--json` returns the bare group object — no instances, unlike the rendered view which fetches `/services?group=` — JSON consumer gets less than the human. [dim 6][table-stakes][client-only]
- `?group=`+name is hand-concatenated (group.go:492) with no URL escaping — names with reserved chars break. [dim 8][table-stakes][client-only]

### prexorctl group create
Current: `POST /api/v1/groups` from flags; hardcodes `jarFile:"server.jar"`; always sends every flag (zero or not).

**Verdict: IMPROVE** — target: declarative `apply -f group.yaml` as the primary path,
with flags as the convenience shortcut, plus `--dry-run=server`.

**Findings:**
- Hardcodes `jarFile:"server.jar"` (group.go:144) with no flag — uncreatable for non-default jar names. [dim 5][table-stakes][client-only]
- Sends every field including unset zero-values (group.go:140-153), so server defaults can never apply — the create body always pins min=1/max=10/mem=1024 etc. even if the user wanted server defaults. Should send only `flags.Changed` like `group update` does. [dim 4,5][table-stakes][client-only]
- No `maxPlayers`, `parent`, `updateStrategy`, `static` flags though `group info` displays all of them — create is a strict subset of the model. [dim 1,5][table-stakes][client-only]
- No enum validation/completion on `--scaling-mode`/`--routing`/`--platform` (free strings, group.go:387-391); invalid values fail server-side only. Wire `RegisterFlagCompletionFunc` + client-side enum check. [dim 5,8][modern][client-only]
- No `--dry-run` (client schema check or server validation), no idempotency (re-run = 409). [dim 4,13][modern][needs-server for server dry-run]
- No declarative `apply -f`/`-o yaml` round-trip — the single biggest modernization: `group info -o yaml > g.yaml; edit; apply -f g.yaml`. [dim 13][innovative][needs-server]
- No `--wait` for the group to reach its min-instances floor after create. [dim 4][modern][needs-server for condition endpoint]

### prexorctl group update [name]
Current: `PATCH` with only `flags.Changed` fields; 5 mutable fields (min/max/memory/routing/scaling-mode).

**Verdict: IMPROVE** — target: full-field patch + optimistic concurrency + `edit`.

**Findings:**
- Changed-only patch is the correct pattern (group.go:184-205) — this is the model `create`/`catalog update` should copy. [dim 4][table-stakes][keep]
- Narrow surface: can't update templates, platformVersion, ports, maxPlayers, parent (group.go:397-401) — forces delete+recreate for common edits. [dim 1,5][table-stakes][needs-server confirm of mutability]
- No optimistic concurrency: blind PATCH with no resourceVersion/If-Match → last-writer-wins under HA concurrent edits. [dim 4,10][modern][needs-server]
- No `prexorctl group edit <name>` ($EDITOR round-trip on the live object) — kubectl-grade ergonomics. [dim 13][modern][needs-server for conflict detect]
- No `--dry-run`. [dim 4][modern][needs-server]

### prexorctl group scale [name] [replicas]
Current: validates ≥0; GETs `maxInstances`; PATCHes `minInstances` (+ raises max if replicas>curMax). Picker + numeric prompt fallback.

**Verdict: IMPROVE** — target: kubectl `scale --replicas=N --wait --timeout` with
`--current-replicas` precondition; this is the flagship **async-convergence** command.

**Findings:**
- **Returns immediately after PATCH** (group.go:370-377) — prints "scaled to N" while the scheduler is still spawning/killing; the workload is NOT at N when the command exits. Needs `--wait`/`--for=condition=available`/`--timeout` that polls `runningInstances` until it equals the target (or a server-side condition endpoint). This is the #1 finding for the whole noun-set. [dim 4,13][table-stakes][needs-server for a clean condition; client-only poll possible today]
- Read-modify-write on max is racy (group.go:358-367): GET then PATCH with no precondition; a concurrent scale can clobber. Offer `--current-replicas=N` (precondition) like kubectl. [dim 4][modern][needs-server]
- Conflates "set min floor" with "scale" — for DYNAMIC groups `scale N` only moves the floor; the `Long` help explains this (good) but the success line "scaled to N" overstates it for DYNAMIC. [dim 2,8][modern][client-only]
- **Scale-to-0 is destructive** (stops all instances, kicks players) and is completely unguarded — no confirm, no `--yes`. Should typed-confirm or require `--yes` when target=0 (or below current running). [dim 10][table-stakes][client-only]
- No `--json` ack with before/after replica counts. [dim 6][table-stakes][client-only]

### prexorctl group delete [name]
Current: mandatory `huh` confirm → `DELETE`. **No `--yes`, no `--json`.**

**Verdict: IMPROVE** — target: `--yes`/typed-name confirm + JSON ack + cascade visibility.

**Findings:**
- **Unscriptable**: the only bypass-less mandatory confirm in the set (group.go:236-244). In `--json`/CI it blocks on /dev/tty or fails. Must add `--yes`/`-y`. [dim 7,10][table-stakes][client-only]
- For a high-blast-radius delete, prefer **typed-name confirmation** ("type the group name to confirm", like `gh repo delete`/cockroach) over a y/N toggle. [dim 10][modern][client-only]
- No `--json` (group.go:220) — ignores `flagJSON`; should emit `{"deleted":"<name>"}`. [dim 6,7][table-stakes][client-only]
- No `--cascade`/`--dry-run` to preview what gets stopped (the confirm *describes* it but doesn't enumerate instances/players affected). [dim 9,10][modern][needs-server]
- No `--wait` for teardown completion. [dim 4][modern][needs-server]

### prexorctl group maintenance [name] [on|off]
Current: maps on/true/1→enabled else→disabled; `PATCH {maintenance}`. Picker + on/off select fallback.

**Verdict: IMPROVE** — target: explicit enum verb pair, validated.

**Findings:**
- Garbage toggle silently means "off" (group.go:290) — `group maintenance lobby onn` disables maintenance with a success message. Must validate the enum and error on unknown. [dim 5,8][table-stakes][client-only]
- Verb shape `maintenance <on|off>` is awkward; consider `group drain <name>` / `group undrain <name>` to mirror `node drain`/`undrain` (consistency win across the whole CLI). [dim 1,2,12][modern][client-only]
- No `--json` ack of resulting state. [dim 6][table-stakes][client-only]
- Enabling maintenance drains player connections — mildly disruptive, currently unguarded; a `--yes` or at least a player-count warning is warranted. [dim 10][modern][needs-server for affected count]

---

## `instance` (instance.go) — alias `inst`

### prexorctl instance list
Current: `GET /api/v1/services` with `--group/--node/--state`; table ID/GROUP/NODE/STATE/PORT/PLAYERS/UPTIME.

**Verdict: IMPROVE** — target: `get instances` parity with the group-list improvements.

**Findings:**
- Backend is `/api/v1/services` but the noun is `instance` — document the mapping in help and keep an `services` alias hidden, so an operator reading server logs/API docs can find it. [dim 2][modern][client-only]
- No `--sort`/client filter/`--watch`/`-o wide` though `group list` has them — sibling inconsistency. [dim 6,12][modern][client-only]
- Decodes to `map[string]any` + `str()/num()` (instance.go:40,52-67) while `api.InstanceResponse` exists — drops `deploymentRevision`, `startedAt` typing. [dim 6,12][modern][client-only]
- `--state`/`--group`/`--node` are free strings, no completion, no enum check on state. [dim 5][modern][client-only]
- No pagination controls. [dim 11][table-stakes][needs-server]

### prexorctl instance info [id]
Current: `GET /api/v1/services/<id>` → header + INSTANCE card.

**Verdict: IMPROVE** — target: `describe instance` with full lifecycle + deployment correlation.

**Findings:**
- Drops `deploymentRevision` (instance.go:116-122) though it's in `InstanceResponse` (types.go:48) — the one field that correlates an instance to a rollout. [dim 6][table-stakes][client-only]
- No `-o yaml`/`-o wide`; `map[string]any` decode. [dim 6][modern][client-only]
- No recent-crash / restart-count / exit-code surfacing though `CrashResponse` exists (types.go:67) — describe should link the last crash. [dim 6,9][modern][needs-server join]
- id is path-concatenated unescaped (instance.go:97). [dim 8][table-stakes][client-only]

### prexorctl instance start <group>
Current: `POST /api/v1/groups/<group>/start`; `ExactArgs(1)`, **no picker**; prints "<count> scheduled".

**Verdict: IMPROVE** — target: async-aware start with `--wait`, picker parity, `--count`.

**Findings:**
- Verb lives on `instance` but takes a **group** and starts an *unspecified* new instance — grammar mismatch; arguably `group scale +1` or `group instance add`. At minimum the help must make "starts a new instance in <group>" unmissable. [dim 1,2][modern][client-only]
- No group picker fallback (instance.go:131 `ExactArgs(1)`) while every sibling resolves a missing arg interactively — inconsistent. [dim 5,12][table-stakes][client-only]
- **Async**: returns when the instance is *scheduled*, not RUNNING (instance.go:151). Needs `--wait`/`--timeout` polling until the new instance reaches RUNNING, and should print the new instance **id** (not just a count) so scripts can chain. [dim 4,6][table-stakes][needs-server returns id]
- No `--count N` to start several. [dim 5][modern][needs-server]

### prexorctl instance stop [id]
Current: `POST .../stop` or `/force-stop` with `--force`; picker fallback. **No `--json`, no confirm.**

**Verdict: IMPROVE** — target: graceful-by-default with `--force` gated + `--wait`.

**Findings:**
- `--force` (SIGKILL-equivalent, instance.go:172-176) is **destructive and unguarded** — no confirm, no `--yes`. Force-killing a running instance kicks players with no flush; gate it (confirm unless `--yes`). [dim 10][table-stakes][client-only]
- No `--json` ack (instance.go:178-187). [dim 6,7][table-stakes][client-only]
- Returns before the instance is actually STOPPED — no `--wait`/`--timeout`. [dim 4][modern][needs-server condition]
- No multi-target (`stop id1 id2 …` or `--group`/`--selector`) — can't drain a set in one call. [dim 5,13][modern][needs-server]
- No `--grace-period`/`--timeout` to bound graceful shutdown before force (kubectl `--grace-period`). [dim 4,13][modern][needs-server]

### prexorctl instance exec <id> <command...>
Current: `MinimumNArgs(2)`, joins args[1:], `POST .../command`; prints "Sent to <id>: <cmd>"; ignores response. **No `--json`.**

**Verdict: IMPROVE** — target: real fire-and-(optionally)-wait console exec with output capture.

**Findings:**
- **Fire-and-forget**: discards any response body (instance.go:204) and can't show command output — `exec` that can't see output is half a command. If the console returns output, capture it; else offer `--follow` to tail the console for N seconds. [dim 4,6][modern][needs-server]
- Name collision with shell `exec`/kubectl `exec` (interactive shell) sets a wrong expectation — this is a *server console command*, not a PTY. Consider `instance command`/`instance cmd <id> -- <cmd>` and reserve `exec` for an actual attach. [dim 1,2][modern][client-only]
- No `--` separator handling (instance.go:201 naive join) — flags meant for the remote command (e.g. `say -foo`) get parsed by cobra. Use `cmd.ArgsLenAtDash()`. [dim 5][table-stakes][client-only]
- No picker for the id (ExactArgs-style), unlike `stop`/`console`. [dim 5,12][modern][client-only]
- No `--json` ack; command not read from stdin option for long/scripted input. [dim 5,6][modern][client-only]

### prexorctl instance console [id]
Current: best-effort GET for header → `tui.LogStream` SSE console + `POST .../command` input; Ctrl-Q to detach; picker fallback.

**Verdict: IMPROVE** — target: kubectl `logs -f`-grade streaming with non-TTY tail mode.

**Findings:**
- TTY-only: it's a full bubbletea LogStream (instance.go:248) with no plain `--follow`/`--tail=N`/`--since` non-interactive mode — can't `prexorctl instance console x | grep ERROR` or use it in CI. Provide a headless streaming path when `!interactive()`. [dim 6,7][table-stakes][needs-server SSE already exists; client-only to add raw printer]
- Input submit errors are swallowed (`_ = client.Post(...)` instance.go:255) — a failed command silently does nothing. [dim 8][modern][client-only]
- SSE reconnect/backoff on transient drop isn't visible here — a console that dies on one network blip is fragile under HA failover; should reconnect to another member. [dim 3,4][modern][needs-server + client-only]
- No `--timestamps`, no log-level filter passthrough, no scrollback search in the TUI. [dim 6,9][modern][client-only]
- Console SSE pins one controller URL; on leader/member loss it can't fail over to another `restAddr`. [dim 3][modern][needs-server discovery + client-only]

---

## `node` (node.go)

### prexorctl node list
Current: shared `fetchList` → `GET /api/v1/nodes`; table ID/STATUS/CPU/MEMORY/INSTANCES/CONNECTED SINCE; `--state` server filter.

**Verdict: IMPROVE** — target: `get nodes -o wide` with correct status buckets + sort.

**Findings:**
- UNREACHABLE counted as offline (node.go:36-44): footer switch only buckets ONLINE/DRAINING, lumping the documented `--state UNREACHABLE` value into offline — wrong tally. [dim 6][table-stakes][client-only]
- Field-source ambiguity: `list` keys ID off `id` (node.go:46) but `info` heads off `nodeId` (node.go:95); `NodeResponse` carries both (types.go:25-26). Pick one canonical id everywhere. [dim 2,12][table-stakes][client-only]
- No `-o wide` (TYPE, freeDisk, version, labels), no `--sort`, no `--watch` — parity gap with group list. [dim 6][modern][client-only]
- No pagination. [dim 11][table-stakes][needs-server]
- Only command in the set that uses `fetchList` — others should converge on it (consistency). [dim 12][modern][client-only]

### prexorctl node info [id]
Current: `GET /api/v1/nodes/<id>` → RESOURCES card + RUNNING INSTANCES table; picker fallback.

**Verdict: IMPROVE** — target: `describe node` with conditions, labels, drain state, allocatable.

**Findings:**
- No `-o yaml`/`-o wide`; `map[string]any` decode despite `NodeResponse`. [dim 6][modern][client-only]
- No surfacing of drain status / schedulability / node type / labels in the card — the very fields `drain`/scheduling decisions hinge on. [dim 6,9][modern][needs-server]
- id path-concatenated unescaped (node.go:85). [dim 8][table-stakes][client-only]
- No capacity/allocatable/headroom view (how many more instances fit) — high-value for an operator deciding placement. [dim 6,13][innovative][needs-server]

### prexorctl node drain [id]
Current: `POST .../drain` → "set to DRAINING". Picker fallback. **No `--json`, no confirm, no wait.**

**Verdict: IMPROVE** — target: true kubectl `drain` semantics (cordon + evict + wait), gated.

**Findings:**
- This is **cordon, not drain**. kubectl `drain` = cordon (stop new placement) **+ evict existing workload + wait until empty**. Here it just flips state to DRAINING and returns; instances are not evicted/migrated by the command and it doesn't wait. Split into `node cordon` (mark unschedulable) and `node drain` (cordon + evacuate + `--wait`/`--timeout`), matching the industry mental model. [dim 1,2,4,13][modern][needs-server eviction/migration]
- Disruptive (reschedules/kills workload) yet **unguarded** — no confirm, no `--yes`, no preview of how many instances/players move. Add a danger gate + affected-count preview. [dim 10][table-stakes][client-only for gate; needs-server for count]
- Returns before the node is empty — no `--wait`. An operator draining for maintenance has no signal it's safe to power off. [dim 4][table-stakes][needs-server condition]
- No `--json` ack (node.go:146-150). [dim 6,7][table-stakes][client-only]
- No `--force`/`--grace-period`/`--ignore-daemonsets`-style controls. [dim 4,5][modern][needs-server]

### prexorctl node undrain [id]
Current: `POST .../undrain` → "set to ONLINE". **No `--json`.**

**Verdict: IMPROVE** — target: `node uncordon` naming + JSON ack.

**Findings:**
- Mirror the cordon/drain rename: this is `uncordon`. Keep `undrain` as an alias. [dim 2,12][modern][client-only]
- No `--json` ack (node.go:168-172). [dim 6,7][table-stakes][client-only]
- "set to ONLINE" overstates: undrain makes a node *schedulable* again; if it's UNREACHABLE it won't be ONLINE. Report the actual resulting state from the response. [dim 8][modern][needs-server]

---

## Cross-cutting / systemic

These should be fixed once, CLI-wide, not per command:

1. **Async-convergence story (the headline gap).** `group scale`, `instance start`, `instance stop`, `group create/delete`, `node drain` all return before the workload converges. Build ONE shared `--wait`/`--for=condition=<x>`/`--timeout=<dur>` mechanism (kubectl `wait` model). Needs a server-side condition/readiness signal per resource (`runningInstances==target`, instance `RUNNING`/`STOPPED`, node `drained`); client can poll today as a stopgap. [table-stakes][needs-server for clean conditions]

2. **Unified output system.** Today: typed structs in some lists, `map[string]any`+`str()/num()` in others; `--json` present on reads, missing/partial on writes; no `-o yaml|wide|name|jsonpath|template|csv`; fabricated columns (`"just now"`, synthetic events, dead TPS/MEM/MATCH). Build one `-o` renderer over typed models, kill fabricated data, and guarantee every command (incl. writes) honors `--json`/`--quiet` with a stable ack envelope. [table-stakes][client-only]

3. **Danger-gate model.** Destructive/disruptive ops are inconsistently guarded: `group delete` has a confirm but no `--yes` (unscriptable); `instance stop --force`, `group scale 0`, `node drain` have no gate at all. Define one gate: `--yes` to bypass, typed-name confirm for high-blast-radius (delete/drain), `--dry-run` to preview, and an affected-resources count before the prompt. Applies uniformly. [table-stakes][client-only + needs-server for previews]

4. **Declarative `apply -f` + unified `get`/`describe`.** The domain is plainly declarative (groups have desired min/max/templates the scheduler reconciles). Target: `prexorctl get <group|instance|node> [-o yaml]`, `describe`, `apply -f`, and `edit`. This subsumes `group create`/`update`/`scale`/`maintenance` into one reconcilable spec and is the single biggest modernization. [innovative][needs-server]

5. **Label/selector support.** No labels anywhere. Add `-l key=val` / `--field-selector` to all `list` verbs and to bulk mutating verbs (`instance stop -l`, `node drain`) so operators act on sets, not one id at a time. [modern][needs-server]

6. **The non-TTY TUI problem.** `group info` defaults to an alt-screen app; `instance console` is TTY-only. Reads should print-and-exit by default and gate the live dashboard/stream behind `--watch`/`--ui`/`--follow`; always provide a headless path. Generalize `interactive()` so NO command can hang waiting on /dev/tty in CI. [table-stakes][client-only]

7. **Topology / HA failover.** Every command pins one controller URL. Since any member serves reads/forwards writes and `GET /cluster/members` yields `restAddr`s, add transparent multi-endpoint failover + retry-on-another-member (etcdctl `--endpoints`), especially for the long-lived `instance console` SSE. Writes (POST/PATCH/DELETE) are currently never retried — make them idempotent + safely retried where the verb allows. [modern][needs-server for member discovery + client-only]

8. **kubectl-grade node verbs.** Rename `node drain`→split `cordon`/`drain` with real eviction+wait; add `node info` schedulability/labels/headroom. This aligns the most operationally dangerous verb with the universal mental model. [modern][needs-server]

9. **URL escaping + typed decoding everywhere.** All paths are string-concatenated (`"/api/v1/groups/"+name`, `?group=`+name) with no escaping — break or inject on reserved chars. Route every call through `url.PathEscape`/`GetWithQuery` and decode into the existing typed structs. [table-stakes][client-only]

10. **Enum validation + shell completion.** `--scaling-mode`, `--routing`, `--platform`, `--state`, maintenance `on|off` are free strings validated only server-side. Add client-side enum checks + `RegisterFlagCompletionFunc` (dynamic completion of group/instance/node ids, platforms, states). [modern][client-only]
