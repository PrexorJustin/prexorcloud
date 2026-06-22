# prexorctl deep-review 02 — Cross-cutting framework + modern-CLI gap analysis

Scope: the CLI-wide framework (global flags/env, output system, error/exit model,
completion, theming, help, pickers, spinners/latency, accessibility) plus the two
"framework-shaped" leaf commands `status` and `completion`. Sources read:
`cmd/{root,help,helpers,picker,completion,completion_dynamic,status,version}.go`,
`internal/api/client.go`, `internal/theme/{palette,text,pill,json}.go`,
`internal/tui/{huh_theme,spinner}.go`. Prior-pass notes 01 + 05 cross-referenced;
where the live tree has moved on from those notes it is called out.

Every block below runs the full 13-dimension rubric and ends with a verdict + tagged
findings. The doc closes with the mandated **Cross-cutting / systemic** section and a
dedicated **Missing modern verbs/capabilities** benchmark against kubectl/gh/stripe/
flyctl/cockroach/etcdctl.

Legend on each finding: dimension number(s) from the rubric · `[table-stakes|modern|
innovative]` · `[client-only|needs-server]`.

---

## A. Global flags & environment (`root.go:14-24,176-189`; `config.go`)

Current: persistent flags `--json/-j`, `--controller/-c`, `--token/-t`, `--context`,
`--no-color`, `--ascii`, `--verbose/-v`. Env: `PREXOR_OUTPUT=json`, `PREXOR_CONTROLLER`,
`PREXOR_TOKEN`, `PREXOR_CONTEXT`, `NO_COLOR`, `PREXOR_NO_BROWSER`, `CI`. Resolution
precedence flag > env > context. A pre-link gate blocks all but a small allowlist until
a context exists.

**Verdict: IMPROVE** — the flag set is coherent but mono-dimensional on output, has no
global timeout/retry/output-format/quiet/no-input knobs, and the env surface is missing
the obvious `PREXOR_*` analogues that scripts expect.

**Findings:**
- `--json` is a *boolean*, not an output selector. Every modern CLI uses
  `-o/--output {table,json,yaml,wide,name,jsonpath,template,csv}`. A bool forecloses
  yaml/wide/name/jsonpath the moment the contract ships. Promote to `-o/--output` with
  `--json` retained as a hidden alias for `-o json`. — dim 6,13 · `[modern]` ·
  `[client-only]`
- No global `--quiet/-q` and no `--no-input`. `-q` (suppress chatter, print only
  IDs/results — the `kubectl ... -o name` / `gh --jq` ergonomic) and `--no-input`
  (hard-disable every picker/prompt regardless of TTY, the explicit CI contract) are
  table stakes. Today the only way to defeat prompts is `--json`, which also changes the
  output format — two unrelated concerns fused onto one flag. — dim 7,6 · `[table-stakes]`
  · `[client-only]`
- No global `--timeout` / `--request-timeout`. The HTTP client is hard-wired to 30s
  (`client.go:88`); an operator on a slow link or scripting a fast-fail health probe has
  no knob. kubectl `--request-timeout`, etcdctl `--command-timeout`, gh have this. —
  dim 4,11 · `[table-stakes]` · `[client-only]`
- No `PREXOR_NO_COLOR`/`PREXOR_ASCII`/`PREXOR_VERBOSE` env analogues, and no
  `PREXOR_CONTROLLER_INSECURE`/CA-bundle env for TLS. Flags exist but env-only CI runners
  can't set them without argv plumbing. Mirror every persistent flag to a `PREXOR_*` env
  (cobra `viper`-style bind or manual). — dim 7 · `[table-stakes]` · `[client-only]`
- `-c`/`-t` as short flags for `--controller`/`--token` is a footgun: `-c` collides with
  the near-universal "config" short flag and `-t` with "tag/type". They are also rarely
  typed (contexts exist). Consider dropping the shorthands or reassigning. — dim 2 ·
  `[modern]` · `[client-only]`
