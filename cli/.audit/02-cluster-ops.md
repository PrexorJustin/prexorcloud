# prexorctl Audit — Cluster / Ops command groups

Scope: `cluster.go`, `token.go`, `backup.go`, `restore.go`, `diagnostics.go`,
`crash.go`, `logs.go`, `share.go`, `share_cmd.go`. Read-only audit.

## Global context

Every command below runs through `requireAuth()` and inherits the root
persistent flags (`cmd/root.go:177-180`):

- `--json, -j` (bool, default false) — global JSON output toggle (`flagJSON`).
- `--controller, -c` (string) — override target controller.
- `--token, -t` (string) — override auth token.
- `--context` (string) — override active context.

API client notes (`internal/api/client.go`): only GET/HEAD are auto-retried
(3 attempts, exp backoff). POST/PATCH/DELETE are never retried. `GetList`
transparently unwraps both bare arrays and `{data,page,pageSize,total}`
envelopes. Errors surface as `APIError` → `"<message> (HTTP <code>)"`, with
exit codes 2/3/4 for 401/403/404.

---

## `prexorctl cluster` (cluster.go)

Parent: "Manage the controller cluster". Thin wrapper over `/api/v1/cluster/*`.

### `prexorctl cluster status`
- Purpose: show cluster id, member count, active config version.
- Usage: `cluster status` (no args).
- Flags: none beyond globals.
- Does: `GET /api/v1/cluster`; prints clusterId, memberCount,
  activeConfigVersion, createdAt.
- `--json`: yes — prints raw map.
- Interactive: none.

### `prexorctl cluster members`
- Purpose: list controller members.
- Usage: `cluster members` (no args).
- Flags: none beyond globals.
- Does: `GET /api/v1/cluster/members`; table NODE ID / RAFT ADDR / REST ADDR /
  GRPC ADDR / LABEL / JOINED AT from `resp.members[]`.
- `--json`: yes — prints the `members` array (not the envelope).
- Interactive: none.

### `prexorctl cluster eject <nodeId>`
- Purpose: force-remove a controller from the Raft group (irreversible).
- Usage: `cluster eject <nodeId>` (ExactArgs(1)).
- Flags:
  - `--yes` (bool, default false) — skip the interactive confirmation.
  - `--reason` (string, default "") — audit reason recorded with the ejection.
- Does: confirm gate (`confirmDestructive`) unless `--yes`; then
  `DELETE /api/v1/cluster/members/<nodeId>[?reason=...]` (path + reason
  url-escaped). Prints success.
- `--json`: NO — success path prints only a plain success line, ignores
  `flagJSON`.
- Interactive: y/N `confirmDestructive` prompt unless `--yes`.

### `prexorctl cluster leave`
- Purpose: have the targeted controller gracefully leave and shut down.
- Usage: `cluster leave` (no args; no `Args` validator set).
- Flags: `--yes` (bool, default false) — skip confirmation.
- Does: confirm gate unless `--yes`; `POST /api/v1/cluster/leave` with empty
  body; prints "Controller <nodeId> leaving cluster <clusterId>".
- `--json`: yes.
- Interactive: y/N confirm unless `--yes`.

### `prexorctl cluster join-token` (parent)
Manage cluster join tokens (controller-to-controller).

#### `prexorctl cluster join-token create`
- Purpose: issue a new cluster join token; prints the wire token ONCE.
- Usage: `cluster join-token create` (no args).
- Flags:
  - `--ttl-seconds` (int, default 86400) — token TTL in seconds.
  - `--label` (string, default "") — human label.
  - `--join-addr` (stringSlice, default nil) — existing controller gRPC
    host:port; repeatable. **Required** (errors if empty).
- Does: `POST /api/v1/cluster/join-tokens` with `{ttlSeconds, joinAddrs[,
  label]}`; prints JTI, Token, Expires at + a "only shown once" warning.
- `--json`: yes — prints full result (token included).
- Interactive: none.

