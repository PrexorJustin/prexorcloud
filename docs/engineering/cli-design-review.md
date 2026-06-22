# prexorctl — design review & improvement plan

Companion to **`cli-command-catalog.md`** (the neutral per-command reference). This
document is the opinionated half: how the CLI actually behaves today, where the
design is strong, where it is cluttered or inconsistent, and a prioritized plan to
make it the best it can be. It was produced from a full source audit (raw notes in
`cli/.audit/`) plus a live coverage run against the Hetzner fleet (Part 11).

The CLI is a cobra app (`cli/`) with a charmbracelet (bubbletea/huh/lipgloss) TUI
layer. ~27 top-level groups, ~90 commands.

> **A deeper pass followed this review.** Every command was re-evaluated against a
> 13-dimension rubric (`cli/.audit/deep/RUBRIC.md`) across seven domains; the
> per-command verdicts live in `cli/.audit/deep/01..07-*.md`, and the synthesized,
> phased plan is in **`cli-modernization-roadmap.md`** — start there for the target
> design. This document remains the per-finding assessment + de-clutter list. The
> six recurring themes the deep pass crystallized: unified `-o` output, async
> `--wait`, one danger-gate, HA-awareness (failover + identity + leader visibility),
> scriptability/honesty (no fabricated data), and a declarative `apply` layer.

---

## 1. What already shipped in this pass (P0 correctness fixes)

These were unambiguous bugs that any redesign keeps, so they're already fixed,
tested, and verified live:

| Fix | Before | After | Where |
|-----|--------|-------|-------|
| **Error messages** | `✗ HTTP 403` / `HTTP 500` with no reason | `✗ Insufficient permissions (HTTP 403)`, `✗ create backup: An internal error occurred (HTTP 500)`. New `parseAPIError` handles `{message}`, nested `{error:{…}}`, RFC-7807 `{title/detail}`, and plain-text bodies | `internal/api/client.go` (+ test) |
| **`--verbose` corrupted output** | HTTP trace written to **stdout**, breaking `--json` and pipes | trace goes to **stderr**; stdout stays clean JSON | `internal/api/client.go` |
| **`group info <name>` crashed without a TTY** | `✗ could not open a new TTY` (exit 1) in any pipe/CI | static text render fallback when not interactive; `--json` unchanged | `cmd/group.go` |
| **Pre-link gate blocked local-only commands** | `plugin new` / `module doctor` / `module test` returned "no cluster connected" on a fresh install | annotated `local-only`; they run offline as intended | `cmd/plugin.go`, `cmd/module_doctor.go`, `cmd/module_dev.go` |

`go test ./...` green. The fixes are in the working tree (uncommitted) — not yet
deployed to the fleet's `/usr/local/bin/prexorctl`.

> Side note surfaced by the better errors: **`backup create` → HTTP 500
> `INTERNAL_ERROR`** is a genuine *controller-side* failure (Part 12), not a CLI
> bug. Worth a separate investigation.

---

## 2. The big picture — what's good, what's systemic

**Strong foundations (keep these in the redesign):**

- Multi-context model (`~/.prexorcloud/config.yml`, kubeconfig-shaped) is clean.
- The pre-link gate ("you can only `setup` or `login` until linked") is a genuinely
  good onboarding idea.
- Consistent `list`/`info`/`create`/`delete` verb shape across most entities.
- Interactive pickers (`resolveArg` + `pickOne`) when an arg is omitted — nice touch.
- `--json` exists broadly on read paths and parses cleanly.
- Exit-code discipline exists (typed `ExitCodeError` + `APIError.ExitCode`).

**Systemic issues (the themes a redesign should fix once, globally):**

1. **Scriptability is half-built.** `--json` covers reads but is missing on most
   *mutations*; destructive commands block on interactive confirms with no `--yes`;
   `login` and `user create` can't run headless at all. The CLI is great
   interactively and frustrating in CI.
2. **Confirmation UX is a free-for-all.** Four different idioms for comparable risk:
   huh-confirm (`group delete`), raw `[y/N]` (`deploy`), typed-"yes"
   (`share revoke`), and *nothing at all* (`restore`, `backup prune`, `node drain`,
   `catalog remove`, `template rollback`). This is the single most visible
   inconsistency.
