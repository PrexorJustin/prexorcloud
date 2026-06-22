# prexorctl deep-review 06 — observability streams & lifecycle

Scope: `prexorctl logs` (controller/daemon/instance/all + bare picker), `share`
(list/view/revoke + the `--share` invocation flags), `stop`
(bare/local/node/controller).

Sources read: `cmd/logs.go`, `cmd/share.go`, `cmd/share_cmd.go`, `cmd/stop.go`,
`internal/tui/logstream.go`, `internal/api/client.go`, `cmd/picker.go`.
Cross-refs: `.audit/02-cluster-ops.md`, `.audit/04-setup-config.md`.

Benchmark targets for this set: **kubectl logs** (`-f`, `--since`,
`--since-time`, `--tail`, `--previous`, `--timestamps`, multi-container, `-l`
selector), **stern** (multi-pod regex tail, per-line color, `--template`,
JSON/`--output`), **vector**/`vector tap`, **docker compose logs**, **systemctl
stop** (SIGTERM→SIGKILL, `--no-block`), **kubectl drain/cordon**, **consul/etcd
operator** (leader-aware member ops).

---

## Architectural facts that dominate this scope

1. **Per-process log ring buffers.** `/api/v1/system/logs` and
   `/api/v1/system/logs/stream` serve the *in-memory ring of the controller the
   CLI is pinned to* (`logsResponse.Capacity`, `Size` — it's a bounded buffer,
   not a durable store). In HA there are N independent buffers. The CLI has **no
   member selector**, **no merge across members**, and the pinned URL has **no
   failover** (per 02/04). So "show me the controller log" silently means "show
   me *one arbitrary* member's last ~`Capacity` lines."
2. **SSE rides the 30s-timeout client.** `SSEStreamWithTicket` calls
   `c.HTTPClient.Do(req)` and `c.HTTPClient` is built with
   `Timeout: 30*time.Second` (`client.go:88`). Go's `http.Client.Timeout`
   includes reading the response body, so **every `logs -f` stream is killed at
   ~30s** with `context deadline exceeded`. There is also **no reconnect / no
   `Last-Event-ID` resume**: `SSEStreamWithTicket` returns on the first EOF or
   error and `followLogs` then `Close(err)`s the view. A live tail across a
   leader change, a daemon restart, or a brief network blip just dies.
3. **Sharing redaction is entirely server-side and unpreviewable.** The flag
   help says "Upload a redacted copy" but the client never sees what was
   redacted before it leaves the building, and there is no `--dry-run`/preview.

These three facts drive most of the findings below.

---

## `prexorctl logs` (bare)

Current: in a TTY (and not `--json`) opens a 4-way chooser
(controller/daemon/instance/all) and forces `--follow=true`; non-TTY/`--json`
falls through to `logs controller`.

**Verdict: IMPROVE** — target: a `kubectl logs`-class multiplexer whose default
is predictable and whose non-TTY path never silently changes scope.

**Findings**
- **[modern][client-only]** (1,5,9) The TTY chooser auto-forcing `--follow` is a
  nice touch (`logs.go:57`), but the bare-command mental model is muddy: bare
  `logs` in a pipe means "controller", which is invisible. A discoverability win
  is to make the picker also let you set `--since`/`--level` inline, like the
  `group info` card it mimics.
- **[table-stakes][client-only]** (6,7) `--json` only ever works in *non-follow*
  paths; bare `logs --json` → `logs controller --json` (one page) is fine, but
  the relationship ("`--json` disables follow") is undocumented in help.

---

## `prexorctl logs controller`

Current: `GET /api/v1/system/logs?level=&logger=&limit=<tail>`; with `--follow`,
seeds from that page then SSE `/api/v1/system/logs/stream`. `--share` rejects
`--follow`.

**Verdict: IMPROVE** — target: HA-aware, reconnecting, time-windowed controller
log access with JSON-lines streaming for automation.

