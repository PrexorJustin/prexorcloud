# prexorctl deep review — 05: HA control plane & ops

Scope: `cluster` (status/members/eject/leave/join-token {create,list,revoke}/seed
rotate/recover), `token` (create/list/revoke), `backup` (create/list/verify/prune/
delete), `restore`, `diagnostics bundle`, `crash` (list/info).

Sources: `cmd/cluster.go`, `cmd/token.go`, `cmd/backup.go`, `cmd/restore.go`,
`cmd/diagnostics.go`, `cmd/crash.go`, `internal/api/client.go`,
server `rest/route/ClusterMembersRoutes.java`.

Verified server facts grounding this pass:
- `GET /api/v1/cluster` returns `{clusterId, createdAt, schemaVersion,
  memberCount, activeConfigVersion}` — **no leader id, no term, no quorum
  size, no "this node" identity** (`ClusterMembersRoutes.java:59-72`).
- `memberJson()` returns `{nodeId, raftAddr, restAddr, gRPCAddr, label,
  joinedAt, lastSeen}` — **no role (LEADER/FOLLOWER/CANDIDATE), no
  voting/non-voting flag, no health/reachable, no matchIndex/commitIndex
  lag** (`ClusterMembersRoutes.java:223-233`). `isLeader()` exists internally
  but is not on the wire.
- POST/PATCH/DELETE are **never retried**; only GET/HEAD retry (3x, exp
  backoff) and only on 408/429/5xx (`client.go:33-50,187-191`). 503 is
  retryable for reads; **a quorum-loss write that HANGS (no 503) is bounded
  only by the 30s client timeout** (`client.go:88`).
- These are the most destructive commands in the CLI and several have **no
  danger gate at all**.

---

## `prexorctl cluster status`
Current: `GET /api/v1/cluster`; prints clusterId, member count, active config
version, createdAt.

**Verdict:** IMPROVE — best-in-class target: an `etcdctl endpoint status`
/ `consul operator raft list-peers`-grade health view that names the leader,
shows quorum margin, and tells me *which node I'm talking to* and *whether it's
the leader*.

**Findings:**
- [3][table-stakes][needs-server] Status answers "how many members" but not the
  only questions that matter in an incident: **who is the leader, do we have
  quorum, how many votes from loss-of-quorum, what term**. `memberCount` alone
  cannot distinguish a healthy 3/3 from a doomed 3-member group with 2 dead.
  Server must surface `leaderId`, `quorumSize`, `votingMembers`, `term`,
  `selfNodeId`, `selfIsLeader`.
- [3][table-stakes][needs-server] No identity of the answering node. Under
  any-member failover the operator cannot tell which controller served the
  read; add `servedBy`/`selfNodeId` to the body and echo it in the footer.
- [6][modern][client-only] Human output is a flat KV list with no health
  verdict. Render a one-line headline pill (`HEALTHY 3/3` / `DEGRADED 2/3 —
  1 vote from quorum loss` / `QUORUM LOST`) computed from member health, the
  way `cluster status` should read at 3am.
- [4][modern][client-only] No `--watch`: an operator babysitting a join/leave/
  failover has to re-run in a loop. Add `--watch`/`-w` (re-poll + redraw) like
  `kubectl get -w`.
- [1][table-stakes][client-only] No `cobra.NoArgs`; stray positional args are
  silently ignored. Set the validator.
- [13][innovative][needs-server] `status` and `members` and `leases` are three
  round-trips for one mental model ("is the control plane healthy?"). A
  `cluster health`/`describe` that fuses status + per-member role/health +
  lease holders into one card (etcdctl `endpoint health` + `move-leader`
  context) would be the headline command operators actually want.

## `prexorctl cluster members`
Current: `GET /api/v1/cluster/members`; table NODE ID / RAFT ADDR / REST ADDR /
GRPC ADDR / LABEL / JOINED AT.

