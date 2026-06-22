# prexorctl CLI Audit — Orchestration Command Groups

Scope: `cli/cmd/{group,instance,node,template,catalog,deploy,status}.go`, cross-referenced
against `cli/internal/api/types.go`, `cli/internal/api/client.go`, `cli/cmd/picker.go`,
`cli/cmd/helpers.go`, `cli/cmd/root.go`. Read-only audit; no source modified.

**Commands documented: 37** (7 parent groups/commands + 30 leaf subcommands).

## Global / shared behavior

Persistent flags on the root command (`root.go:177-183`) apply to every command below:

| Flag | Short | Type | Default | Purpose |
|------|-------|------|---------|---------|
| `--json` | `-j` | bool | false | Emit JSON instead of the rendered TUI/table. Also auto-enabled when `PREXOR_OUTPUT=json` (`root.go:186`). |
| `--controller` | `-c` | string | "" | Override controller URL for this invocation. |
| `--token` | `-t` | string | "" | Override auth token. |
| `--context` | — | string | "" | Override active context. |
| `--no-color` | — | bool | false | Disable colored output. |
| `--ascii` | — | bool | false | ASCII-only glyphs. |
| `--verbose` | `-v` | bool | false | Print HTTP request/response lines. |

Shared mechanics:
- `requireAuth()` (`root.go:233`) builds the client and errors `not authenticated -- run 'prexorctl login'` if no token, or `no controller URL configured -- run 'prexorctl setup'...` if no controller.
- `resolveArg(args, usage, pick)` (`helpers.go:45`): returns `args[0]` if present; else if a TTY (and not `--json`) runs the interactive `huh` picker; else returns the usage error. `interactive()` is false whenever `--json` is set (`picker.go:22`).
- `client.GetList` (`client.go:240`) transparently unwraps `{ "data": [...] }` pagination envelopes or bare arrays.
- Only GET/HEAD are auto-retried (3 attempts, backoff); POST/PATCH/PUT/DELETE never retried (`client.go:140`).
- API errors map to exit codes: 401→2, 403→3, 404→4, else 1 (`client.go:109`).

---

## `group` (group.go)

Parent: `prexorctl group` — "Manage server groups". No RunE (help only).

### `prexorctl group list`
- Purpose: list all groups.
- Usage: `group list` (no positionals).
- Flags: `--filter <str>` (default ""; substring match on name, client-side), `--sort <name|players|instances>` (default `name`, client-side insertion sort), `--watch` (bool, default false; re-renders every 2s by clearing screen with raw ANSI `\033[2J\033[H` in an infinite loop).
- Does: `GET /api/v1/groups`, decoded into `[]api.GroupResponse`. Renders themed table (GROUP/TYPE/STATUS/INSTANCES/PLAYERS/VERSION/UPDATED) + footer counts. `UPDATED` column is hardcoded `"just now"` (`group.go:465`).
- `--json`: yes — dumps the raw `[]GroupResponse` (filter/sort/watch ignored in JSON path).
- Interactive: none (watch is a passive loop, exit via Ctrl-C only).

### `prexorctl group info [name]`
- Purpose: show group details (interactive bubbletea view).
- Usage: `group info [name]` — name optional; picker if omitted on a TTY.
- Flags: none beyond global.
- Does: `GET /api/v1/groups/<name>` (into `map[string]any`); non-JSON launches `tui.NewGroupInfo`, which fetches `GET /api/v1/services?group=<name>` for the instance table and offers in-view actions: `d` drain → `POST /api/v1/services/<id>/stop`; `r` restart → `POST /api/v1/services/<id>/force-stop`; `↵` → attaches the live console.
- `--json`: yes — dumps the raw group map (skips the TUI).
- Interactive: group picker if no arg; rich bubbletea panel with drain/restart/attach key actions.