- `PREXOR_OUTPUT=json` silently flips `flagJSON=true` at *package init* (`root.go:186`),
  which also globally suppresses every interactive picker (`picker.go:23`). A user who
  exports it for scripting will find arg-less interactive commands erroring with no
  obvious cause. Acceptable behavior, but it must be documented and ideally decoupled
  (`--no-input` should own prompt suppression, not the output format). — dim 7,9 ·
  `[table-stakes]` · `[client-only]`
- The pre-link gate (`root.go:72-81`) is good UX but runs in `PersistentPreRunE`, which
  cobra **skips for `--help`** — so the gate and theme init diverge between the normal and
  help paths (see help.go finding). Centralize. — dim 9 · `[modern]` · `[client-only]`

---

## B. Output system — human vs machine (`theme/json.go`, `helpers.go`, all renderers)

Current: two paths. Human path renders themed tables/cards/banners. Machine path is a
single `theme.PrintJSON` (indented JSON to stdout) gated on `flagJSON`, invoked ad-hoc
inside each command (and via `fetchList`/`fetchOne`).

**Verdict: REPLACE** — the output layer should be a single typed renderer with a format
enum, not a per-command `if flagJSON { PrintJSON } else { render }` copy-paste that drifts.

**Findings:**
- The `if flagJSON` branch is duplicated in ~30 commands and **drifts**: prior passes
  found `group delete`, `instance stop/exec`, `node drain/undrain`, `catalog recommend/
  remove`, `user delete`, `role delete` emit *no* JSON at all, and `status --json` returns
  a strict subset of the rendered view. A single `output.Emit(cmd, humanFn, data)` helper
  that owns the format switch would make `--json` total by construction. — dim 6,12 ·
  `[table-stakes]` · `[client-only]`
- No `-o yaml`. Operators diffing/editing config-shaped resources (groups, roles, catalog
  entries) want yaml; it is the lingua franca of declarative infra (kubectl/flyctl). —
  dim 6,13 · `[modern]` · `[client-only]`
- No `-o name` / `-o jsonpath=` / `-o go-template=` / `-o custom-columns=`. These are the
  pipeline primitives: `prexorctl instance list -o name | xargs -n1 prexorctl instance
  stop` is the single most common operator idiom and is impossible today without `jq`.
  jsonpath+template are pure client-side projections over the already-decoded payload. —
  dim 6,7,13 · `[modern]` · `[client-only]`
- No `-o wide`. List tables hard-code their columns; there is no way to surface the extra
  fields (e.g. `instance info` drops `deploymentRevision` per pass 01) without `--json`.
  `wide` is the standard "show me the hidden columns" escape hatch. — dim 6 · `[modern]`
  · `[client-only]`
- Machine output is **untyped in half the commands**: `instance list`, `group info`,
  `node info`, all `deploy` handlers decode into `map[string]any` then stringify, while
  `group/node/catalog list` use typed structs. `-o json` therefore re-emits whatever the
  server sent (good for fidelity) but the human path loses type safety and field-presence
  guarantees. Standardize on typed DTOs in `internal/api/types.go`. — dim 6,12 ·
  `[table-stakes]` · `[client-only]`
- The spinner (`tui/spinner.go:50,61,68`) writes to **stdout**, not stderr. In the human
  path that is fine, but it means the "data on stdout, chatter on stderr" contract is not
  enforced at the framework level — any future caller that spins before emitting data will
  corrupt a pipe. Move all spinner/progress writes to stderr. (Note: the prior-pass claim
  that `--verbose` corrupts stdout is now **stale** — `client.go:213,232,370,441` already
  write the `→`/`←` trace to `os.Stderr`. Good.) — dim 6,12 · `[table-stakes]` ·
  `[client-only]`