3. **Errors aren't structured under `--json`.** Now that messages are fixed, the next
   gap: a `--json` consumer still gets plain text on stderr, never a JSON error
   envelope. Exit codes are also overloaded (2 = auth-401 *and* doctor-warning;
   `ExitConnError=5` is dead code).
4. **Help text has drifted from reality.** Multiple flags are documented but dead, or
   real behavior contradicts the help. These actively mislead and are cheap to fix.
5. **Some output is fictional.** `status` renders synthetic TPS/Players/Memory
   sparklines and a per-group `SPARK (1h)` column that are *generated waves*, plus
   `whoami = "(authenticated)"`. A redesign must decide: wire to real metrics or
   remove. Showing fake data in an ops tool is a trust problem.
6. **Theming is split-brain.** Tables/headings use lipgloss v2 + the accent system;
   huh prompts and `--help` hardcode purple and ignore `cfg.Accent`.

---

## 3. De-clutter recommendations (you flagged this specifically)

Things that add surface area without adding capability — candidates to **merge,
hide, or cut** in the redesign:

- **`module upload` ⊂ `module install`.** `upload` is the strictly weaker subset
  (local-jar only, unsigned, no sidecar, no registry). Fold it into `install` or
  mark deprecated. Two commands, one job.
- **Two parallel "join token" trees.** `cluster join-token` (controller↔controller)
  vs top-level `token` (node/daemon). Neither's short help disambiguates; it's easy
  to run the wrong one. Consider nesting both under one noun or cross-referencing
  hard in help.
- **Four lifecycle flags for two questions in `setup`.** `--service-mode` +
  `--startup-validation-mode` (legacy) coexist as first-class equals with
  `--boot-mode` + `--start-mode` (canonical). Pick one pair; deprecate the other.
