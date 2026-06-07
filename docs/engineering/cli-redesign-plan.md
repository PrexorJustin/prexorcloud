# prexorctl CLI — visual redesign

## Context

An earlier design pass produced a high-fidelity HTML/JSX prototype of `prexorctl` defining a new visual language: brand-purple theme, lipgloss/bubbletea/huh stack, rounded cards, sparklines, animated progress bars, pill labels, bottom status bar, and seven canonical scenes (`status`, `group list`, `group info`, `instance console`, `logs --follow`, `deploy`, `setup`).

The current CLI works, but its look (tangerine theme, plain tables, no TUI models, no sparklines/progress bars/status bar) doesn't match the new design. We want to bring `prexorctl` up to that visual bar.

**User-confirmed scope decisions:**
- **CLI-only restyle.** No backend changes. Treat handoff endpoint names (`/v1/...`, WebSocket, `/clusters`, OAuth device-code, per-group metrics) as aspirational. Map design verbs onto existing endpoints (`/api/v1/...`, SSE, JWT). Where data doesn't exist (sparklines, multi-cluster), render placeholders.
- **Restyle ALL commands**, not just the 7 in the handoff. The design tokens become the global look for `node`, `token`, `user`, `role`, `crash`, `template`, `module`, `backup`, `restore`, `diagnostics`, `config`, `login` too. No interaction redesign for those.
- **Setup wizard is visual reference only** — keep the existing JWT username/password login under the hood; just put it in the new huh chrome. Don't fake OAuth/PAT/mTLS options.
- **Continue on master.** No new branch.

## Approach

Three layers:

1. **`cli/internal/theme/`** (new) — single source of truth for the design tokens.
2. **`cli/internal/tui/`** (new) — reusable bubbletea/lipgloss components: status bar, sparkline, progress bar, animated spinner, log stream, deploy progress, group-info screen.
3. **Per-command rewrites** — the 7 scenes get full redesigns; everything else swaps `output/*` styling for the new theme.

Existing infrastructure that stays unchanged: `cli/internal/api/client.go` (HTTP+SSE+ticket auth), `cli/internal/config/`, `cli/internal/setup/` (installer flows), all controller code. JSON mode (`--json`, `PREXOR_OUTPUT=json`) continues to short-circuit before any styled rendering.

## Files

### New

- `cli/internal/theme/palette.go` — color tokens (`Brand`, `BrandDeep`, `Magenta`, `Green`, `Amber`, `Red`, `Cyan`, `Blue`, `FgDim`, `FgMute`, `FgFaint`) as `lipgloss.Color`. Honor `--no-color` / `NO_COLOR` and `--ascii`. Use `termenv` for profile detection (degrade truecolor → 256 → 16). Accent swap (`cyan|green|amber|purple`) read from config.
- `cli/internal/theme/glyphs.go` — `BrandGlyph='▲'`, status dots (`●`, `◐`, `✓`, `✗`), spinner frames (`⣾⣽⣻⢿⡿⣟⣯⣷`, 90ms), progress (`█`/`░`), sparkline ramp (`▁▂▃▄▅▆▇█`), box-drawing. ASCII fallbacks (`*`, `o`, `+--|`, `\|/-`).
- `cli/internal/theme/pill.go` — `Pill(kind, text)` → styled string. Kinds: `green`/`amber`/`red`/`brand`/`cyan`/`mute`. No-color fallback strips bg/fg → `[UP]`.
- `cli/internal/theme/text.go` — helpers: `Heading`, `Subtitle`, `Hint`, `Code`, `Path`, `BulletDim`, `Added`, `Updated`, `Removed`.
- `cli/internal/tui/statusbar.go` — bubbletea-styled bottom bar (single line). Left: `▲ prexorctl` on brand-deep. Middle: current-command + key hints. Right: `● <cluster> <version>` (cyan dot). Reusable across all bubbletea models.
- `cli/internal/tui/spinner.go` — replaces `cli/internal/setup/spinner.go`. 8-frame braille at 90ms. Resolves to `✓ <msg>` line on completion (also clears partial frame from scrollback). Used inline in `status`, `setup`, anywhere we wait.
- `cli/internal/tui/sparkline.go` — `Render(values []float64, width int, color lipgloss.Color) string` using 8-level block ramp; max-scaled per-row for table cells.
- `cli/internal/tui/progress.go` — animated bar (`█`/`░`), brand fill, fg-faint empty. Resolves to `✓ <summary>` line; doesn't leave partial bar in scrollback (per design spec). Used by `deploy`.
- `cli/internal/tui/banner.go` — PrexorCloud ASCII wordmark (6 lines, brand color, magenta-glow underline). One render path; ASCII fallback collapses to plain text title.
- `cli/internal/tui/table.go` — replaces `cli/internal/output/table.go`. Sharp box drawing for tabular data, column-width hints from the design (extracted in handoff scenes), left/right alignment, header in fg-dim bold, status-pill-aware cells. Same `Table` API surface so call sites elsewhere don't churn.
- `cli/internal/tui/card.go` — rounded-border card (`╭╮╰╯─│`) with title + body. Used by `LIVE METRICS` and the three panels in `group info`.
- `cli/internal/tui/logstream.go` — bubbletea model: header line + scrollable buffer + sticky `⏸ scrolled — press End to follow` indicator + filter (`/`) + pause (`p`) + history (`j/k`). Backed by an SSE event channel. Shared by `logs --follow` and `instance console`.
- `cli/internal/tui/groupinfo.go` — bubbletea model for `group info <name>`: 3 cards in a row, INSTANCES table with selectable rows (selection bg `lipgloss.Color("#2a1840")` ≈ brand at low alpha), RECENT EVENTS list, key handler (`↵` attach, `d` drain, `r` restart, `q` quit). Uses real REST + SSE under the hood.
- `cli/internal/tui/deploy.go` — bubbletea model that drives the rollout: plan diff renderer, `[y/N]` confirm, sequence of `progress.Model`s for canary then batches, final summary block.
- `cli/internal/tui/setup_form.go` — huh form theme matching the prototype: brand left-border, brand title, dim subtitle, focused field with brand border + soft glow. Used by `cmd/setup.go` and `cmd/login.go`.