- No `--quiet` suppression of the success lines / banners. Composing prexorctl in scripts
  forces `--json` even when the caller only wants exit-code semantics. — dim 7 ·
  `[table-stakes]` · `[client-only]`
- No pagination passthrough in the output contract: `GetList` transparently unwraps the
  `{data,page,pageSize,total}` envelope (`client.go:286`) and **discards** the page
  metadata. `-o json` consumers can't see `total`/next-page, and there is no global
  `--limit`/`--all`/`--page`. For large fleets this is a correctness + perf gap. —
  dim 6,11 · `[modern]` · `[needs-server]` (server already returns it; CLI must stop
  swallowing it and add paging flags)

---

## C. Error taxonomy, JSON error envelope, exit-code model (`client.go:64-120`, `root.go:124-174`)

Current: `APIError{StatusCode,Code,Message}` parsed from a multi-shape envelope
(`{code,message}` / `{error:{}}` / RFC-7807). `ExitCodeError{Code,Message}` for typed
exits. Exit constants 0/1/2/3/4/5. `Execute()` prints `theme.PrintError` then exits via
the typed/API code (else 1).

**Verdict: IMPROVE** — the parsing is genuinely good (multi-envelope probe + body-snippet
fallback is best-in-class), but the *emission* and *exit-code* layers are broken for the
exact `--json` consumers they should serve.

**Findings:**
- **Errors are never emitted as JSON under `--json`.** Every error funnels through
  `theme.PrintError` as plain styled text on stderr (`root.go:162`). A `--json` consumer
  whose call fails gets empty stdout and an unparseable stderr line — it cannot
  distinguish a 404 from a 500 from a connection refusal without parsing English. Emit a
  structured envelope on stderr when `-o json`:
  `{"error":{"code":"NOT_FOUND","message":"...","httpStatus":404,"exitCode":4}}`. —
  dim 8,6 · `[table-stakes]` · `[client-only]`
- **Exit code 2 is overloaded.** `ExitAuthError=2` (HTTP 401, `client.go:111`) collides
  with root.go's documented convention that 2 = "validation/diagnostic failure with
  warnings" (`module doctor`, `root.go:121`). A 401 and a doctor-warning are
  indistinguishable by exit code — fatal for CI gates. Renumber: keep 2 for
  validation-warnings, move auth to a distinct code, document the full table once. —
  dim 8 · `[table-stakes]` · `[client-only]`
- **`ExitConnError=5` is dead.** Connection failures return a plain
  `fmt.Errorf("connection error: %w", ...)` (`client.go:221` and the upload/SSE paths),
  not an `APIError`, so `Execute()` maps them to generic exit 1. Wrap transport failures
  in a typed error that carries `ExitConnError` so "can't reach the controller" is
  scriptable and distinct from "the controller said no". — dim 8,3 · `[table-stakes]` ·
  `[client-only]`
