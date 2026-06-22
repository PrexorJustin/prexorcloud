# prexorctl Visual Design Handoff — delivery template

**For Claude Design to fill in. For Claude Code (the implementing engineer) to build from.**

This is the format the prexorctl visual redesign must be delivered in. Claude Design
designs the *look & feel only* (no functionality changes) of the **target** CLI
surface described in `cli-target-catalog.md`. Every section below must be concrete
enough that an engineer can implement it directly against the existing Go +
charmbracelet code without re-deriving intent. Where a value is a choice, give the
exact value (hex, glyph, padding count), not a description.

Implementation lives in `cli/internal/theme/` (palette, glyphs, pills, text) and
`cli/internal/tui/` (banner, table, card, chart, sparkline, spinner, progress,
statusbar, list_header, groupinfo, logstream, deploy, huh_theme). Reference the
target screens in `cli-target-catalog.md` and the brand tokens in
`design-system/tokens.json`.

---

## 0. Design vision (1–2 pages)

- The feeling in one paragraph (what an operator should feel using it).
- 5–7 guiding principles (e.g. "data is calm, danger is loud", "color carries
  meaning, never decoration").
- Anti-goals (what to avoid — e.g. no fabricated telemetry, no rainbow output).
- A "north-star" mockup: one hero screen (likely `status`) rendered as it should
  feel, in ANSI, dark + light.

---

## 1. Theme token spec

The single source of truth for color/spacing. Maps CLI semantic roles → the Reef
("Quiet Studio / Reef") tokens in `design-system/tokens.json`. Fill the table with
**exact hex** for both terminal modes, plus the fallback behavior.

| Semantic role | Dark hex | Light hex | --no-color fallback | Used for |
|---------------|----------|-----------|---------------------|----------|
| primary / accent | `#4ec5d4` | `#0c8aa8` | (bold) | … |
| success | | | | |
| warning | | | | |
| danger / error | | | | |
| muted / secondary | | | | |
| heading | | | | |
| border / rule | | | | |
| selection (picker) | | | | |

Also specify:
- **Accent override.** The user's `cfg.Accent` (purple|cyan|green|amber) must map
  onto the role table — give each accent's primary/selection hex. The default is
  Reef. (Today huh + `--help` hardcode purple — kill that.)
- **Spacing scale** (cell padding, column gap, card padding, section gaps) as
  integer cell counts.
- **Type treatment** in a terminal: which roles are bold/dim/underline.
- **`--ascii` + `--no-color` rules**: how every color/glyph degrades.
- **Implements in:** `cli/internal/theme/palette.go` (+ `text.go`). Note any token
  that should be generated from `design-system/tokens.json`.

---

## 2. Component specs

One subsection per component. For each: **anatomy** (labeled parts), an
**ANSI/ASCII mockup** (both color and `--ascii`), the **exact tokens** (colors from
§1, spacing, glyphs), **states** (normal / selected / disabled / error / empty),
and the **target file** it implements into.

Required components (map 1:1 to the code):
- **Table** (`tui/table.go`, `tui/borderless_table.go`) — header style, column
  alignment/rhythm, truncation rule (esp. the `role list` permission-count case),
  zebra/no-zebra, footer/count line, the `-o wide` column treatment.
- **List header** (`tui/list_header.go`).
- **Card / key-value view** (`tui/card.go`) — for `group info`, `node info`,
  `instance info`, `whoami`, `auth status`, `module describe`. Null fields render
  as `—` (no `<nil>`).
- **Status pill / state dot** (`theme/pill.go`) — every state value
  (ONLINE/DRAINING/…, deployment states, token status, share status, health
  verdicts) → color + glyph + ascii fallback.
- **Glyph set** (`theme/glyphs.go`) — every glyph + its `--ascii` twin (★, ✓, ✗,
  →, ←, leader marker, tree markers, spinner frames).
- **Spinner** (`tui/spinner.go`) and **progress** (`tui/progress.go`) — including
  the new async `--wait` progress line (`group edge: 2/4 instances running…`).
- **Banner** (`tui/banner.go`) — the `status` header.
- **Status bar** (`tui/statusbar.go`).
- **Sparkline / chart** (`tui/sparkline.go`, `tui/chart.go`) — design for **real
  data only**; if a screen has no real metric, specify the omitted state (no fake
  waves).
- **Picker** (huh select; theme via `tui/huh_theme.go`) — arg-less selection.
- **Confirm prompt** (`tui/huh_theme.go` + the danger-gate helper) — two variants:
  simple `y/N` and **typed-name** confirm for blast-radius ops. Specify the copy
  pattern and how the resource name/consequence is emphasized.
- **Input prompt** (huh, masked for passwords).
- **Error presentation** — the styled human error (TTY) AND a note that under `-o
  json|yaml` it is the JSON envelope on stderr (design the human form only).
- **Empty state** — the pattern for "no groups / no nodes / no crashes yet".
- **Success / done** — the confirmation line after a mutation.
- **`--help`** — styled help layout honoring the accent (today hardcoded purple).

---

## 3. Hero screen designs (before → after)

Full ANSI mockups (dark; note light deltas) for each, driven by the exact
fields/columns the command **Delivers** per `cli-target-catalog.md`:

1. `status` (overview dashboard) — banner + cluster/nodes/instances summary +
   real-metric card (or omitted) + groups table. **No fabricated data.**
2. `group info` (interactive panel + the static non-TTY fallback).
3. `instance console` / `logs` (full-screen stream + the piped plain/JSON-lines
   form).
4. `deploy <group>` rollout view (plan preview + live progress).
5. A representative `list` (e.g. `group list` or `cluster members` with leader/role/
   health/quorum).
6. One destructive flow end-to-end (e.g. `cluster eject`): confirm → progress →
   success.
7. An error screen and an empty-state screen.

For each: caption what changed vs. today and why.

---

## 4. Implementation map

A table the engineer reads to know where each design decision lands. Every row:
design element → target file → note.

| Design element | Target file | Note |
|----------------|-------------|------|
| color roles / accent | `cli/internal/theme/palette.go` | generated from tokens.json? |
| glyphs + ascii | `cli/internal/theme/glyphs.go` | |
| pills/state dots | `cli/internal/theme/pill.go` | |
| table | `cli/internal/tui/table.go` | |
| … | … | |

Flag anything that needs a **new** file or a shared helper that doesn't exist yet.

---

## 5. Cross-cutting rules (state once, applied everywhere)

- Color/glyph degradation for `--no-color` and `--ascii`.
- stdout = data only; all spinners/progress/chrome to stderr.
- Light vs dark terminal handling.
- How the accent override repaints every component.
- Any motion/animation (spinner cadence, progress refresh interval) as concrete
  values.

---

## 6. Open questions for the engineer / product

List anything ambiguous that needs a decision before implementation (e.g. "does
the controller expose a real TPS metric for the `status` card, or omit it?").