### `prexorctl group create`
- Purpose: create a new group.
- Usage: `group create --name <n> --platform <p> [flags]`.
- Flags: `--name` (string, req), `--platform` (string, req), `--platform-version` (string ""), `--template` (stringSlice, ordered layers), `--scaling-mode` (string `DYNAMIC`), `--min` (int 1), `--max` (int 10), `--memory` (int 1024 MB), `--routing` (string `LOWEST_PLAYERS`), `--port-start` (int 30000), `--port-end` (int 30100).
- Does: `POST /api/v1/groups` with a body that **hardcodes `jarFile: "server.jar"`** (`group.go:144`) and includes every flag value (zero or not).
- `--json`: yes — dumps the POST result; otherwise prints success line.
- Interactive: none.

### `prexorctl group update [name]`
- Purpose: update mutable group fields.
- Usage: `group update [name] [flags]` — name optional (picker).
- Flags: `--min` (int 0), `--max` (int 0), `--memory` (int 0), `--routing` (string ""), `--scaling-mode` (string "").
- Does: builds a body containing **only flags the user changed** (`flags.Changed`), then `PATCH /api/v1/groups/<name>`. Only 5 fields are updatable.
- `--json`: yes. Interactive: group picker if no arg.

### `prexorctl group scale [name] [replicas]`
- Purpose: set `minInstances` floor to N, raising `maxInstances` to match if lower.
- Usage: `group scale [name] [replicas]` — both optional; pickers/prompt fill gaps.
- Flags: none beyond global.
- Does: validates replicas ≥ 0; `GET /api/v1/groups/<name>` to read `maxInstances`; `PATCH` with `minInstances`, plus `maxInstances` only if `replicas > curMax`. Good `Long` help explaining STATIC/MANUAL/DYNAMIC semantics.
- `--json`: yes. Interactive: group picker + numeric `huh.NewInput` prompt when replicas omitted on a TTY; non-TTY errors with example.

### `prexorctl group delete [name]`
- Purpose: delete a group.
- Usage: `group delete [name]` — name optional (picker).
- Flags: none beyond global (no `--yes`/`--force`).
- Does: **`huh.NewConfirm` confirmation** (warns it stops all instances), then `DELETE /api/v1/groups/<name>`.
- `--json`: **NO** — only prints a success line; ignores `flagJSON` entirely.
- Interactive: group picker + mandatory confirm. **No non-interactive bypass flag** → unscriptable.

### `prexorctl group maintenance [name] [on|off]`
- Purpose: toggle maintenance (drain) mode.
- Usage: `group maintenance [name] [on|off]`.
- Flags: none beyond global.
- Does: maps `on|true|1`→enabled, anything else→disabled; `PATCH /api/v1/groups/<name>` with `{maintenance: bool}`.
- `--json`: yes. Interactive: group picker + on/off select when toggle omitted on a TTY. Garbage toggle values are silently treated as "off" (no validation).

---

## `instance` (instance.go) — alias `inst`

Parent: `prexorctl instance` / `inst` — "Manage server instances".

### `prexorctl instance list`
- Purpose: list instances.
- Usage: `instance list [flags]`.
- Flags: `--group` (string ""), `--node` (string ""), `--state` (string "") — all server-side query params.
- Does: `GET /api/v1/services` with the params (into `[]map[string]any`); themed table ID/GROUP/NODE/STATE/PORT/PLAYERS/UPTIME + footer (running/other).
- `--json`: yes. Interactive: none.

### `prexorctl instance info [id]`
- Purpose: show one instance's details.
- Usage: `instance info [id]` — id optional (picker).
- Does: `GET /api/v1/services/<id>`; renders header + INSTANCE card (port/players/memory/uptime). Does **not** surface `deploymentRevision` though the field exists.
- `--json`: yes. Interactive: instance picker if no arg.

### `prexorctl instance start <group>`
- Purpose: start a new instance in a group.
- Usage: `instance start <group>` — **`ExactArgs(1)`, no picker fallback**.
- Does: `POST /api/v1/groups/<group>/start`; prints "<count> instance(s) scheduled".
- `--json`: yes. Interactive: none (inconsistent — all sibling commands offer a picker for the missing arg).

### `prexorctl instance stop [id]`
- Purpose: stop an instance.
- Usage: `instance stop [id]` — id optional (picker).
- Flags: `--force` (bool false) → routes to `/force-stop` instead of `/stop`.
- Does: `POST /api/v1/services/<id>/stop` (or `/force-stop`); prints "stopping"/"force-stopped".
- `--json`: **NO**. Interactive: picker if no arg. **No confirmation even for `--force`** (force-kill is destructive but unguarded).