**Verdict:** IMPROVE — target: `consul operator raft list-peers` parity —
every member with **role, voting status, leader marker, reachability, and
replication lag**.

**Findings:**
- [3][table-stakes][needs-server] The table shows addresses but **omits role
  and health** — the two columns an operator scans first. There is no `★`/
  `LEADER` marker, no `FOLLOWER`/`LEARNER`, no `reachable` flag. Add ROLE and
  STATUS columns (needs the server to emit them per the verified gap above).
- [6][table-stakes][client-only] The payload already includes `lastSeen` but
  the table **drops it** (`cluster.go:70-81`) — yet `cluster recover` prints
  exactly that field as the staleness signal (`cluster.go:317-320`). Surface
  `lastSeen` as a "LAST SEEN" column so `members` alone shows which peer is
  dead.
- [6][modern][client-only] No `-o wide|name|yaml|jsonpath`; only table or raw
  `--json` of the inner array. A scripting caller wanting just nodeIds
  (`-o name`) to feed `cluster eject` has to pipe through `jq`. Adopt the
  cross-cutting `-o` system.
- [4][modern][client-only] No `--watch`. Joining a controller is the textbook
  case for watching the member list converge.
- [1][table-stakes][client-only] No `cobra.NoArgs`.

## `prexorctl cluster eject <nodeId>`
Current: y/N confirm unless `--yes`; `DELETE /api/v1/cluster/members/<id>
[?reason=]`; nodeId + reason url-escaped.

**Verdict:** IMPROVE — target: a quorum-aware destructive op with typed
confirm, `--dry-run`, and a quorum-math warning (kubectl drain / etcdctl
member remove safety).

**Findings:**
- [10][table-stakes][needs-server] **No quorum-safety check.** Ejecting a
  member from an N=3 group with one already dead drops you to a 1-of-3 voting
  set and can brick quorum — exactly the "leave-orphan split-brain" class that
  already corrupted a survivor in the 8E run. Eject should refuse (or hard-gate
  with a distinct typed confirm) when removal would cross the quorum threshold,
  and the server should report the resulting voting count.
- [10][modern][client-only] The y/N `confirmDestructive` is the weakest gate
  for the single most dangerous routine op. For an irreversible Raft removal,
  require typed-confirm of the nodeId (`gh`/`stripe` pattern: type the resource
  name), not a one-key `y`.
- [4][modern][needs-server] No `--dry-run` to preview "after eject: 2 voting
  members, quorum=2, margin=0". etcdctl-style preview before a destructive
  membership change is table stakes for this blast radius.
- [6][table-stakes][client-only] Success path **ignores `--json`** — prints
  only `theme.PrintSuccess` (`cluster.go:114`). A script ejecting members can't
  capture structured confirmation. Emit `{nodeId, ejected:true, reason}` under
  `--json`.
- [8][modern][client-only] No "did you mean" / membership validation client-
  side; a typo'd nodeId round-trips to a 404 (server does check, returns
  `MEMBER_NOT_FOUND`). Could pre-validate against `members` and offer the
  closest match.

## `prexorctl cluster leave`
Current: y/N confirm unless `--yes`; `POST /api/v1/cluster/leave`; prints
"Controller <id> leaving cluster <id>".

**Verdict:** IMPROVE — target: self-drain with quorum guard, identity echo,
and `--wait` for the leave to commit.

**Findings:**
- [3][table-stakes][client-only] **The command leaves *whichever controller the
  context points at* — and that identity is invisible.** There is no preflight
  "you are about to remove ctrl-2 (10.0.0.2)"; the operator only learns the
  nodeId from the *success* line, after it's done. Print the target identity in
  the confirm prompt.
- [4][modern][needs-server] Returns as soon as the leave is *initiated*; the
  controller "shuts down after the leave commits" (server comment). No
  `--wait`/`--timeout` to confirm the membership change committed before the
  process dies. An async shutdown with no convergence signal is a foot-gun in
  automation.
- [1][table-stakes][client-only] No `Args` validator — extra positional args
  silently ignored (`cluster.go:119-143`).