- **No "did you mean…" / no remediation taxonomy.** Cobra's suggestion engine is on by
  default but the custom help/usage funcs may bypass it; more importantly there is no
  mapping from API `code` → actionable hint (e.g. 403 → "your role lacks
  `instances.start`; ask an ADMIN or run `prexorctl role show <yours>`"). The envelope
  carries a `Code` field that is currently thrown away in the human path. — dim 8,9 ·
  `[modern]` · `[client-only]` (codes already server-side)
- **No request-id / correlation surfacing.** When the controller 500s, the operator has
  nothing to hand to support. If the server returns a request-id header, echo it in the
  error envelope; if not, that's the one `[needs-server]` piece. — dim 8 · `[modern]` ·
  `[needs-server]`
- Error-message *voice* is inconsistent CLI-wide (pass 01/05): `requireAuth` uses `--`
  dashes, usage errors are lowercase-with-backticks, picker errors are terse. A single
  error-formatting helper would unify tone and guarantee the JSON envelope. — dim 8,12 ·
  `[table-stakes]` · `[client-only]`

---

## D. `prexorctl status` (`status.go`)

Current: human path spinner-fetches `/overview` + `/groups` + best-effort `/system/version`,
renders a banner, a 3-col CLUSTER/NODES/INSTANCES summary, a LIVE METRICS card, a GROUPS
table, and footer hints. `--json` returns **only** the raw `/overview` object.

**Verdict: IMPROVE** (best-in-class target: a truthful, `--watch`-able cluster dashboard
whose `--json` is a superset of the rendered view, à la `kubectl get --watch` + `flyctl
status`).

**Findings:**
- **Fabricated telemetry shown as real.** `liveMetricsCard` (`status.go:145-179`) plots
  `wave()`/`sineLike()` synthetic sparklines for TPS/Players/Memory, and `groupsTable`'s
  `SPARK (1h)` column is `repeatF(TotalPlayers,18)` — a flat line dressed as a 1h series.
  An operator can mistake fabricated data for live metrics during an incident. Either wire
  real `/metrics` data or drop the sparklines until the endpoint exists. — dim 6,9,10 ·
  `[table-stakes]` · `[needs-server]` (needs a metrics endpoint; the *honesty* fix is
  client-only — remove the fake data now)
- **`whoami()` is hardcoded `"(authenticated)"`** (`status.go:281-287`). The banner claims
  "logged in as (authenticated)". The token is a JWT — decode its `sub`/`preferred_
  username` claim client-side, or call a `/whoami` endpoint. This also blocks a real
  `prexorctl whoami` command (see missing-verbs). — dim 6,10 · `[table-stakes]` ·
  `[client-only]` (JWT claim decode needs no server change)
- **`status --json` is a strict subset of the human view** (`status.go:26-32`): scripts
  get `/overview` only — no groups, no version — while the TUI shows all three. `-o json`
  must emit the same composite the human sees. — dim 6,7 · `[table-stakes]` · `[client-only]`
- **No `--watch`/`--refresh`.** A cluster overview is the canonical thing operators leave
  open on a second monitor. `group list` already has a crude `--watch` (raw `\033[2J`);
  `status` has none. Provide a shared, interval-configurable, clean-exit watch (see
  systemic). — dim 13,9 · `[modern]` · `[client-only]`
- **No HA/leader/quorum surfacing.** Architecture note: `/cluster/members` returns each
  member's `restAddr` and quorum health; `status` shows a single green "HEALTHY" pill
  derived from counts, never the Raft membership/leader/quorum state. The one screen an
  operator hits during an HA incident is blind to HA. Add a CLUSTER MEMBERS / quorum row.
  — dim 3,6 · `[modern]` · `[needs-server]` (members endpoint exists; leader flag is the
  documented missing server bit)
- Latency feedback is good (the spinner resolves to "connected … latency 24ms"); but the
  three serial GETs (`overview`, `groups`, `version`) are sequential behind one spinner —
  parallelize for snappier first paint. — dim 11 · `[modern]` · `[client-only]`

---

## E. `prexorctl completion` (`completion.go`, `completion_dynamic.go`, `root.go:146`)

Current: generates bash/zsh/fish/powershell. `suppressFileCompletion` installs
`NoFileComp` on no-arg leaves. Dynamic completers resolve live groups/nodes/instances/
crashes/contexts/catalog from the cluster with a 3s timeout, failing silently.

**Verdict: IMPROVE** — the dynamic-completion design is genuinely modern (live resources,
`value\tdescription`, fail-silent, short timeout) but **bash is dead** and several
resource families and all enum flags are uncompleted.

**Findings:**
- **Bash dynamic completion is dead.** `GenBashCompletion` (`completion.go:23`) emits the
  cobra **v1** bash script, which does *not* call back into the binary — so every
  `ValidArgsFunction` (groups/nodes/instances/catalog/…) works in zsh/fish/pwsh but **not
  bash**. Switch to `GenBashCompletionV2(os.Stdout, true)`. This is the single highest-ROI
  completion fix. — dim 13,9 · `[table-stakes]` · `[client-only]`
- **No completion for users, roles, tokens, templates, deployments.** `user delete <TAB>`,
  `role show/update/delete <TAB>`, `token …`, `template versions/rollback <TAB>`, and every
  `deploy …` arg get only `NoFileComp`. Closed-set enums are also uncompleted: `--role`
  (ADMIN/OPERATOR/VIEWER), `group --scaling-mode`/`--routing`, `catalog --category`,
  `node --state`, `deploy --strategy`, `--permissions` (enumerable from `role show ADMIN`).
  Register completers/`RegisterFlagCompletionFunc` for all of them. — dim 5,13 ·
  `[modern]` · `[client-only]`
- **No install/`--no-descriptions` ergonomics.** gh/kubectl ship `completion <shell>
  --no-descriptions` and document `eval "$(prexorctl completion zsh)"`. Memory note says an
  `install.sh`/completions bundle was batched uncommitted for v1.1 — land it and add a
  `prexorctl completion install` convenience. — dim 9 · `[modern]` · `[client-only]`
- Dynamic completers each rebuild a client and round-trip per keystroke with no caching;
  on a large fleet `instance <TAB>` fetching all `/services` can be slow even at 3s. A
  tiny TTL cache keyed on endpoint (a few seconds) would smooth repeated tabs. — dim 11 ·
  `[modern]` · `[client-only]`
- Good: the fail-silent + 3s-timeout + `NoFileComp` suppression of cwd files is exactly
  right; keep it. — dim 7,9 · n/a.

---

## F. Theming / accent split-brain (`theme/palette.go`, `tui/huh_theme.go`)

Current: `theme.Init(accent,noColor,ascii)` swaps the Brand/BrandDp pair for purple/cyan/
green/amber and honors `NO_COLOR`/`--no-color`/`--ascii`. But `tui/huh_theme.go`
hard-codes hex and uses **lipgloss v1**.

**Verdict: IMPROVE** — the v2 theme layer is clean and accessibility-aware; the huh layer
is a parallel, frozen, purple-only copy.

**Findings:**
- **Accent split-brain.** `huh_theme.go:16-21` hard-codes `#c77dff` (purple) and ignores
  the selected accent entirely — so a user on `cyan`/`green`/`amber` gets purple pickers/
  prompts while every other surface respects their accent. Have `HuhTheme()` read
  `theme.Brand`/`theme.BrandDp`/etc. (it already reads `theme.NoColor`). — dim 9,12 ·
  `[modern]` · `[client-only]`
- **lipgloss v1 vs v2 dependency split.** huh pulls v1; the rest of the CLI is v2. Two
  color models, two style APIs, double the binary surface. Track huh's v2 migration or
  bridge the palette through a single adapter. — dim 12 · `[modern]` · `[client-only]`
- **`--ascii` does not degrade the huh prompts.** Pills and glyphs honor `ASCII`
  (`pill.go:45`, `glyphs.go`), but `HuhTheme` sets unicode selectors (`› `, `[x]`) with no
  ASCII branch. Pure-ASCII terminals get mojibake in pickers. — dim 9 (accessibility) ·
  `[table-stakes]` · `[client-only]`
- `Pill()` uses Powerline/Nerd-Font cap glyphs () that render as tofu on un-patched
  fonts (`pill.go:59-60`); there's a `SquarePill` fallback but it's never auto-selected.
  Consider detecting `--ascii`/a font hint, or defaulting to `SquarePill`. — dim 9 ·
  `[modern]` · `[client-only]`
- Good: `NO_COLOR` env honored independently of the flag; non-TTY stdout auto-forces
  no-color (`root.go:59`); every glyph has an ASCII fallback. Accessibility baseline is
  solid. — dim 9 · n/a.

---

## G. Help template (`help.go`)

Current: custom `SetHelpFunc`/`SetUsageFunc` → `printStyledHelp` with branded sections
(USAGE/ALIASES/EXAMPLES/COMMANDS/FLAGS/GLOBAL FLAGS + trailing hint). `ensureThemeInit`
re-inits theme because help bypasses `PersistentPreRunE`.

**Verdict: IMPROVE** — visually strong and consistent; two correctness bugs and a
discoverability gap.

**Findings:**
- **Help ignores the user's accent and `--json`.** `ensureThemeInit` (`help.go:35`)
  hard-codes `theme.AccentPurple` and only checks `flagNoColor`+non-tty — so `--help` is
  always purple regardless of `cfg.Accent`, and `--help -j` is still colored (a `--json
  --help` consumer gets ANSI). Load `cfg` and reuse the same accent/no-color resolution as
  `PersistentPreRunE`. — dim 9 · `[modern]` · `[client-only]`
- **No machine-readable help.** No `--help -o json` / no `explain`. kubectl `explain` and
  gh's structured help let tools/AI introspect the command tree. Low priority but on the
  modern frontier. — dim 13 · `[innovative]` · `[client-only]`
- Help does not group subcommands by topic (cobra `AddGroup`); `printStyledHelp` has the
  hook ("grouped if cobra groups are present") but no groups are defined, so the root help
  is a flat 26-command wall. Group into Orchestration / Cluster / Catalog / Auth / Modules
  / System. — dim 9 · `[modern]` · `[client-only]`
- Good: progressive disclosure (local vs global flags), examples, alias display, trailing
  "use … --help" hint. Keep. — dim 9 · n/a.

---

## H. Pickers & latency feedback (`picker.go`, `tui/spinner.go`)

Current: `interactive()` gates on `!flagJSON && stdin&stdout TTY`. `resolveArg` returns
arg[0], else picks interactively, else returns the usage string as an error. Styled
`huh.NewSelect` pickers for group/node/instance/crash/context/catalog. Spinner with
✓/✗ resolution and rich completion message.

**Verdict: IMPROVE** — the pattern is excellent where applied but inconsistently wired,
and confirmation/danger gating is fragmented (covered in systemic).

**Findings:**
- **Picker coverage is inconsistent.** `resolveArg`+picker is used by group/node/instance/
  crash/context/catalog *info-style* commands, but `instance start <group>`, `instance
  exec`, `template versions/rollback`, every `deploy …`, and **all user/role** commands
  use `ExactArgs` with no picker fallback — so an arg-less invocation just errors instead
  of offering a selector. Add `pickUser`/`pickRole`/`pickTemplate`/`pickDeployment` and
  route the holdouts through `resolveArg`. — dim 5,12 · `[modern]` · `[client-only]`
- **No multi-select / fuzzy-filter pickers.** huh supports both; bulk ops (stop N
  instances, drain N nodes) and large lists (50+ groups) would benefit. — dim 5,13 ·
  `[modern]` · `[client-only]`
- Spinner writes to stdout (see B); also there's no spinner on the many bare GETs that
  aren't `status` — `instance list` against a slow controller looks hung. A framework-level
  "spin if it takes >150ms" wrapper around `requireAuth`+GET would give uniform latency
  feedback. — dim 9,11 · `[modern]` · `[client-only]`
- Good: `interactive()` correctly fails fast in non-TTY/`--json`; pickers degrade to a
  usage error for scripts. Keep. — dim 7 · n/a.

---

# Cross-cutting / systemic

These should be fixed **once**, framework-wide, not per command:

1. **Output system → single typed renderer.** Replace the ~30 hand-rolled
   `if flagJSON { PrintJSON } else { render }` blocks with one
   `output.Emit(format, data, humanFn)` that owns a real `-o {table,json,yaml,wide,name,
   jsonpath,template,csv}` enum. Makes machine output *total* (no more commands that
   forget `--json`), kills the human/JSON drift, and is the carrier for every other output
   improvement. `[table-stakes]/[modern]` · `[client-only]`

2. **Error + exit-code model.** (a) JSON error envelope on stderr under `-o json`; (b)
   renumber exit codes so 401≠validation-warning and document the table in one place; (c)
   wrap transport failures in a typed `ExitConnError`; (d) map API `code`→remediation hint
   in one error formatter; (e) unify error voice. `[table-stakes]` · mostly `[client-only]`.

3. **Async-convergence story (`--wait`/`--for`/`--timeout`).** Architecture note: scale/
   deploy/start/stop are async — the scheduler reconciles after the call returns. There is
   **no** `--wait`/`--for=condition`/`--timeout` anywhere, so every script must poll by
   hand. Add a shared waiter (poll the resource until a predicate or timeout, with spinner/
   progress) and wire it into `instance start/stop`, `group scale`, `deploy`, `node drain`.
   This is the biggest single modernization for an async control plane. `[modern]` ·
   `[client-only]` (uses existing GET endpoints).

4. **Auth/endpoint/failover model.** Architecture note: any member serves reads + forwards
   writes; one JWT works on all members; `/cluster/members` returns every `restAddr`. Yet
   a context pins **one** controller URL with no failover, no endpoint discovery, no token
   refresh, no stored identity. Framework-wide: on a connection error to the pinned URL,
   transparently retry against the other members from `/cluster/members` (like
   etcdctl `--endpoints`). Store identity in the context so `whoami` is real. `[modern]/
   [innovative]` · partially `[needs-server]` (leader flag) but failover itself is free.

5. **Danger-gate model.** Confirmation is fragmented: `group delete`/`user delete`/`role
   delete` use a mandatory huh confirm with **no `--yes` bypass** (unscriptable); `deploy`
   uses a raw `bufio [y/N]` (no TTY check → silent no-op in CI); `node drain`, `instance
   stop --force`, `catalog remove`, `template rollback`, `deploy rollback` have **no gate
   at all**. Define one `confirm(cmd, prompt, opts)` helper: respects `--yes`/`-y`, TTY-
   guards, supports typed-name confirmation for high-blast-radius ops, and is the *only*
   way any command gates. `[table-stakes]` · `[client-only]`.

6. **Completion: ship `GenBashCompletionV2`** (revives all dynamic completion in bash),
   complete the missing resource families + every closed-set enum flag, and add an
   `install` convenience. `[table-stakes]/[modern]` · `[client-only]`.

7. **Theming: collapse the huh split-brain** — `HuhTheme()` reads the live `theme.*`
   palette (accent-aware) and honors `--ascii`; track huh→lipgloss v2 to remove the dual
   dependency. `[modern]` · `[client-only]`.

8. **Help/theme init unification** — one resolver feeds both `PersistentPreRunE` and the
   help func so accent/no-color/json behave identically; group root subcommands by topic.
   `[modern]` · `[client-only]`.

9. **Chatter→stderr invariant** — spinner/progress to stderr, data to stdout, everywhere,
   enforced by routing all of it through the output package. `[table-stakes]` ·
   `[client-only]`.

---

# Missing modern verbs / capabilities (benchmark: kubectl / gh / stripe / flyctl / cockroach / etcdctl)

Evaluated against the rubric's dimension 13. Verdict per candidate:

- **`get` / `describe` (unified, alias-driven).** Today every noun has its own bespoke
  `list`/`info`. kubectl's `get <type>` + `describe <type> <name>` with resource aliases
  (`po`,`svc`) is the dominant mental model. prexorctl could add a thin
  `get <type> [name]` / `describe <type> <name>` facade over the existing per-noun
  handlers plus short aliases (`inst` exists; add `grp`,`nd`,`tpl`). **Recommend** as an
  additive convenience, not a replacement. `[modern]` · `[client-only]`.
- **`apply -f` / declarative config + `diff`.** Groups/roles/catalog are config-shaped and
  already have full CRUD REST. A declarative `apply -f group.yaml` (create-or-update by
  name) + `diff -f` would be the headline modern feature — GitOps for the fleet, matching
  kubectl/flyctl. **Strongly recommend.** Client can do create-or-update over existing
  endpoints; a true server-side 3-way merge / `--dry-run=server` is `[needs-server]`.
  `[innovative]` · `[client-only]` for v1, `[needs-server]` for server-side dry-run.
- **`--watch`.** Only `group list` has a crude one. A shared `--watch`/`-w` (re-render on
  interval, clean exit, "updated Ns ago") on `status`, `*list`, `deploy show`. `[modern]`
  · `[client-only]` (poll) or `[needs-server]` (true streaming/SSE — the events ticket
  infra already exists in `client.go`, so SSE-backed watch is reachable).
- **`--wait` / `--for=condition`.** See systemic #3 — the top async gap. `[modern]` ·
  `[client-only]`.
- **`--dry-run=client|server`.** No dry-run anywhere. `client` (validate + print the
  request body, esp. for `group create`/`deploy`) is free; `server` needs an endpoint.
  `[modern]` · `[client-only]`/`[needs-server]`.
- **`edit`.** `kubectl edit`-style "fetch → $EDITOR → PATCH" for groups/roles/catalog would
  beat the narrow flag-by-flag `update` commands (pass 01: `group update` can only touch 5
  fields). Pairs with `-o yaml`. `[modern]` · `[client-only]`.
- **`explain`.** Machine/AI-introspectable schema for each resource type. Niche but on the
  frontier. `[innovative]` · `[needs-server]` (schema source) or `[client-only]` (from the
  bundled OpenAPI/SDK types).
- **`whoami`.** No way to see who you are / your role / token expiry. The JWT is already in
  hand — decode it client-side. Also fixes `status`'s hardcoded `whoami()`. **Recommend.**
  `[table-stakes]` · `[client-only]`.
- **`self-update` / `version --check`.** `version` (`version.go`) shows CLI+controller
  versions but never checks for a newer release or warns on CLI↔controller skew. gh/flyctl/
  cockroach all self-update or nag. The release pipeline already ships cosign-signed
  binaries (memory), so a signed self-update is feasible. **Recommend `version --check`
  first** (cheap), self-update later. `[modern]` · `[client-only]` (+ release-API
  `[needs-server]`).
- **Krew-style plugins (`prexorctl-<x>` on PATH).** There is already a first-party module/
  plugin ecosystem server-side; a client plugin model (exec any `prexorctl-foo` as
  `prexorctl foo`) is a low-effort cobra pattern that lets the community extend the CLI.
  `[innovative]` · `[client-only]`.
- **Profiles.** Contexts exist; add named *output/behavior* profiles (default `-o`,
  accent, no-input) so a CI profile vs an interactive profile is one flag. Minor.
  `[modern]` · `[client-only]`.
- **`-o template`/`jsonpath` (CLI-side templating).** Covered in B — the pipeline
  primitive. `[modern]` · `[client-only]`.
- **Structured logging of the CLI itself (`--log-file`/`--debug`).** `--verbose` prints an
  HTTP trace to stderr but there's no leveled/structured CLI log for bug reports. Minor.
  `[modern]` · `[client-only]`.
- **`completion install`, `config view`, `cluster members`/`leader`.** Operability glue;
  `cluster members` exposing `/cluster/members` (+ leader once the server flag lands) is
  the etcdctl/`consul operator raft` analogue and feeds both `status` and failover.
  `[modern]` · `[needs-server]` for leader, else `[client-only]`.

**Top net-new to build (priority order):** `--wait/--for` (async story) · `-o`
templating/yaml/name · `apply -f`+`diff` · transparent member failover · `whoami` ·
`edit` · `version --check`.