### `prexorctl instance exec <id> <command...>`
- Purpose: send a console command to an instance.
- Usage: `instance exec <id> <command...>` — `MinimumNArgs(2)`, no picker.
- Does: joins args[1:] and `POST /api/v1/services/<id>/command`; prints "Sent to <id>: <cmd>". Ignores any response body.
- `--json`: **NO**. Interactive: none.

### `prexorctl instance console [id]`
- Purpose: attach to a live console (SSE).
- Usage: `instance console [id]` — id optional (picker).
- Does: best-effort `GET /api/v1/services/<id>` for the header, then opens `tui.LogStream`; streams `GET /api/v1/services/<id>/console` over SSE (ticketed) and submits typed input via `POST .../command`. Ctrl-Q to detach.
- `--json`: N/A (interactive TUI). Interactive: full-screen log stream with input.

---

## `node` (node.go)

Parent: `prexorctl node` — "Manage cluster nodes".

### `prexorctl node list`
- Purpose: list nodes.
- Usage: `node list [--state ...]`.
- Flags: `--state <ONLINE|DRAINING|UNREACHABLE|OFFLINE>` (string "") — server-side filter.
- Does: via the shared `fetchList` helper (the only command in this set that does), `GET /api/v1/nodes`; themed table ID/STATUS/CPU/MEMORY/INSTANCES/CONNECTED SINCE + footer. The status count switch only buckets ONLINE/DRAINING/else→offline, so UNREACHABLE is counted as offline (`node.go:36-44`).
- `--json`: yes (handled inside `fetchList`). Interactive: none.

### `prexorctl node info [id]`
- Purpose: show node details + its running instances.
- Usage: `node info [id]` — id optional (picker).
- Does: `GET /api/v1/nodes/<id>`; RESOURCES card + RUNNING INSTANCES table. Heading reads `nodeId` (`node.go:95`) whereas `node list` keys off `id` (`node.go:46`) — inconsistent field source for the same identifier.
- `--json`: yes. Interactive: node picker if no arg.

### `prexorctl node drain [id]`
- Purpose: mark a node DRAINING.
- Usage: `node drain [id]` — id optional (picker).
- Does: `POST /api/v1/nodes/<id>/drain`; prints "set to DRAINING".
- `--json`: **NO**. Interactive: picker if no arg. **No confirmation** despite being disruptive (migrates/evacuates workload).

### `prexorctl node undrain [id]`
- Purpose: clear DRAINING.
- Usage: `node undrain [id]` — id optional (picker).
- Does: `POST /api/v1/nodes/<id>/undrain`; prints "set to ONLINE".
- `--json`: **NO**. Interactive: picker if no arg.

---

## `template` (template.go)

Parent: `prexorctl template` — "Manage templates".

### `prexorctl template list`
- Purpose: list templates.
- Usage: `template list`.
- Does: `GET /api/v1/templates`; table NAME/HASH(8-char)/SIZE/DESCRIPTION + footer.
- `--json`: yes. Interactive: none.

### `prexorctl template versions <name>`
- Purpose: version history of a template.
- Usage: `template versions <name>` — `ExactArgs(1)`, no picker.
- Does: `GET /api/v1/templates/<name>/versions`; numbered rows `len(versions)-i` (assumes newest-first ordering from the API — undocumented assumption).
- `--json`: yes. Interactive: none.

### `prexorctl template rollback <name>`
- Purpose: roll back to the previous template version.
- Usage: `template rollback <name>` — `ExactArgs(1)`, no picker.
- Does: `POST /api/v1/templates/<name>/rollback`. No target-version selection (only "previous"). **No confirmation.**
- `--json`: yes — synthesizes `{status, template}`. Interactive: none.

Note: `template` has no `info`, `delete`, `create`/`upload` here — limited surface.

---

## `catalog` (catalog.go)

Parent: `prexorctl catalog` — "Manage the server platform catalog" (good `Long`).