- [10][modern][client-only] Same weak y/N gate as eject for an irreversible op;
  consider typed-confirm of the cluster id.

## `prexorctl cluster join-token create`
Current: `POST /api/v1/cluster/join-tokens` with `{ttlSeconds, joinAddrs[,
label]}`; prints JTI/Token/Expires + "shown once".

**Verdict:** IMPROVE — target: a copy-pasteable join recipe (the full peer
command), with `--join-addr` properly declared required.

**Findings:**
- [5][table-stakes][client-only] `--join-addr` is **required at runtime only**
  (`cluster.go:163-165`), not via `MarkFlagRequired`. Help doesn't show it as
  required and the error fires only after the auth round-trip. Mark it required.
- [5][table-stakes][client-only] `--ttl-seconds` is raw seconds; every sibling
  (`token create --ttl 24h`) takes a Go duration string. Inconsistent input
  model — accept `--ttl 24h` and keep `--ttl-seconds` as a hidden alias, or
  unify on a duration.
- [9][modern][client-only] The token prints alone. Best-in-class (k3s/k0s/nomad
  `server join`) prints the **exact command the new controller runs** — e.g.
  `prexorcloud-controller join --addr <one of joinAddrs> --token <token>` — so
  the operator copies one line instead of reconstructing it.
- [6][table-stakes][client-only] Secret token goes to stdout intermixed with
  styled chatter; under `--json` it's fine, but in human mode there's no
  `--token-only`/quiet form to capture just the secret into a var without the
  banner.
- [5][modern][client-only] No `--ttl` upper-bound hint or default-visibility;
  86400s default is invisible in the one-line help.

## `prexorctl cluster join-token list`
Current: `GET /api/v1/cluster/join-tokens`; table JTI/LABEL/STATUS/CREATED/
EXPIRES.

**Verdict:** KEEP (minor) — target: same table plus relative expiry.