#### `prexorctl cluster join-token list`
- Purpose: list outstanding cluster join tokens.
- Usage: no args.
- Flags: none beyond globals.
- Does: `GET /api/v1/cluster/join-tokens`; table JTI / LABEL / STATUS /
  CREATED AT / EXPIRES AT from `resp.tokens[]`.
- `--json`: yes — prints the `tokens` array.
- Interactive: none.

#### `prexorctl cluster join-token revoke <jti>`
- Purpose: revoke an outstanding cluster join token.
- Usage: `cluster join-token revoke <jti>` (ExactArgs(1)).
- Flags: none beyond globals.
- Does: `DELETE /api/v1/cluster/join-tokens/<jti>` (jti NOT url-escaped);
  prints success.
- `--json`: NO.
- Interactive: NONE — no confirmation gate (revocation is destructive but
  unguarded; contrast with `share revoke`).

### `prexorctl cluster seed` (parent)
Manage the cluster seed secret (HMAC key for join tokens).

#### `prexorctl cluster seed rotate`
- Purpose: rotate the seed; invalidates all outstanding tokens.
- Usage: no args.
- Flags: `--yes` (bool, default false) — skip confirmation.
- Does: confirm gate unless `--yes`; `POST /api/v1/cluster/seed/rotate`;
  prints "Seed rotated for cluster <id> by <user> at <ts>".
- `--json`: yes.
- Interactive: y/N confirm unless `--yes`.

### `prexorctl cluster recover`
- Purpose: recover a degraded cluster — quorum-preserved shrink or
  catastrophic single-survivor reset.
- Usage: `cluster recover` (no args).
- Flags:
  - `--eject` (stringSlice, default nil) — dead nodeIds to eject; skips the
    interactive prompt.
  - `--i-have-only-survivor` (bool, default false) — print the catastrophic
    reset playbook (quorum lost). **Danger gate / mode switch.**
  - `--yes` (bool, default false) — skip the confirmation.
- Does:
  - If `--i-have-only-survivor`: prints the offline single-survivor reset
    playbook (or JSON if `--json`). No API calls, no FS surgery — guidance only.
  - Else (quorum-preserved): `GET /api/v1/cluster/members`. If no `--eject`,
    prints members and prompts on stdin for comma-separated nodeIds (blank =
    abort). Then confirm gate unless `--yes`. Loops
    `DELETE /api/v1/cluster/members/<id>?reason=cluster+recover` per id,
    collecting failures; suggests `cluster seed rotate` at the end.
- `--json`: only meaningful in the `--i-have-only-survivor` playbook branch;
  the quorum-preserved branch ignores `--json` entirely (prints styled text
  + interactive prompt, never JSON).
- Interactive: stdin prompt for dead nodeIds (when `--eject` absent) + y/N
  confirm. Note the nodeIds entered interactively are NOT url-escaped before
  being interpolated into the DELETE path.

---

## `prexorctl token` (token.go)

Parent: "Manage node join tokens" — **node** (daemon) join tokens via
`/api/v1/admin/tokens`. Distinct surface from `cluster join-token`.

### `prexorctl token create`
- Purpose: create a new node join token.
- Usage: no args.
- Flags:
  - `--node` (string, default "") — node id for the token (optional; omitted
    from body if empty).
  - `--ttl` (string, default "1h") — TTL like `1h`, `24h`.
- Does: `POST /api/v1/admin/tokens` with `{nodeId?, ttl?}`; prints Token ID,
  Join Token, Node ID, Expires At.
- `--json`: yes.
- Interactive: none.

### `prexorctl token list`
- Purpose: list node join tokens.
- Usage: no args.
- Flags: none beyond globals.
- Does: `GET /api/v1/admin/tokens` (via GetList); table TOKEN ID / NODE /
  EXPIRES AT / STATUS.
- `--json`: yes.
- Interactive: none.

### `prexorctl token revoke <id>`
- Purpose: revoke a node join token.
- Usage: ExactArgs(1).
- Flags: none beyond globals.
- Does: `DELETE /api/v1/admin/tokens/<id>` (id NOT url-escaped); prints success.
- `--json`: NO.
- Interactive: NONE — no confirmation gate.

---