### `prexorctl catalog list`
- Purpose: list platforms/versions.
- Usage: `catalog list`.
- Does: `GET /api/v1/catalog` → `[]api.CatalogEntry`; table PLATFORM/CATEGORY/VERSION/REC(★)/SHA256/DOWNLOAD URL with middle-truncation.
- `--json`: yes. Interactive: none.

### `prexorctl catalog add <platform>`
- Purpose: add a version (creating the platform if new).
- Usage: `catalog add <platform> --version <v> --url <u> [flags]`. Has an `Example`.
- Flags: `--version` (string, req), `--url` (string, req), `--sha256` (string ""), `--category` (string `SERVER`), `--config-format` (string ""), `--recommended` (bool false).
- Does: `POST /api/v1/catalog/<platform>/versions`; if `--recommended`, a **second** `PUT .../versions/<v>/recommended` (non-atomic — version is added even if the recommend call fails; error wrapped).
- `--json`: yes. Interactive: none.

### `prexorctl catalog update [platform] [version]`
- Purpose: change a version's URL/checksum (and optionally rename).
- Usage: `catalog update [platform] [version] [flags]` — pickers fill missing args on a TTY.
- Flags: `--url` (string ""), `--sha256` (string ""), `--new-version` (string "" → defaults to current version).
- Does: `PATCH /api/v1/catalog/<platform>/versions/<version>` with `{version, downloadUrl, sha256}`. **Always sends `downloadUrl` and `sha256` even when the flags are empty** (`catalog.go:137-141`) — unlike `group update` which only sends changed fields.
- `--json`: yes. Interactive: platform + version pickers (`resolveCatalogTarget`).

### `prexorctl catalog recommend [platform] [version]`
- Purpose: mark a version recommended.
- Usage: `catalog recommend [platform] [version]` — pickers fill gaps.
- Does: `PUT /api/v1/catalog/<platform>/versions/<version>/recommended`.
- `--json`: **NO** — only a success line. Interactive: pickers.

### `prexorctl catalog remove [platform] [version]` (aliases `rm`, `delete`)
- Purpose: remove a version.
- Usage: `catalog remove [platform] [version]` — pickers fill gaps.
- Does: `DELETE /api/v1/catalog/<platform>/versions/<version>`. **No confirmation.**
- `--json`: **NO** — only a success line. Interactive: pickers.

---

## `deploy` (deploy.go)

Parent `prexorctl deploy <group>` is **itself runnable** (triggers a rollout) and also hosts subcommands.

### `prexorctl deploy <group>` (trigger)
- Purpose: trigger a rolling deployment for a group.
- Usage: `deploy <group> [flags]` — `ExactArgs(1)`, **no group picker**.
- Flags: `--strategy` (string ""), `--batch-size` (int 0), `--canary-instances` (int 0), `--canary-percent` (int 0, "mutually exclusive with --canary-instances"), `--health-gate` (bool false), `--auto-rollback` (bool false), `--promotion-timeout` (int64 0 sec), `--min-healthy` (int64 0 sec), `--yes`/`-y` (bool false; skip confirm).
- Does: builds a body of only the changed flags (`buildDeployBody`). In `--json` mode: `POST /api/v1/groups/<group>/deploy` and dump result. Otherwise: `GET` group → print PLAN preview → confirm → `POST .../deploy` → live `tui.NewDeploy` polling `GET .../deployments/<rev>`.
- `--json`: yes (skips PLAN/TUI). Interactive: a raw `bufio` `[y/N]` prompt (NOT huh), skippable with `--yes`.

### `prexorctl deploy list <group>`
- Purpose: deployment history.
- Usage: `deploy list <group> [flags]` — `ExactArgs(1)`.
- Flags: `--page` (int 1), `--page-size` (int 50, "max 100").
- Does: `GET /api/v1/groups/<group>/deployments?page&pageSize`; table REV/STATE/STRATEGY/TRIGGER/PROGRESS(bar)/CREATED. Prints plain `"No deployments found."` and has **no ListHeader/Footer** unlike other list commands.
- `--json`: yes. Interactive: none.