- **Dead/no-op flags to delete:** `setup --dashboard-serve-mode`,
  `--dashboard-tls-email` (never consumed); `module new --browser` (returns "not
  implemented"); `module new --no-mongo` (silently does nothing); `logs instance
  --level/--logger` (accepted, ignored). A flag that does nothing is worse than no
  flag.
- **`module new` has three different platform-target lists** (legacy `--interactive`
  offers 3, the wizard 6, `scaffold.AllTargets()` 4). Collapse to one source of truth.
- **Two unrelated "rollback" verbs** (`template rollback`, `deploy rollback`) — fine
  to keep, but document the distinction so it doesn't read as duplication.
- **`config set token <value>`** takes a JWT as a positional arg (leaks to shell
  history). Either drop it (we have `login`) or read from stdin.

Net: the CLI isn't *badly* cluttered, but ~8–10 flags/commands are pure noise and
should go before a redesign freezes the surface.

---

## 4. Prioritized backlog

### P1 — scriptability & consistency (recommended next; survives a redesign)

- **Add `--yes`/`--force` + non-TTY guards to every destructive command**, and pick
  *one* confirm idiom. Affected: `group delete`, `user delete`, `role delete`,
  `backup prune`, `backup delete`, `restore` (currently has *no* gate at all — the
  highest-risk command), `cluster eject/leave/recover` (already gated, just unify).
- **Add a non-interactive path to `login`** (`--username`, `--password`/stdin,
  `--controller`) and `user create` (`--password`/stdin). Today neither can run in
  CI. This is the biggest single scriptability gap.
- **`--json` on mutations and the rest of the read surface.** Missing on: `group
  delete`, `instance stop`/`exec`, `node drain`/`undrain`, `catalog
  remove`/`recommend`, `cluster eject`/`join-token revoke`, `token revoke`, `backup
  delete`, `config set`/`unset`, `login`/`logout`, `stop local`, `module
  delete`/`upgrade`(up-to-date branch)/`doctor`. Make `--json` total.
- **JSON error envelope** when `--json` is set (so failures parse), and **fix the
  overloaded exit codes** (give auth-401 its own code distinct from
  diagnostic-warning-2; wire up the dead `ExitConnError=5`).
- **`backup verify --json` returns exit 0 for an INVALID bundle** — the text path
  errors, the JSON path swallows it. A script can't tell a bad backup from a good one.
- **`catalog update` blanks `downloadUrl`/`sha256` on a rename-only edit** — it always
  PATCHes those fields even when the flags are empty. Send only changed fields (the
  way `group update` does).
- **URL-escape id/name path args.** Many commands string-concatenate ids into the
  path (`token revoke`, `crash info`, `backup verify/delete`, `cluster join-token
  revoke`, `logs daemon <node>`, `group info`). `cluster eject` and `share` already
  escape — make it universal.
- **Fix the misleading help / dead flags** listed in §3 plus: `restore` Long help
  documents `--no-files`/`--no-data` that don't exist (real flags:
  `--filesystem`/`--datastores`); `plugin new --name` advertised but unregistered;
  `module --wizard` doesn't force the wizard when `--targets` is passed; `setup
  --component` help omits the valid `dashboard`; `crash --since` is sent as wire param
  `from`.
- **Bash dynamic completions are dead** — `completion bash` uses v1
  `GenBashCompletion`, which never calls back into the binary, so resource
  completions (groups/nodes/instances) work in zsh/fish/pwsh but not bash. Switch to
  `GenBashCompletionV2`.
- **`PREXOR_OUTPUT=json` globally disables pickers** — fine, but then arg-less
  commands hard-error instead of giving a useful "arg required" message.

### P2 — cosmetic / structural (Claude Design's redesign territory)

- **Decide what to do about the synthetic metrics** in `status` (TPS/Players/Memory
  sparklines, group `SPARK` column) and `whoami="(authenticated)"`. Wire to real
  data or cut.
- **`role list` PERMISSIONS column** dumps all ~52 ADMIN perms into one cell and
  explodes table width — show a count (like `role show`) or truncate. (Confirmed ugly
  live.)
- **Unify rendering**: some lists decode typed structs, others `map[string]any`; only
  `node list` uses the shared `fetchList` helper. Single-resource views are a mix of
  cards and bespoke text. Consolidate on one table + one card component.
- **Unify theming**: make huh prompts and `--help` honor `cfg.Accent` and `--no-color`
  like everything else.
- **Symmetry gaps**: no `user show` / `user update` (but `role` has both); `--filter`
  / `--sort` / `--watch` exist only on `group list` (not `instance`/`node` list);
  `group list --watch` is a crude 2s clear-screen loop.
- **`group create` is narrow** — hardcodes `jarFile: "server.jar"`, no
  `--max-players` / `--parent` / `--update-strategy` though `group info` displays them.
- **Static-render polish**: the new `group info` non-TTY fallback prints `parent
  <nil>` for a null field (`str()` returns `"<nil>"`; `strOrDash` only catches `""`).
  Trivially fixed by null-checking in `str()`.

---

## 5. Design principles to carry into the redesign

1. **Every command works three ways:** interactive (TTY), scripted (`--json` +
   flags, no prompts), and piped (clean stdout, diagnostics to stderr). No command
   should *require* a TTY.
2. **One confirm idiom, one `--yes`.** Destructive = gated + bypassable, uniformly.
3. **`--json` is total** — reads, mutations, and errors. stdout is data; stderr is
   chatter.
4. **Help never lies.** No dead flags; help text matches behavior or the flag is cut.
5. **Never show fabricated data.** Real metric or no metric.
6. **One source of truth** for theming, target lists, table/card rendering.
7. **Cut before you polish** — merge the redundant commands/flags in §3 first, then
   redesign the smaller surface.

---

## 6. Live coverage result (Part 11)

Every read path validated green against the fleet: `status`, `node`, `group`
(incl. create/update/scale/delete write path), `instance list`, `catalog`,
`template`, `user`, `role`, `token`, `cluster status/members`, `crash`, `share`,
`backup list`, `deploy list`, `logs controller` (+`--json`), `diagnostics bundle`,
`module new`. `--json` clean where present.

Not exercisable in this run (not CLI faults): instance `start/stop/console/exec`
(no schedulable instances on the degraded/#22 single-member fleet + interactive);
`backup create` (controller 500); `cluster join-token` (needs a cluster-admin
token, not `admin`). These need a healthy quorum and/or a user-driven MC client.