**Findings**
- **[table-stakes][needs-server]** (3) **No way to say which member's log you
  want, and no merge.** Add `--member <nodeId>` (resolve its `restAddr` from
  `GET /api/v1/cluster/members`, like the failover substrate in 02) and
  `--all-members` to fan-in/merge with a `[node]` prefix (the `logs all`
  machinery already does per-source colored prefixes). Today an operator
  debugging an HA incident sees one random member and can't tell.
- **[table-stakes][client-only]** (3,4) **30s hard cap on follow** (client.go:88
  + SSE on the same client). Use a separate streaming client with no
  `Timeout` (rely on `ctx` + a read/idle deadline), or the tail dies every 30s.
- **[table-stakes][client-only]** (3,4) **No SSE reconnect.** On EOF/error,
  reconnect with backoff and resume from `LastSeq()` (the code already tracks
  `sinceSeq` to de-dupe — wire it into a reconnect loop so a tail survives a
  leader re-election / restart instead of exiting).
- **[modern][needs-server]** (5,6) **No time window.** Only `--tail <count>`.
  Add `--since 15m` / `--since-time <RFC3339>` (kubectl parity); the records
  carry `ts` (epoch millis) so even client-side filtering of the seed page is
  possible today, server-side is better.
- **[modern][client-only]** (6,7) **`--follow` silently ignores `--json`** — it
  always renders the TUI. There is **no scriptable live capture**: `kubectl logs
  -f | jq` works, here it can't. Emit newline-delimited JSON (or
  `formatLogRecord` plain text) to stdout when `--follow --json`/non-TTY instead
  of forcing bubbletea.
- **[table-stakes][client-only]** (6,12) **Color is applied unconditionally** in
  the non-follow text path (`renderLogRecord`→`formatLogRecord` always wraps in
  `theme.Style*`). Piping `logs controller > file` embeds ANSI. Respect
  non-TTY/`--no-color` and emit plain (the global `--no-color` exists per 04 —
  confirm `formatLogRecord` honors it).
- **[modern][client-only]** (6) **No `--timestamps`/`-o` knobs.** Add
  `--timestamps=false`, `-o json|raw|template`, and a `--grep <regex>` (stern's
  killer feature) so server filtering by message — not just `--logger` prefix —
  is possible client-side at minimum.
- **[modern][needs-server]** (4) `--level INFO` default means a bare `logs` hides
  DEBUG/TRACE silently; fine, but the header shows `level INFO+` only in follow
  mode — print the effective level in non-follow too so operators don't think
  they're seeing everything.

---

## `prexorctl logs daemon [node-id]`

Current: same shape as controller against `/api/v1/nodes/<id>/logs[/stream|
/ticket|/share]`; picker when id omitted in a TTY.

**Verdict: IMPROVE** — same streaming fixes as controller, plus hygiene.

**Findings**
- **[table-stakes][client-only]** (5,10) **`nodeID` is interpolated into the URL
  path with no `url.PathEscape`** (`logs.go:321,334,367`) — inconsistent with
  `share`/`cluster eject` which do escape. Path-injection / breakage on odd ids.
- **[table-stakes][client-only]** (3,4) Inherits the **30s cap + no reconnect**.
  Daemon tails are exactly where a node blip is likely, so reconnect matters
  more here.
- **[modern][needs-server]** (3,6) No `--all-nodes`/selector. Tailing "every
  daemon in node-pool X" is a common fleet question; `logs all` covers instance
  consoles but there is no daemon-level fan-in.