## `prexorctl backup` (backup.go)

Parent: "Manage controller backups (create, list, verify, prune)". Bundles
live on the controller host; the CLI never transports them.

### `prexorctl backup create`
- Purpose: create a new backup bundle (Mongo + Redis + on-disk state).
- Usage: NoArgs.
- Flags: none beyond globals.
- Does: `POST /api/v1/backups`; prints id, human size, mongo doc count, redis
  key count, file count.
- `--json`: yes (raw manifest).
- Interactive: none.

### `prexorctl backup list`
- Purpose: list backups stored on the controller.
- Usage: NoArgs.
- Flags: none beyond globals.
- Does: `GET /api/v1/backups`; prints Directory + Retention then a table
  ID/Created/Size/Mongo Docs/Redis Keys/Files from `resp.items[]`.
- `--json`: yes (full envelope).
- Interactive: none.

### `prexorctl backup verify <id>`
- Purpose: verify a bundle is restorable.
- Usage: ExactArgs(1).
- Flags: none beyond globals.
- Does: `POST /api/v1/backups/<id>/verify`. If `valid` prints success; else
  prints the missing-files/dirs/collections/prefixes/empty lists and returns
  a non-nil error (`backup verification failed`).
- `--json`: yes (raw resp). Note: in JSON mode an INVALID bundle still
  returns nil (no error), unlike the text path which errors.
- Interactive: none.

### `prexorctl backup prune`
- Purpose: delete backups beyond retention.
- Usage: NoArgs.
- Flags: `--keep` (int, default 0) — keep this many recent; 0 = server
  retentionCount default.
- Does: `POST /api/v1/backups/prune[?keep=N]`; prints removed ids or
  "No backups pruned".
- `--json`: yes.
- Interactive: NONE — destructive deletion with no confirm gate.

### `prexorctl backup delete <id>` (alias `rm`)
- Purpose: delete a single backup by id.
- Usage: ExactArgs(1).
- Flags: none beyond globals.
- Does: `DELETE /api/v1/backups/<id>` (id NOT url-escaped); prints success.
- `--json`: NO.
- Interactive: NONE — no confirm gate.

---

## `prexorctl restore <id>` (restore.go)

- Purpose: restore the controller from a backup bundle.
- Usage: `restore <id>` (ExactArgs(1)).
- Flags:
  - `--dry-run` (bool, default false) — validate + report planned changes,
    no writes.
  - `--filesystem` (bool, default **true**) — restore on-disk filesystem.
  - `--datastores` (bool, default **true**) — restore Mongo + Redis.
- Does: `POST /api/v1/restore` with `{id, dryRun, filesystem, datastores}`.
  Prints "applied"/"dry-run", filesystem entry count + rollback snapshot,
  datastore counts. Rejected server-side if the bundle fails verification.
- `--json`: yes (raw resp).
- Interactive: NONE — a full live restore (overwrites Mongo/Redis/FS) runs
  with NO confirmation gate and NO `--yes`. The Long help references
  `--no-files` / `--no-data` which **do not exist** (the actual flags are
  `--filesystem`/`--datastores`).

---

## `prexorctl diagnostics` / `diag` (diagnostics.go)

### `prexorctl diagnostics bundle`
- Purpose: collect a redacted diagnostics bundle (tar.gz) locally.
- Usage: NoArgs.
- Flags:
  - `--out, -o` (string, default "") — output path; default
    `./prexorctl-diag-<timestamp>.tar.gz`.
  - `--log-lines` (int, default 500) — recent log lines to include; 0 skips.
  - share flags: `--share`, `--expiry`, `--public`, `--burn-after-read`
    (see Share flags below).
- Does:
  - If `--share`: `POST /api/v1/system/diagnostics/share` and prints the link.
    With `--share` + `--out`, also continues to write the local bundle; with
    `--share` and no `--out`, returns after sharing.
  - Local path: `GET /api/v1/system/diagnostics` (+ optional
    `GET /api/v1/system/logs?level=DEBUG&limit=<log-lines>`), writes a tar.gz
    with manifest/readiness/overview/settings/config/redis/leases/logs files
    to disk. Prints path + size.