### Modified

- `cli/cmd/root.go` — add `--ascii` persistent flag; wire profile detection in `PersistentPreRun`; expose helper to read accent color from config; tighten command groupings so `--help` reads cleanly with new theme; populate cluster name + CLI version into shared status-bar context.
- `cli/cmd/status.go` — full rewrite to handoff sequence: spinner → connected line → banner → subtitle → rule → 3-col CLUSTER/NODES/INSTANCES summary → `LIVE METRICS` card with TPS/Players/Memory sparklines → `GROUPS` table with per-group 1h sparkline column → footer hint. Sparkline data gracefully degrades to flat line if metrics endpoint not yet available. Preserve `--json` shape exactly as today (no behavior change for scripts).
- `cli/cmd/group.go` — `group list` output: bordered table + header (`Listing groups in cluster <c> • filter: <f> • sort: <s>`) + footer summary (`N groups • N up • N draining • N down`). Add `--filter`, `--sort`, `--watch` flags (`--watch` re-renders every 2s; trivial bubbletea model). `group info <name>` delegates to `tui.GroupInfo`.
- `cli/cmd/instance.go` — `instance console <id>` rewritten on top of `tui.LogStream` + a header line and bottom status bar. Stdin → existing `services/<id>/command` POST (one line per `Enter`). `Ctrl-Q` detach, `Ctrl-C` sigint. Other instance subcommands restyled (table swap only).
- `cli/cmd/logs.go` — `logs --follow` (alias for `logs controller --follow` and a new cluster-wide aggregator) uses `tui.LogStream`. Per-group color hash (deterministic palette pick from group name). Flags: `--group` (repeatable), `--level`, `--since`, `--no-follow`. Until a real cluster-wide log endpoint exists, fan-in client-side over per-node SSE streams.
- `cli/cmd/deploy.go` — render plan from existing `/api/v1/groups/<name>/deploy` payload; show diff with `+`/`→`/dim prefixes; signature line if bundle is signed (data already present in deploy payload); `[y/N]` confirm; drive rollout view through `tui.Deploy` by polling `/api/v1/groups/<name>/deployments/<rev>` (no WS needed).
- `cli/cmd/setup.go` — wrap existing 4-stage installer flow in the huh form chrome from `tui.SetupForm`. Step 2 keeps the existing username/password login (presented as the only enabled option, per user direction — no faked OAuth/PAT/mTLS). Final-screen copy matches the handoff verbatim.
- `cli/cmd/login.go`, `cmd/config.go`, `cmd/version.go`, `cmd/node.go`, `cmd/token.go`, `cmd/user.go`, `cmd/crash.go`, `cmd/template.go`, `cmd/module.go`, `cmd/backup.go`, `cmd/restore.go`, `cmd/diagnostics.go`, `cmd/stop.go` — swap imports of `cli/internal/output` for `cli/internal/theme` + `cli/internal/tui`. No interaction changes; just colors/glyphs/pills/box-drawing.
- `cli/internal/output/` — keep `json.go` as-is. Convert `table.go` and the styles file into thin shims that call `cli/internal/tui` and `cli/internal/theme`, then progressively delete shims as call sites migrate. Final state: `output/` deleted, all uses on `theme`+`tui`.
- `cli/internal/setup/spinner.go` — delete; replaced by `tui.Spinner`.