### `prexorctl deploy show <group> <rev>`
- Purpose: details of one deployment.
- Usage: `deploy show <group> <rev>` — `ExactArgs(2)`.
- Does: `GET /api/v1/groups/<group>/deployments/<rev>`; KV blocks incl. nested `rollout` config.
- `--json`: yes. Interactive: none.

### `prexorctl deploy pause|resume|rollback <group> <rev>`
(generated by `newDeployActionCmd`, `deploy.go:321`)
- Purpose: pause / resume / roll back an in-progress or past deployment.
- Usage: `deploy <action> <group> <rev>` — `ExactArgs(2)`.
- Does: `POST /api/v1/groups/<group>/deployments/<rev>/<action>`.
- `--json`: yes. Interactive: none. **No confirmation** for `rollback` (disruptive). Success line uses `%q` on the group name → prints quotes, unlike every other success message.

---

## `status` (status.go)

### `prexorctl status`
- Purpose: cluster overview dashboard.
- Usage: `status` (no args/flags beyond global).
- Does: `--json` → `GET /api/v1/overview` only, dumped raw. Non-JSON → spinner fetching overview + `GET /api/v1/groups` + best-effort `GET /api/v1/system/version`, then banner, 3-col CLUSTER/NODES/INSTANCES summary, LIVE METRICS card, GROUPS table, footer hints.
- `--json`: yes, **but a strict subset** — JSON returns only the overview object (no groups, no version), so scripts get less than the rendered view shows.
- Interactive: none. No `--watch`/`--refresh`.
- Caveats: `whoami()` is hardcoded `"(authenticated)"` (`status.go:281`) — never the real user. The LIVE METRICS sparklines (TPS, Players, Memory) and the GROUPS `SPARK (1h)` column are **synthetic fabricated data** (`wave`/`sineLike`/`repeatF`, `status.go:145-249`), not real metrics.

---

# FINDINGS