- `--json`: NOT honored on the local-write success path (always prints a
  styled success line). Honored only inside the `--share` result and the
  catastrophic playbook elsewhere.
- Interactive: none.
- Side effects: writes a tar.gz file to the local filesystem.

---

## `prexorctl crash` (crash.go)

### `prexorctl crash list`
- Purpose: list crash reports.
- Usage: no args.
- Flags:
  - `--group` (string) — filter by group.
  - `--node` (string) — filter by node.
  - `--since` (string) — ISO 8601; sent as query param `from`.
- Does: `GET /api/v1/crashes?group=&node=&from=` (via GetList); table
  ID/INSTANCE/GROUP/NODE/EXIT/CLASS/CRASHED AT/UPTIME.
- `--json`: yes.
- Interactive: none.

### `prexorctl crash info [id]`
- Purpose: show crash details (context card + last log lines).
- Usage: MaximumNArgs(1); id optional (picker when omitted in a TTY).
- Flags: share flags (`--share`, `--expiry`, `--public`, `--burn-after-read`).
- Does:
  - If `--share`: `POST /api/v1/crashes/<id>/share` and prints link.
  - Else: `GET /api/v1/crashes/<id>`; prints crash report card + LAST LOG LINES.
- `--json`: yes (raw crash), except when `--share` is set (then share result).
- Interactive: when id omitted in a TTY, `pickCrash` selector. Non-TTY/`--json`
  with no id → usage error.

---

## `prexorctl logs` (logs.go)

Persistent flags on `logsCmd` (inherited by all subcommands):
- `--follow, -f` (bool, default false) — live tail view.
- `--tail, -n` (int, default 200) — recent records before streaming.
- `--level` (string, default "INFO") — min level TRACE/DEBUG/INFO/WARN/ERROR.
- `--logger` (string, default "") — only loggers with this prefix.

### `prexorctl logs` (bare)
- Purpose: pick what to tail.
- Behavior: in a TTY (and not `--json`) opens a 4-way chooser (controller /
  daemon / instance / all) and forces `--follow=true` unless already set;
  delegates to the chosen subcommand. Non-interactive → falls through to
  `logs controller`.

### `prexorctl logs controller`
- Usage: NoArgs.
- Flags: persistent + share flags.
- Does:
  - `--share`: rejects if `--follow` (`--share cannot be combined with
    --follow`); else `POST /api/v1/system/logs/share`.
  - Non-follow: `GET /api/v1/system/logs?level=&logger=&limit=<tail>`; prints
    records (or JSON if `--json`).
  - Follow: seeds with the fetched page then SSE
    `/api/v1/system/logs/stream` (ticket `/api/v1/system/logs/ticket`) in the
    bubbletea tail view.
- `--json`: yes ONLY when not `--follow`. In follow mode `--json` is silently
  ignored (renders the TUI).

### `prexorctl logs daemon [node-id]`
- Usage: MaximumNArgs(1); node id optional (picker when omitted in a TTY).
- Flags: persistent + share flags.
- Does: same shape as controller but `/api/v1/nodes/<id>/logs[/share|/stream|
  /ticket]`.
- `--json`: yes when not `--follow`.
- Interactive: `pickNode` when id omitted in a TTY.

### `prexorctl logs instance [id]`
- Usage: MaximumNArgs(1); id optional (picker when omitted in a TTY).
- Flags: persistent + share flags. NOTE: `--level`/`--logger` are accepted
  (persistent) but ignored by this subcommand.
- Does:
  - `--share`: rejects with `--follow`; else
    `POST /api/v1/services/<id>/console/share` (limit=tail).
  - Non-follow: `GET /api/v1/services/<id>/console/history?limit=<tail>`.
  - Follow: SSE `/api/v1/services/<id>/console` (read-only tail).
- `--json`: yes for the history (non-follow) path.
- Interactive: `pickInstance` when id omitted in a TTY.

### `prexorctl logs all`
- Purpose: merged live tail of every instance console, colored per-instance
  prefixes.