- **[modern][needs-server]** (5) `--since`/`--previous` apply here too (a daemon
  that just restarted — show the prior process's tail).

---

## `prexorctl logs instance [id]`

Current: non-follow `GET /api/v1/services/<id>/console/history?limit=<tail>`;
follow SSE `/api/v1/services/<id>/console` (read-only). Picker when id omitted.

**Verdict: IMPROVE** — target: kubectl-grade single-stream with `--previous` and
honest flag surface.

**Findings**
- **[table-stakes][client-only]** (5,9) **`--level`/`--logger` are accepted but
  ignored** — they're persistent on `logsCmd`, the console endpoints don't use
  them, yet they appear in `instance --help`. Either hide them on this
  subcommand or error if set. Misleading.
- **[table-stakes][client-only]** (5,10) `id` is not `url.PathEscape`d on the
  history/stream/share paths (`logs.go:417,442,458,477`).
- **[table-stakes][client-only]** (3,4) **30s cap + no reconnect** — and
  `tailInstanceConsole` uses plain `SSEStream` (events ticket), so a console
  that's mid-restart drops the viewer permanently.
- **[modern][needs-server]** (4,5) **No `--previous`.** A crashed instance's last
  console lines live only in `crash info` (separate command, `LAST LOG LINES`).
  kubectl `logs --previous` is the expected bridge; surface the crash tail under
  `logs instance <id> --previous`.
- **[modern][client-only]** (6) Console history `--json` returns
  `{lines:[{ts,line}]}` but the follow path emits raw lines into a TUI with no
  `ts`. Inconsistent shape between the two paths; a `--timestamps` toggle and a
  JSON-lines follow would unify them.
- **[innovative][needs-server]** (13) The follow path is *read-only* by design
  (the writable variant is `instance console`). Document the split in help —
  today a user reasonably expects `logs instance -f` to let them type.

---

## `prexorctl logs all`

Current: `GET /api/v1/services`, filter by `--group`/`--node`, fan out one SSE
per instance into a merged colored-prefix TUI. Always a TUI.

**Verdict: IMPROVE** — this is the closest thing to `stern`; make it scriptable
and resilient.

**Findings**
- **[table-stakes][client-only]** (6,7) **`--json`, `--follow`, `--tail` are all
  silently ignored** — it is *only* a TUI. In a pipe it still tries to render
  bubbletea (alt-screen) and will misbehave. There is **no non-interactive
  merged capture** — the single biggest gap vs stern/vector, which exist
  precisely to pipe multi-source tails. Add a non-TTY/`--json`/`--no-tui` mode
  that prints prefixed (or JSON-lines, with an `instance` field) to stdout.
- **[modern][client-only]** (3,4) Each fan-out stream is subject to the **30s
  cap** and has **no per-stream reconnect** (`_ = client.SSEStream(...)` swallows
  the error). After ~30s the merged view goes quiet with zero feedback; the
  `wg.Wait()`→`Close(nil)` then makes it look like a clean end. Reconnect each
  source independently and surface per-source drop/rejoin in the prefix.
- **[modern][client-only]** (5,6) **No regex/grep, no `-l`/label-style
  selector** beyond exact `--group`/`--node` (case-insensitive equality). stern's
  whole value is regex pod + container matching; add `--grep` and glob/regex on
  group/node.
- **[modern][needs-server]** (4) Snapshot-at-start: it fans out over the
  instance list once, so instances started *after* the command don't appear.
  stern re-watches. A `--watch`-style re-resolve (or server-side multiplexed
  endpoint) would fix it.
- **[table-stakes][client-only]** (11) Unbounded fan-out: one HTTP connection +
  goroutine per instance with no concurrency cap. On a large fleet `logs all`
  (no filter) opens hundreds of SSE connections. Cap concurrency / require a
  filter above N.

---

## `--share` invocation flags (share.go) — on `logs controller|daemon|instance`, `crash info`, `diagnostics bundle`

Current: `--share`, `--expiry`, `--public`, `--burn-after-read`; POSTs to the
surface's `/share` endpoint; server redacts + uploads to a paste service.

**Verdict: IMPROVE** — target: a privacy-first "share a diagnostic" flow with
preview, validated expiry, and safe defaults.

**Findings**
- **[table-stakes][needs-server]** (10) **No redaction preview / `--dry-run`.**
  "redacted copy" is asserted, never shown. For a feature whose entire purpose is
  pasting logs to a third party, an operator must be able to see exactly what
  leaves first (`--dry-run` returns the redacted payload locally; or
  `--out` writes it instead of uploading). Highest-trust gap in the set.
- **[table-stakes][client-only]** (5,8) **`--expiry` is not validated.** Help
  lists `1h|1d|30d|never` but any string is passed through with `omitempty`
  (`share.go:40,75`). A typo (`--expiry 1day`) silently becomes server-default.
  Make it an enum with completion + client validation.
- **[modern][client-only]** (9,10) **`--expiry never` on a `*_LOGS`/`DIAGNOSTICS`
  share is a footgun** — a permanent public-ish paste of logs that may contain
  tokens/IPs. Warn (or require `--yes`) when combining `never` with a log/diag
  kind.
- **[modern][client-only]** (5,9) `--public` inverts to `isPrivate=false` and is
  only sent when changed — correct, but a public log paste deserves a one-line
  confirmation/warning ("this paste will be world-readable"). Default-private is
  good.
- **[table-stakes][client-only]** (9) `--share` + `--follow` errors cleanly
  (good), but the error is generic; suggest the non-follow form
  ("drop --follow to snapshot-and-share the last --tail lines").
- **[modern][client-only]** (6,9) On success `runShare` prints URL + expiry +
  delete URL but offers no **`--copy`** (clipboard) or **`--open`** convenience,
  and the `DeleteURL`/`DeleteToken` is printed to stdout where it lands in logs —
  consider stderr for the secret revoke URL.

---

## `prexorctl share list`

Current: `GET /api/v1/shares?kind=&activeOnly=&pageSize=`; table.

**Verdict: KEEP (minor IMPROVE).**

**Findings**
- **[table-stakes][client-only]** (6) Good `--json` (full page envelope). Add
  `-o wide` (show `rawUrl`, `expiresAt`, `burnAfterRead`) and `-o name` (ids for
  piping into `share revoke`).
- **[modern][client-only]** (5,8) `--kind` is upcased but not validated against
  the documented enum; a bad kind silently returns everything/nothing. Validate
  + completion.
- **[modern][client-only]** (5) No `--since`/`--mine`/`--expired` filters; for an
  audit surface ("what did we leak this week, by whom") these are the natural
  questions. `sharedByUser` is in the record but not filterable.
- **[table-stakes][client-only]** (6) `--limit 0` sends no `pageSize` (server
  default), diverging from the documented default 50 — clamp or document.

---

## `prexorctl share view <id>`

Current: `GET /api/v1/shares/<id>` (url-escaped); prints fields + revocability
hint.

**Verdict: KEEP.**

**Findings**
- **[modern][client-only]** (6) Solid card + `--json`. Add `--open` (xdg-open the
  URL) and `--raw` (print just the `url`/`rawUrl` for piping).
- **[table-stakes][client-only]** (9) The "revocable via" hint is great
  discoverability; mirror it in `share list` status column tooltips.

---

## `prexorctl share revoke <id>`

Current: unless `--yes`, fetches the record, prints it, and demands a typed
`yes` via `fmt.Scanln`; then `POST /api/v1/shares/<id>/revoke`.

**Verdict: IMPROVE** — behavior is good; the confirmation *idiom* is the problem.

**Findings**
- **[table-stakes][client-only]** (9,12) **Confirmation idiom is unique in the
  whole CLI.** Everywhere else destructive ops use `confirmDestructive` y/N
  (cluster) or `confirmStop` huh-confirm (stop); here it's a raw
  `fmt.Scanln("type yes")`. Unify on one danger-gate helper (cross-cutting).
- **[table-stakes][client-only]** (7) `fmt.Scanln` in a non-TTY without `--yes`:
  it reads "" and aborts with `revoke aborted` — acceptable, but the message
  should say "no TTY; pass --yes" (parity with `confirmStop`'s explicit refusal).
- **[modern][client-only]** (4) No bulk revoke. `share revoke --kind DAEMON_LOGS
  --before <date>` or accepting multiple ids / stdin would make "nuke everything
  we shared during the incident" one command instead of a `jq | xargs` loop.

---

## `prexorctl stop` (bare)

Current: TTY → 3-way picker; **non-TTY → `stop local`**. Persistent `--yes`.

**Verdict: IMPROVE (safety)** — target: bare `stop` must never take a destructive
default in automation.

**Findings**
- **[table-stakes][client-only]** (7,10) **Footgun: bare `prexorctl stop` in a
  script/pipe stops THIS host's controller+daemon** (`stop.go:30-35`) — and may
  demand root mid-run. A stray `stop` (typo, wrong arg, `xargs` misfire) silently
  shells out `systemctl stop`/`compose stop`. In a non-TTY, bare `stop` should
  **error with usage** ("specify local|node|controller"), not pick the most
  destructive local default. This is the single most dangerous default in scope.
- **[table-stakes][client-only]** (1) Asymmetric lifecycle: there is a `stop` but
  no `start`/`restart`/`status` sibling. `stop local` can't be undone with the
  same tool — see cross-cutting (net-new `start`/`restart local`).

---

## `prexorctl stop local`

Current: detect controller+daemon via compose dir or systemd unit; stop each;
systemd path requires root. No `--json`.

**Verdict: IMPROVE.**

**Findings**
- **[table-stakes][client-only]** (6,12) **No `--json`** while `stop node`/`stop
  controller` both support it (`stop.go:180,218`). Inconsistent within one tree;
  a script can't tell what was stopped/skipped.
- **[table-stakes][client-only]** (4) **First-error abort, not aggregate.** The
  loop `return err`s on the first failed component (`stop.go:106,112`), so if the
  controller stop fails the daemon is never attempted, and the partial result is
  lost. Mirror the `stopped`/`missing` accumulation pattern it already uses for
  success — collect failures and report all.
- **[modern][client-only]** (5,9) **No `--dry-run`.** It auto-detects deploy mode
  (docker vs systemd) and acts; "show me what you'd stop" is cheap and valuable
  given the footgun above.
- **[modern][client-only]** (4) **No graceful/`--timeout` control.** systemctl
  and `docker stop -t` both expose SIGTERM→SIGKILL timing; `ComposeStop`/
  `systemctl stop` are fire-and-wait with no knob and no progress feedback on a
  slow JVM shutdown.
- **[table-stakes][client-only]** (8,9) The root requirement is detected and
  errored well (`stop.go:97`), but only *after* detection; a `--json` error
  envelope + correct exit code (currently generic) would help automation.
- **[modern][client-only]** (1) Maps to `compose stop` (not `down`) — correct, it
  preserves volumes; state that explicitly in help so operators know data isn't
  removed.

---

## `prexorctl stop node [id]`

Current: confirm/`--yes`; `POST /api/v1/nodes/<id>/shutdown`; immediate, no
drain; non-TTY without `--yes` refuses. `--json` yes.

**Verdict: KEEP (minor IMPROVE)** — the safest-designed command in the set.

**Findings**
- **[table-stakes][client-only]** (5,10) `id` not `url.PathEscape`d
  (`stop.go:177`).
- **[modern][needs-server]** (4) Help correctly points to `node drain` for a
  graceful stop — good. Consider a `--drain` convenience flag that drains then
  stops in one call (`kubectl drain` ergonomics) rather than two commands.
- **[modern][client-only]** (4) Async: shutdown is sent, not awaited. Offer
  `--wait` (poll `GET /api/v1/nodes/<id>` until OFFLINE) per the CLI-wide
  async-convergence story.

---

## `prexorctl stop controller`

Current: confirm/`--yes`; `POST /api/v1/system/shutdown` on the *connected*
controller; warns about restart-always supervisors. `--json` yes.

**Verdict: IMPROVE (HA blast radius)** — target: leader/quorum-aware controller
stop with member targeting.

**Findings**
- **[table-stakes][needs-server]** (3,10) **HA blast radius is invisible.** It
  stops whichever member the context happens to be pinned to. If that's the
  **leader**, this forces a re-election; if quorum is already thin, stopping it
  can **lose quorum** (cf. the 8E findings in memory where majority-loss hangs
  writes). The confirm copy mentions re-election generically but the command
  doesn't *know* whether this member is the leader or whether stopping it breaks
  quorum. Pre-flight `GET /api/v1/cluster/members` (+ a leader/quorum check once
  `isLeader()` is exposed per 02) and **escalate the gate** (typed-confirm /
  refuse without `--force`) when stopping the leader or the quorum-critical
  member.
- **[modern][client-only]** (3,5) **No `--member` targeting.** To stop a
  *specific* member you must re-point your context at it first. Accept
  `--member <nodeId>` and route via its `restAddr` (same substrate as the
  proposed `logs --member`).
- **[modern][client-only]** (9) The restart-supervisor caveat is in `Long` help
  but not echoed at confirm time — surface it inline when the gate fires, since
  "it came back" is the #1 confusion.
- **[table-stakes][client-only]** (4) Async + no `--wait`; returns on "shutdown
  initiated". For a single controller this also means the CLI's own next call
  will fail — note that.

---

## Cross-cutting / systemic

1. **SSE streaming substrate is broken for long-lived tails (fix once).**
   Streams reuse the 30s-`Timeout` client (`client.go:88`) and have no reconnect
   / `Last-Event-ID` resume. Introduce a dedicated streaming client (no
   `Timeout`, ctx-driven, idle read-deadline) and a reconnect loop keyed on the
   `seq`/`sinceSeq` the code already tracks. This fixes `logs controller/daemon/
   instance/all` and any future stream consumer (events, console) in one place.
   *[table-stakes][client-only]*
2. **HA member-targeting model.** Logs and `stop controller` both silently act on
   the pinned member with no selector and no merge. Add a shared `--member
   <nodeId>` / `--all-members` resolver over `GET /api/v1/cluster/members`
   `restAddr` (the failover substrate from audit 02), reused by logs fan-in and
   member-scoped stop. Pair with exposing `isLeader()`/quorum so destructive
   member ops can gate on role. *[modern][needs-server]*
3. **Non-interactive default for `stop` (and any bare picker that falls back to a
   destructive action).** Bare `stop` → `stop local` in non-TTY is a live
   footgun; bare commands must error-with-usage in non-TTY rather than pick a
   destructive default. Audit every `interactive()`-gated fallback for the same
   pattern. *[table-stakes][client-only]*
4. **`--json` (and exit codes) on every success path.** `stop local`, `logs
   -f`, `logs all` all drop machine output. Establish "every command emits JSON
   under `--json`/non-TTY, including streams (JSON-lines)" as an invariant —
   matches the same gap found in 02/04. *[table-stakes][client-only]*
5. **Unify the danger-gate idiom.** This scope alone has three:
   `confirmDestructive` y/N (cluster), `confirmStop` huh-confirm (stop), and
   `fmt.Scanln("yes")` (share revoke). One helper with tiers (y/N → typed-confirm
   → `--force`) keyed on blast radius. *[table-stakes][client-only]*
6. **Plain-when-piped output.** Log rendering applies ANSI unconditionally in
   text mode; detect non-TTY/`--no-color` and emit plain so `logs > file` and
   `| grep` are clean. *[table-stakes][client-only]*
7. **URL-escape every path-interpolated id, CLI-wide.** `logs daemon/instance`
   and `stop node` interpolate ids raw; `share`/`cluster eject` escape. Make a
   path-builder that always escapes. *[table-stakes][client-only]*
8. **Time-window + `--previous` log primitives.** `--since`/`--since-time` and
   `--previous` (bridge to crash tail) are table-stakes kubectl parity missing
   across all log subcommands. *[modern][needs-server]*
9. **Net-new lifecycle siblings.** `stop` has no `start`/`restart`/`status local`
   counterpart, so `stop local` is a one-way door from this CLI. Add them (and a
   `--dry-run`/`--timeout` model) to round out host-lifecycle parity with
   systemctl/docker compose. *[modern][client-only]*
10. **Share = privacy feature, not just an uploader.** `--dry-run`/preview of the
    redacted payload, validated `--expiry` enum, and a warning gate on
    `never`+log/diag kinds should be standard on every `--share` surface.
    *[table-stakes][needs-server]*