**Findings:**
- [6][modern][client-only] Absolute timestamps only; add relative ("expires in
  3h") which is what operators reason about for short-TTL join windows.
- [1][table-stakes][client-only] No `cobra.NoArgs`.
- [13][modern][client-only] No `-o name` to pipe JTIs into `revoke`.

## `prexorctl cluster join-token revoke <jti>`
Current: `DELETE /api/v1/cluster/join-tokens/<jti>` (jti **not** url-escaped);
prints success; **no gate, no `--json`**.

**Verdict:** IMPROVE — target: consistent danger-gate + escaped path +
structured output.

**Findings:**
- [10][table-stakes][client-only] **No confirmation gate** for a destructive
  revoke, while `share revoke` (comparable risk) hard-prompts. Inconsistent
  danger model CLI-wide.
- [6][table-stakes][client-only] Success path ignores `--json` (`cluster.go:239`).
- [10][table-stakes][client-only] `jti` is interpolated into the path **without
  `url.PathEscape`** (`cluster.go:236`), unlike `cluster eject` two functions
  up. Path-injection inconsistency.
- [2][modern][client-only] No `revoke --all`/`--expired` to clear stale tokens
  in one shot.

## `prexorctl cluster seed rotate`
Current: y/N confirm unless `--yes`; `POST /api/v1/cluster/seed/rotate`; prints
who/when.

**Verdict:** KEEP — target: keep, but make it discoverable as part of recovery
flows.

**Findings:**
- [9][modern][client-only] Good gate + `--json`. But it's a leaf under
  `cluster seed` with one verb; consider `cluster seed status` (when rotated,
  by whom) for symmetry, since rotation is a security event with no read side.
- [10][modern][client-only] y/N gate is acceptable here (idempotent-ish,
  recoverable by re-issuing tokens) — note the contrast with eject/leave which
  deserve *stronger* gates than this one currently has parity with.

## `prexorctl cluster recover`
Current: two modes — `--i-have-only-survivor` prints an offline reset playbook
(guidance only); otherwise lists members, prompts for dead nodeIds, loops
`DELETE …?reason=cluster+recover`.

**Verdict:** IMPROVE — target: a quorum-aware guided recovery that is fully
scriptable and never blind-deletes.

**Findings:**
- [10][table-stakes][client-only] Interactively-entered nodeIds are
  interpolated into the DELETE path **without url-escaping** (`cluster.go:344`),
  unlike `cluster eject`.
- [7][table-stakes][client-only] In a **non-TTY without `--eject`**, the stdin
  read returns "" instantly and aborts with "no peers ejected" instead of a
  clear "no TTY — pass `--eject`" error (`cluster.go:312-335`). Hostile to
  automation.
- [6][table-stakes][client-only] The quorum-preserved branch **ignores `--json`
  entirely** (`cluster.go:298-356`); only the playbook branch emits JSON. The
  per-id eject results (success/fail) are unstructured text, so a recovery
  script can't parse partial failure.
- [4][modern][client-only] Partial-failure handling exists (collects `failed`)
  but there's no idempotent re-run guard — re-running re-DELETEs already-gone
  members and surfaces their 404s as failures. Treat `MEMBER_NOT_FOUND` as
  already-converged.
- [10][modern][needs-server] Recover blind-deletes whatever the operator types;
  it never cross-checks `lastSeen` to confirm the targets are actually dead, nor
  warns if the ejections would themselves cross quorum. This is the command most
  likely to *cause* the catastrophe it's meant to fix.
- [9][modern][client-only] `--i-have-only-survivor` is pure guidance (no FS
  surgery, by design) — good — but the flag name is awkward and the playbook
  references `docs/runbooks/recover-cluster.md` that the operator must open
  anyway. Consider `cluster recover --quorum-lost` as the verb and inline the
  step that's safe to automate (the post-reset `seed rotate` + `join-token
  create`).

## `prexorctl token {create,list,revoke}`
Current: node/daemon join tokens via `/api/v1/admin/tokens`.

**Verdict:** IMPROVE — target: clearly disambiguated from `cluster join-token`,
with consistent gating/escaping.

**Findings:**
- [2][table-stakes][client-only] **Two near-identical "join token" trees with
  zero cross-reference:** top-level `token` (node/daemon, `/admin/tokens`) vs
  `cluster join-token` (controller-to-controller, `/cluster/join-tokens`).
  Neither short help disambiguates; trivially easy to mint the wrong kind. At
  minimum rename to `token` → `node-token` (or `daemon token`) and add "see
  also" lines; ideally unify under `prexorctl token --kind node|controller`.
- [10][table-stakes][client-only] `token revoke <id>` has **no gate** and the
  id is **not url-escaped** (`token.go:101`); success path **ignores `--json`**
  (`token.go:105`). Same trio of defects as `cluster join-token revoke`.
- [6][modern][client-only] `token create --ttl 1h` uses a duration string —
  good — but this is *inconsistent with* `cluster join-token create
  --ttl-seconds`. Pick one TTL grammar CLI-wide.
- [5][modern][client-only] `token list` has no `--node`/`--status` filter and
  no `-o name`; can't easily script "revoke all expired."

## `prexorctl backup create`
Current: `POST /api/v1/backups`; prints id/size/doc-count/key-count/file-count.

**Verdict:** IMPROVE — target: `etcdctl snapshot save` parity — a structured,
verifiable manifest with a `--wait`-free synchronous guarantee and off-host
hint.

**Findings:**
- [4][modern][needs-server] No way to know if the bundle is *consistent* at
  create time — verification is a separate command. `etcdctl snapshot save`
  emits hash + revision inline. Surface a content hash / verify-on-create so a
  backup is trustworthy in one step.
- [9][modern][client-only] `--out`/off-host transport is documented as "copy it
  yourself with restic/rclone." That's a real gap for a backup tool — consider
  `backup pull <id> -o file.tar.gz` (download) so the CLI can actually get the
  bundle off the controller host (currently impossible via prexorctl).
- [5][modern][client-only] No `--label`/`--note` to tag a backup (e.g.
  "pre-v1.1-upgrade"). Manifests are anonymous ids.
- [1][table-stakes] Good: `cobra.NoArgs`, `--json`, error wrapping.

## `prexorctl backup list`
Current: `GET /api/v1/backups`; Directory + Retention header then a table.

**Verdict:** KEEP (minor).

**Findings:**
- [6][modern][client-only] Table has no `AGE` column (relative) and no
  verified/unverified status; an operator can't tell which bundles are known-
  restorable. Fuse last-verify result if the server tracks it.
- [13][modern][client-only] No `-o name`/jsonpath for scripting "verify the
  newest backup" without `jq`.

## `prexorctl backup verify <id>`
Current: `POST /api/v1/backups/<id>/verify`; text path errors on INVALID, JSON
path does not.

**Verdict:** IMPROVE — target: a verification that fails the exit code in every
output mode.

**Findings:**
- [8][table-stakes][client-only] **`--json` swallows the failure signal**: an
  INVALID bundle returns nil (exit 0) under `--json` while the text path returns
  a non-nil error (`backup.go:111-127`). A CI gate using `--json` greenlights a
  corrupt backup. Set the error in both modes.
- [10][table-stakes][client-only] `id` not url-escaped (`backup.go:108`).
- [6][modern][client-only] The missing-files/collections lists print only in
  text mode; fine, but the human output has no summary count ("3 missing files,
  1 empty"). Add a one-line verdict.

## `prexorctl backup prune`
Current: `POST /api/v1/backups/prune[?keep=N]`; lists removed ids.

**Verdict:** IMPROVE — target: a gated, dry-runnable retention sweep.

**Findings:**
- [10][table-stakes][client-only] **Destructive deletion with no confirm gate
  and no `--yes`** (`backup.go:130-162`). Deletes backups irreversibly on a bare
  invocation. Add the standard gate.
- [4][table-stakes][client-only] No `--dry-run` to preview which bundles would
  be pruned before they're gone. For a retention sweep this is table stakes.
- [5][modern][client-only] `--keep 0` silently means "server default"; a
  fat-fingered `--keep 0` reads like "keep zero / delete all." Make 0 explicit-
  reject or document; consider `--keep` defaulting to unset sentinel.

## `prexorctl backup delete <id>` (alias `rm`)
Current: `DELETE /api/v1/backups/<id>`; success line.

**Verdict:** IMPROVE.

**Findings:**
- [10][table-stakes][client-only] **No confirm gate, no `--yes`** for an
  irreversible delete (`backup.go:164-180`).
- [6][table-stakes][client-only] Ignores `--json` on success (`backup.go:177`).
- [10][table-stakes][client-only] `id` not url-escaped (`backup.go:174`).
- [2][modern][client-only] No `--all`/multi-id delete; operators clearing space
  loop one-at-a-time.

## `prexorctl restore <id>`
Current: `POST /api/v1/restore` with `{id, dryRun, filesystem, datastores}`;
prints applied/dry-run + entry/collection counts.

**Verdict:** IMPROVE — **highest-risk command in the entire CLI** — target: a
mandatory typed-confirm + mandatory pre-restore safety backup + accurate help.

**Findings:**
- [10][table-stakes][client-only] **A full live restore that overwrites Mongo +
  Redis + on-disk FS runs with NO confirmation gate and NO `--yes`**
  (`restore.go:19-52`) — unlike every `cluster` destructive command. This is
  the single worst safety gap in scope. Require typed-confirm of the cluster id
  / "restore" and a `--yes` bypass.
- [10][table-stakes][needs-server] No **mandatory pre-restore backup**. A
  restore is irreversible against live data; best-in-class DR tooling snapshots
  current state first (the server already produces a `rollbackRoot` for the FS —
  extend that to a full auto-backup, or have the CLI run `backup create` first
  unless `--no-safety-backup`).
- [9][table-stakes][client-only] **Long help documents flags that don't exist**:
  references `--no-files`/`--no-data` but the real flags are
  `--filesystem`/`--datastores` (`restore.go:13-15` vs `:66-69`). Actively
  misleading on the most dangerous command.
- [4][modern][client-only] `--dry-run` exists (good) but there's no `--dry-run=
  server` vs default human diff of *what changes*; the dry-run only echoes
  counts. A real plan ("N collections will be dropped and replaced") would let
  operators trust the apply.
- [10][table-stakes][needs-server] No quorum/HA awareness: restoring shared
  Mongo from one controller while peers are live is a split-brain risk. The
  command should warn / require the cluster be quiesced (or single-member), and
  the server should reject a live-multi-member restore without an override.
- [8][modern][client-only] If both `--filesystem=false` and
  `--datastores=false`, the restore is a silent no-op; should error "nothing to
  restore."

## `prexorctl diagnostics bundle`
Current: writes a redacted tar.gz locally (or `--share`s it); optional
`--log-lines`.

**Verdict:** IMPROVE — target: scriptable capture with overwrite safety and
honored `--json`.

**Findings:**
- [6][table-stakes][client-only] **Local-write success path ignores `--json`**
  (`diagnostics.go:96`) — always prints a styled line. A CI/postmortem script
  can't get `{path, sizeBytes}` structured.
- [10][modern][client-only] `os.Create` **truncates an existing `--out` file
  with no overwrite guard** (`diagnostics.go:107`); re-running clobbers a prior
  bundle. Add `--force` / refuse-if-exists.
- [9][modern][client-only] `--share` + no `--out` **silently discards** the
  local bundle (`diagnostics.go:54-67`); `--share` quietly changes whether a
  file is produced. Document it, or always keep the file unless `--no-local`.
- [3][modern][needs-server] In an HA incident the bundle captures only the
  *contacted* controller (leases are cluster-wide but config/logs are node-
  local). A `--all-members` fan-out (one sub-dir per controller via each
  `restAddr`) is the diagnostics story HA actually needs — the failover
  substrate already exposes `restAddr`.
- [4][modern][client-only] Partial failure: logs fetch failure is a warning and
  the bundle still writes (good), but there's no manifest flag recording that
  logs were omitted — a reader can't tell "no logs" from "logs failed."

## `prexorctl crash list`
Current: `GET /api/v1/crashes?group=&node=&from=`; table.

**Verdict:** KEEP (minor).

**Findings:**
- [2][modern][client-only] `--since` flag is sent as wire param `from`
  (`crash.go:27-33`); cosmetic mismatch that trips anyone correlating with
  server logs. Also no client-side ISO-8601 validation — a bad value silently
  returns everything.
- [5][modern][client-only] `--since` only; no `--until`, no `--limit`/`--class`
  filter. For a crash-loop investigation, time-window + classification filters
  are the obvious asks.
- [6][modern][client-only] No `-o name`/jsonpath to pipe ids into `crash info`.

## `prexorctl crash info [id]`
Current: card + last log lines; TTY picker when id omitted; `--share`.

**Verdict:** KEEP (minor).

**Findings:**
- [10][table-stakes][client-only] `id` interpolated into the path **without
  url-escaping** (`crash.go:93,100`).
- [9][modern][client-only] Good: picker fallback, `--json`, non-TTY usage error.
  No real complaints beyond escaping.

---

## Cross-cutting / systemic

1. **Expose leader/role/health/quorum on the wire, then everywhere [needs-
   server, table-stakes].** The single highest-leverage fix. `cluster status`
   and `members` are blind without `leaderId`, `selfNodeId`, per-member
   `role`/`voting`/`reachable`/`lastSeen`/lag, and `quorumSize`/`votingMembers`.
   `isLeader()` already exists internally. Until this ships, none of the HA
   commands can answer the incident-time questions (who's leader, do we have
   quorum, is it safe to eject) — and `eject`/`recover` will keep being able to
   brick quorum blind, exactly the 8E failure mode.

2. **One danger-gate model, applied to every destructive op [client-only,
   table-stakes].** Today there are *three* behaviors: `confirmDestructive`
   y/N (eject/leave/seed/recover), typed-"yes" (`share revoke`), and **no gate
   at all** (`cluster join-token revoke`, `token revoke`, `backup prune`,
   `backup delete`, **`restore`**). Adopt one helper: `--yes` bypass + non-TTY
   refuse-without-`--yes` + typed-confirm-of-resource for irreversible/
   quorum-altering ops (eject, leave, restore). `restore` having **zero** gate
   is the worst offender.

3. **`--dry-run` + quorum-math preview for membership/retention ops [mixed,
   modern].** `eject`, `recover`, `backup prune`, and `restore` all mutate
   irreversibly with no preview. Standardize `--dry-run` that prints the
   post-op state ("2 voting members, quorum=2, margin=0" / "would prune 4
   bundles" / "would drop & replace N collections").

4. **Honor `--json` (and add `-o`) on every success path [client-only, table-
   stakes].** `cluster eject`, `cluster join-token revoke`, `token revoke`,
   `backup delete`, `diagnostics bundle` local-write all print plain text under
   `--json`; `backup verify` even returns the *wrong exit code* under `--json`.
   Build a single output layer (human/`--json`/`-o name|yaml|jsonpath|wide`,
   `--quiet`) and route all of these through it. Mutations should emit a stable
   `{resource, action, result}` envelope.

5. **`url.PathEscape` every id interpolated into a path [client-only, table-
   stakes].** `cluster join-token revoke`, `token revoke`, `backup verify/
   delete`, `crash info`, and `cluster recover`'s typed nodeIds all skip it,
   while `cluster eject` and `share` escape. Fix once, ideally by a client
   helper that builds escaped paths.

6. **Mandatory pre-restore safety backup + HA-quiesce awareness [needs-server,
   table-stakes].** Restore is the only command that destroys live shared state
   with no recovery path and no quorum awareness. Auto-snapshot before apply
   (extend the existing `rollbackRoot`), and refuse/​warn on a live multi-member
   restore.

7. **Off-host backup transport [needs-server, modern].** The CLI can create/
   list/verify/prune backups but **cannot retrieve one** ("copy it yourself").
   A `backup pull <id> -o file` (and `restore --from-file`) closes the DR loop
   and matches `etcdctl snapshot save`/restore.

8. **Disambiguate the two join-token trees [client-only, table-stakes].**
   `token` (node) vs `cluster join-token` (controller) are a documented trap.
   Rename/cross-reference, unify TTL grammar (`--ttl 24h` everywhere, not
   `--ttl-seconds`), and add "see also" lines.

9. **`--wait`/`--watch` for async control-plane convergence [mixed, modern].**
   `cluster leave` (commit-then-shutdown), join, and membership changes are
   async with no convergence signal. Add `--wait`/`--timeout` to mutations and
   `--watch` to `status`/`members` so operators can watch a join/failover
   settle (kubectl `wait`/`-w`).

10. **Quorum-loss write story [needs-server + client].** A write during quorum
    loss HANGS (no 503) and is bounded only by the 30s client timeout, which
    then looks like a generic connection error. Surface a distinct, actionable
    error ("no quorum — N/M voting members reachable; see `cluster recover`")
    and a shorter, explicit write-deadline with a clear message rather than a
    silent 30s stall.

11. **Net-new commands the domain implies [modern/innovative]:** `cluster
    health` (fused status+members+leases verdict), `cluster leader`
    (who/transfer — consul `move-leader`), `cluster leases` (the route exists;
    no CLI verb surfaces it), `backup pull`/`restore --from-file`, `token`
    kind-unification, `cluster recover --quorum-lost` replacing the awkward
    `--i-have-only-survivor`.