- Usage: NoArgs.
- Flags: `--group` (string) and `--node` (string) filters (local to this
  subcommand) + inherited persistent flags.
- Does: `GET /api/v1/services`, filters by group/node (case-insensitive),
  errors if none match, then fans out one SSE console stream per instance into
  the merged bubbletea view.
- `--json`: NO — always a TUI; `--json` ignored. `--follow`/`--tail` also
  ineffective here.
- Interactive: full-screen TUI.

---

## `prexorctl share` / `shares` (share_cmd.go) + share flags (share.go)

### Share flags (registered on crash info, logs controller/daemon/instance,
diagnostics bundle)
- `--share` (bool, default false) — upload a redacted copy to the configured
  paste service and print the link.
- `--expiry` (string, default "") — preset `1h | 1d | 30d | never`. Not
  validated client-side; passed through (omitempty).
- `--public` (bool, default false) — mark paste public (inverts to
  `isPrivate=false`); only sent if the flag was explicitly changed.
- `--burn-after-read` (bool, default false) — destroy on first read; only sent
  if explicitly changed.
- `mapShareError` rewrites `SHARE_DISABLED`/409 → "sharing is not configured",
  `PASTE_UPSTREAM_ERROR`/502 → "paste service unreachable".

### `prexorctl share list`
- Purpose: list recent shares (newest first).
- Usage: no args.
- Flags:
  - `--kind` (string, default "") — CRASH | CONTROLLER_LOGS | DAEMON_LOGS |
    DIAGNOSTICS | INSTANCE_CONSOLE (upcased client-side).
  - `--active-only` (bool, default false) — hide revoked.
  - `--limit` (int, default 50) — max rows (server caps at 200); sent as
    `pageSize`.
- Does: `GET /api/v1/shares?kind=&activeOnly=&pageSize=`; table
  ID/KIND/WHEN/BY/BYTES/URL/STATUS.
- `--json`: yes (full page envelope).
- Interactive: none.

### `prexorctl share view <id>`
- Purpose: view a single share.
- Usage: ExactArgs(1).
- Flags: none beyond globals.
- Does: `GET /api/v1/shares/<id>` (url-escaped); prints fields + revocability
  hint.
- `--json`: yes.
- Interactive: none.

### `prexorctl share revoke <id>`
- Purpose: revoke a paste share (pste DELETE + mark record revoked).
- Usage: ExactArgs(1).
- Flags: `--yes, -y` (bool, default false) — skip confirmation.
- Does: unless `--yes`, first `GET /api/v1/shares/<id>` and prompts
  "Type yes to confirm" (uses `fmt.Scanln`); then
  `POST /api/v1/shares/<id>/revoke`; prints success.
- `--json`: yes (revoked record).
- Interactive: typed-"yes" confirmation unless `--yes`. This is the ONLY
  confirmation style of its kind (others use `confirmDestructive` y/N).

---

# FINDINGS

- **`restore` has no confirmation gate or `--yes`** (`restore.go:19-52`). A
  full live restore overwrites Mongo + Redis + on-disk FS with zero
  interactive guard, unlike every `cluster` destructive command. Highest-risk
  command in the set; should require `confirmDestructive`/`--yes`.
- **`restore` Long help documents flags that don't exist** (`restore.go:14-15`):
  references `--no-files` / `--no-data`, but the real flags are
  `--filesystem` / `--datastores` (`restore.go:67-69`). Misleading help.
- **`backup prune` deletes with no confirm gate** (`backup.go:130-162`) and
  **`backup delete`/`rm` also has none** (`backup.go:164-180`). Destructive,
  no `--yes`.
- **Inconsistent confirmation idioms across destructive commands:**
  `cluster eject/leave/seed rotate/recover` use `confirmDestructive` y/N
  (`cluster.go:408`), `share revoke` uses a typed-"yes" `fmt.Scanln`
  (`share_cmd.go:164-167`), while `cluster join-token revoke`
  (`cluster.go:227-242`), `token revoke` (`token.go:91-108`), `backup
  prune/delete` (`backup.go`), and `restore` have NO gate. Three different
  behaviors for comparable risk.