- **`catalog update` can silently wipe fields** (`catalog.go:130-141`): always sends `downloadUrl` and `sha256` in the PATCH body even when those flags are empty, so `catalog update PAPER 1.21 --new-version 1.21.1` (no `--url`) likely blanks the existing URL/checksum. `group update` correctly sends only changed fields; catalog should do the same.
- **`status` shows fake metrics as if real** (`status.go:145-249, 281-287`): TPS/Players/Memory sparklines and the GROUPS `SPARK (1h)` column are synthetic waves; `whoami()` is hardcoded `"(authenticated)"`. Operators can mistake fabricated data for live telemetry.
- **`deploy` PLAN preview is misleading** (`deploy.go:127-133`): `healthcheck` is hardcoded `"on"` regardless of `--health-gate`, and the canary line shows only `--canary-instances`, ignoring `--canary-percent` (shows `0` when percent is used).
- **`deploy` confirm silently no-ops in scripts** (`deploy.go:163-172`): the `[y/N]` prompt reads stdin without a TTY check; in a non-TTY without `--yes`, EOF → "Cancelled" and exit 0 — a deploy that looks like it ran but didn't.
- **`--canary-instances` / `--canary-percent` mutual exclusion not enforced** (`deploy.go:351-352`, `buildDeployBody`): help says mutually exclusive but both are sent if both are set; no client-side check.
- **Confirmation UX is inconsistent**: `group delete` uses a `huh` confirm; `deploy` trigger uses a raw `bufio [y/N]` with `--yes`; `node drain`, `instance stop --force`, `catalog remove`, `template rollback`, and `deploy rollback` have **no confirmation at all** despite being destructive/disruptive.
- **No `--yes`/`--force` bypass on `group delete`** (`group.go:236-248`): the only mandatory huh confirm has no scriptable skip, so `group delete` cannot be automated; in `--json`/non-TTY it will block or fail.
- **`--json` missing where it should exist**: `group delete` (`group.go:220`), `instance stop` (`instance.go:156`), `instance exec` (`instance.go:191`), `node drain`/`node undrain` (`node.go:132,154`), `catalog recommend` (`catalog.go:156`), `catalog remove` (`catalog.go:178`) all emit only a human success line and ignore `flagJSON` — they should return a JSON acknowledgment for scripting.
- **`status --json` is a strict subset of the rendered view** (`status.go:26-32`): returns only `/api/v1/overview`; scripts cannot get the group list or version that the TUI shows.
- **Inconsistent picker support for missing args**: `group`/`instance`/`node`/`catalog` info-style commands use `resolveArg` + interactive pickers, but `instance start <group>`, `instance exec`, `template versions <name>`, `template rollback <name>`, and every `deploy ...` command use `ExactArgs` with **no picker fallback** — inconsistent interactive UX.
- **`group create` hardcodes `jarFile: "server.jar"`** (`group.go:144`) with no override flag, and exposes no flags for `maxPlayers`, `parent`, or `updateStrategy` even though `group info` displays those fields — incomplete scriptable creation.
- **`group update` is narrow** (`group.go:184-205`): can only change min/max/memory/routing/scaling-mode; cannot update templates, platform version, ports, or maxPlayers.
- **`--filter`/`--sort` and `--watch` exist only on `group list`** (`group.go:403-405`); `instance list` and `node list` have neither client-side sort nor watch — inconsistent list ergonomics across sibling commands.
- **`group list --watch` is a crude infinite loop** (`group.go:78-85`): raw `\033[2J\033[H` + `time.Sleep(2s)`, no configurable interval, no clean exit (Ctrl-C only), no "last updated" indicator.
- **Node identifier field mismatch**: `node list` keys the ID off `id` (`node.go:46`) but `node info` heading uses `nodeId` (`node.go:95`); whichever the API omits renders as `-`. Pick one field.
- **`node list` UNREACHABLE is counted as offline** (`node.go:36-44`): the footer switch only handles ONLINE/DRAINING, lumping UNREACHABLE (a documented `--state` value) into the offline tally.
- **`deploy list` output is unthemed/inconsistent** (`deploy.go:240-272`): plain `"No deployments found."` and no `ListHeader`/`ListFooter`, unlike every other list command in this set.
- **`deploy` action success uses `%q`** (`deploy.go:342`): prints the group name in quotes (`Deployment r2 for "lobby": rollback`), cosmetically inconsistent with all other `'%s'` success messages.
- **Two unrelated "rollback" verbs**: `template rollback` (POST template rollback) and `deploy rollback` (deployment action) — potential user confusion; neither is confirmed.
- **No path escaping for names/ids anywhere** (e.g. `group.go:106,208,250,492`, `instance.go:97`, all command files): paths are built by string concatenation (`"/api/v1/groups/"+name`), and `group info` builds `?group=`+name manually instead of `GetWithQuery`. Names/ids with reserved URL characters break or could be injected into the path.
- **`group maintenance` and `group create --scaling-mode`/`--routing`/`catalog --category` accept any string** with no client-side enum validation or shell completion; invalid `--sort` values silently fall through to the default (`group.go:421-428`), and bad maintenance toggles silently mean "off" (`group.go:290`).
- **`template versions` numbering assumes newest-first** (`template.go:83`): `len(versions)-i` produces wrong numbers if the API returns oldest-first; ordering assumption is undocumented.
- **`catalog add --recommended` is non-atomic** (`catalog.go:101-105`): the version is created by the POST even if the follow-up recommend PUT fails, leaving partial state.
- **Mixed typed vs untyped decoding**: `group list`/`catalog list`/`node list` decode into typed structs, while `instance list`/`group info`/`node info`/all `deploy` handlers decode into `map[string]any` (then stringify via `str`/`num`). Inconsistent and loses type safety; only `node list` uses the shared `fetchList` helper.
- **`instance info` drops `deploymentRevision`** (`instance.go:116-122`) even though the field is in `InstanceResponse` — useful for correlating an instance with a deployment.
- **Error-message style is inconsistent**: usage errors are lowercase with backticked examples (`group.go:99`), `requireAuth` uses `--` dashes (`root.go:239`), picker errors are terse (`picker.go:61` "no groups exist yet"). No uniform voice.
- **`PREXOR_OUTPUT=json` globally disables all interactive pickers** (`picker.go:22-27`, `root.go:186`): a user who sets that env for scripting will find every picker silently suppressed and arg-less commands erroring — correct but worth a documented warning.