### Reused as-is (do not touch)

- `cli/internal/api/client.go` — REST + SSE-with-ticket transport. The design's WS endpoints map onto these existing SSE methods.
- `cli/internal/config/config.go` — add one field: `Accent` (`purple|cyan|green|amber`, default `purple`); read by theme.
- `cli/internal/setup/{compose,service,install,download,probe,health}.go` — installer logic untouched.
- `cli/internal/scaffold/`, `cli/internal/util/`.

## Endpoint mapping (design → real)

| Design                                 | Actual                                              | Notes                                              |
| -------------------------------------- | --------------------------------------------------- | -------------------------------------------------- |
| `GET /v1/status`                       | `GET /api/v1/overview`                              | Same data shape                                    |
| `GET /v1/groups`                       | `GET /api/v1/groups`                                | —                                                  |
| `GET /v1/groups/:name`                 | `GET /api/v1/groups/{name}`                         | —                                                  |
| `GET /v1/groups/:name/metrics?range=1h`| —                                                   | Stub: empty/flat sparklines until backend lands    |
| `WS /v1/instances/:id/console`         | `SSE /api/v1/services/{id}/console` + ticket        | Stdin → `POST /api/v1/services/{id}/command`       |
| `WS /v1/logs?groups=*`                 | Fan-in `SSE /api/v1/system/logs/stream` + per-node  | Client-side multiplex                              |
| `POST /v1/deploys`                     | `POST /api/v1/groups/{name}/deploy`                 | —                                                  |
| `WS /v1/deploys/:id`                   | Poll `GET /api/v1/groups/{name}/deployments/{rev}`  | 500ms cadence; emit progress events into bubbletea |
| `GET /v1/clusters`                     | —                                                   | Single-cluster: skip step 3 of wizard              |
| OAuth device-code                      | —                                                   | Wizard offers username/password only               |

## Modes

- `--json` / `PREXOR_OUTPUT=json` — short-circuits all rendering; emits today's JSON shapes unchanged.
- `--no-color` / `NO_COLOR` — palette returns no-op styles; pills become `[UP]`/`[DOWN]`/etc.; sparklines stay (monochrome).
- `--ascii` — glyphs swap to ASCII fallbacks; box drawing → `+ - |`; sparkline → `. : | #`; spinner → `\|/-`.
- Non-tty stdout — auto-imply `--no-color`; spinners and progress bars become single completion lines (no animation frames in pipes).

## Migration order (single PR or progressive — user's call at execute time)

1. Land `cli/internal/theme/*` and `cli/internal/tui/{spinner,table,card,pill,banner}`. Keep old `output/*` working in parallel.
2. Rewrite `cmd/status.go` end-to-end (most visible surface, exercises theme + sparkline + card + table + spinner + banner).
3. `cmd/group.go` (list + info), `cmd/setup.go` (wizard chrome).
4. `tui/logstream.go` + `cmd/logs.go` + `cmd/instance.go` (console).
5. `tui/deploy.go` + `cmd/deploy.go`.
6. Sweep remaining commands (node/token/user/role/crash/template/module/backup/restore/diagnostics/config/login/version/stop) — mechanical theme swap.
7. Delete `cli/internal/output/` + `cli/internal/setup/spinner.go`.

## Verification

- **Visual diff against prototype.** Run each scene against a local controller and eyeball the output side-by-side with the design-pass HTML mockup (column widths, copy strings, glyph choices). Treat the mockup as final and matched-exactly.
- **`--json` regression.** For every redesigned command, run `prexorctl <cmd> --json` before and after; diff the output. Should be byte-identical.
- **Color-profile matrix.** Run each scene under: truecolor terminal, `TERM=xterm-256color`, `NO_COLOR=1`, `--ascii`, and stdout piped to `cat`. No crashes; degraded but legible output in each.
- **Tests.** `go test ./cli/...` — existing tests must pass. Add table-driven tests for `theme.Pill`, `tui.Sparkline` (ramp boundaries), `tui.Progress` rendering at 0/50/100%, log-line formatter (level coloring + player-name bolding + item brackets).
- **End-to-end smoke against a running stack.** `prexorctl setup` → `status` → `group list` → `group info <name>` → `instance console <id>` (type `list` and detach with Ctrl-Q) → `logs --follow` → `deploy <group>` (dry-run / cancel at confirm). All seven scenes exercised once on a real controller.