- **`--json` not honored on several success paths**, breaking scripting:
  `cluster eject` (`cluster.go:114`), `cluster join-token revoke`
  (`cluster.go:239`), `token revoke` (`token.go:105`), `backup delete`
  (`backup.go:177`), and `diagnostics bundle` local-write path
  (`diagnostics.go:96`) all print plain text even under `--json`.
- **`backup verify --json` swallows the failure signal** (`backup.go:111-127`):
  the text path returns a non-nil error for an INVALID bundle, but the JSON
  path returns nil after printing — a script using `--json` sees exit 0 for an
  invalid backup.
- **`logs all` silently ignores `--json`, `--follow`, `--tail`**
  (`logs.go:493-570`): it is always a TUI. No non-interactive/scriptable way to
  capture a merged tail; in a pipe it still tries to render bubbletea.
- **`logs instance` accepts but ignores `--level`/`--logger`**
  (`logs.go:391-428`): these are persistent flags on `logsCmd` but the console
  history/stream endpoints don't use them — flags that appear in `--help` and
  do nothing.
- **Path-injection / no url-escaping on several id args:** `cluster join-token
  revoke <jti>` (`cluster.go:236`), `token revoke <id>` (`token.go:101`),
  `backup verify/delete <id>` (`backup.go:108,174`), `crash info <id>`
  (`crash.go:100`), `logs daemon <node-id>` (`logs.go:321,334,367`) and the
  interactively-entered nodeIds in `cluster recover` (`cluster.go:344`) are all
  interpolated into the URL path without `url.PathEscape`. `cluster eject` and
  the `share` commands DO escape — inconsistent.
- **`cluster recover` quorum-preserved branch ignores `--json`**
  (`cluster.go:298-356`): only the `--i-have-only-survivor` playbook branch
  honors JSON. The normal path prints styled text and reads from stdin, so it
  can't be driven non-interactively except via `--eject` + `--yes` (which then
  emits unstructured success/error text).
- **No `--yes` plumb-through for `cluster recover` interactive prompt corner
  case:** when `--eject` is omitted in a non-TTY, the stdin read returns ""
  immediately and aborts with "no peers ejected" rather than a clear "no TTY,
  pass --eject" error (`cluster.go:312-335`).
- **`crash list --since` flag name vs wire param mismatch** (`crash.go:27-33`):
  the flag is `--since` but it is sent as query param `from`. Cosmetic but a
  trap for anyone correlating with server logs; also no validation of the ISO
  8601 input.
- **`diagnostics bundle --share` + no `--out` discards the bundle silently**
  (`diagnostics.go:54-67`): returns right after sharing. Behavior is reasonable
  but undocumented in help — `--share` quietly changes whether a local file is
  produced.
- **`cluster join-token create` requires `--join-addr` but the requirement is
  runtime-only** (`cluster.go:163-165`): not marked `MarkFlagRequired`, so the
  error surfaces only after auth round-trip setup; help doesn't show it as
  required.
- **Two near-identical "join token" command trees with no cross-reference:**
  `cluster join-token` (`/api/v1/cluster/join-tokens`, controller-to-controller)
  vs top-level `token` (`/api/v1/admin/tokens`, node/daemon). Short help for
  neither disambiguates; easy to invoke the wrong one.
- **`share list --limit` help says "server caps at 200" but client doesn't
  enforce/clamp** (`share_cmd.go:189`) — fine, but `--limit 0` silently sends
  no pageSize (server default), which differs from the documented default 50.
- **`diagnostics bundle` writes the output file before reporting any partial
  failure**, and `os.Create` truncates an existing file at the `--out` path
  with no overwrite guard (`diagnostics.go:107`). Re-running with a fixed
  `--out` silently clobbers a prior bundle.
- **Weak/!meaningful help on `cluster leave`**: no `Args` validator, so extra
  positional args are silently ignored (`cluster.go:119-143`); same for several
  NoArgs-intended commands that don't set `cobra.NoArgs` (e.g. status, members,
  join-token list).
