# PrexorCloud Design System

> **Kubernetes for Minecraft.**
> A serious-feeling infrastructure design system for a self-hosted, MIT-licensed
> Minecraft cloud orchestrator. Tone is closer to **Linear / Vercel / Tailscale**
> than to Pterodactyl or Plesk — sober, dense, and confident — but never sterile.
> The product is fundamentally playful (Minecraft) so the system permits a single
> playful gesture (the voxel cloud logo) and otherwise stays out of the way.

---

## 1. What this is for

This system is consumed by four surfaces that must feel like the same product:

| Surface | Stack | Theme primary |
|---|---|---|
| **Marketing site** + docs | Astro / Starlight, Tailwind v4 | Dark — primary |
| **Web Dashboard** | Nuxt 4 SPA, shadcn-vue (Reka UI), Tailwind v4 | Dark — primary |
| **CLI** (`prexorctl`) | Go + Cobra, ANSI 256 / truecolor terminals | n/a |
| **Social / OG / README** | Static SVG + PNG | Dark |

All four mirror the same token set (`colors_and_type.css` / `tokens.json`) in
their own stack — they don't import it at build time, they implement it. The
CLI maps the same hues to ANSI approximations.

---

## 2. Mood board

| Reference | What we take from it |
|---|---|
| **Linear** | Information density without anxiety. Soft glows over deep navy. Page header pattern (`gradient title + muted subtitle + actions on the right`). |
| **Vercel** | Confident black surfaces, single saturated accent, restraint with color. Mono-font interplay with Inter. |
| **Tailscale** | Friendly-but-serious infra voice. Diagrams over screenshots. |
| **k9s** | The CLI is a real product, not an afterthought — first-class glyph + color rules. |
| **Sentry** | Status badges, dense table rows, command-palette-led navigation. |

What we **do not** take: the cartoon mascots of Pterodactyl/Plesk; bluish-purple
gradients; soft shadows on tinted cards; emoji UI.

---

## 3. Sources & access

Every surface this system describes lives in this monorepo — read the real
source, not a recreation:

| Surface | Path |
|---|---|
| Web dashboard (Nuxt 4 SPA) | `dashboard/` |
| Marketing site + docs (Astro) | `website/` |
| CLI (`prexorctl`) | `cli/` |
| Browser setup wizard (Vue) | `installer/` |
| Brand assets | `website/public/` (today: only `favicon.svg`) |

The token files in this folder (`colors_and_type.css`, `tokens.json`) are the
written reference. Each surface implements these tokens in its own stack and
cites this folder as canonical (e.g. `website/src/styles/docs-theme.css`).

---

## 4. Index — what lives where

This folder holds the canonical written rules, the token source of truth, and a
small generator that emits machine-consumable artifacts:

```
.
├── README.md            ← you are here: full voice + visual spec
├── SKILL.md             ← agent-skill manifest for generating on-brand artifacts
├── colors_and_type.css  ← canonical, human-edited tokens + utility classes (both themes)
├── tokens.json          ← machine-readable token source (single source of truth)
├── build-tokens.mjs     ← zero-dependency generator: tokens.json → dist/
├── __tests__/           ← freshness + parity guards (run via `node --test`)
└── dist/                ← GENERATED — do not edit
    ├── tokens.css       ← CSS custom properties (:root / .dark / .light)
    ├── tokens.ts        ← typed constants for JS logic (mermaid palette, etc.)
    └── tokens.json      ← normalized build output (e.g. for Figma sync)
```

`tokens.json` is the source of truth; `colors_and_type.css` stays the canonical
human-edited reference. A CI parity test (`__tests__/tokens.test.mjs`) proves the
two never drift — every color in `tokens.json` must resolve to the same value in
`colors_and_type.css`, and the committed `dist/` must match a fresh build. Run
`node build-tokens.mjs` after editing `tokens.json` and commit `dist/`.

The earlier `ui_kits/` recreations were removed: the real dashboard, website,
CLI, and installer (see §3) are the live references now. Logo/preview assets
are not committed here — see §8.

---

## 5. Content fundamentals

We sound like an SRE writing for other SREs. Calm, plain, technical.

### Voice principles
1. **Describe what works, not what's coming.** Documented features only.
2. **Name things by their role, not their type.** *Instance*, not *server*.
   *Node*, not *machine*. *Group*, not *fleet*.
3. **Verbs over nouns.** "Start an instance" beats "Instance creation."
4. **No hype.** *No* "magical", *no* "blazing-fast", *no* "supercharged".
   We have receipts (benchmarks, p99 numbers) instead.
5. **Direct address.** Marketing uses *you*. Docs use the imperative.
   Dashboard messages are first-person plural for *our* errors and second-person
   for *yours* ("We can't reach this node" / "Add at least one group to start").
6. **Sentence case everywhere except button labels.** *"Start instance"*, not
   *"Start Instance"*. Buttons are Title Case.
7. **Numbers are nouns.** Always tabular, never abbreviated below 1k.
   *3 nodes*, *147 players*, *1.2k crashes*.
8. **No emoji in product UI.** They appear nowhere in the dashboard, CLI, or
   docs. README/social media may use them sparingly (<1 per heading).
9. **Acronyms expanded on first use** in docs, never in the dashboard.
10. **Failure messages name the fix.** Not *"Connection failed"* but *"Can't reach
    node-2 on :8080 — check the firewall or run `prexorctl node ping node-2`."*

### Do / don't
| Do | Don't |
|---|---|
| "Start instance" | "Spin up a new server" |
| "Node `node-2` is draining — 4 instances will move." | "🚀 Migrating workloads!" |
| "We couldn't write to disk on `node-2` (ENOSPC)." | "Oops! Something went wrong." |

### Glossary (canonical terms)
- **Node** — a host machine connected to the cluster.
- **Instance** — one running Minecraft server process on a node.
- **Group** — a template + scaling policy that spawns instances (e.g. `lobby`).
- **Network** — a Velocity/BungeeCord proxy fronting one or more groups.
- **Template** — versioned filesystem + JVM args used to start instances.
- **Catalog** — first-party + third-party server platforms (Paper, Fabric, …).
- **Module** — a runtime-loaded plugin extending the dashboard backend.
- **Crash** — a non-graceful instance exit (OOM / SIGKILL / ERROR).

---

## 6. Visual foundations

> **Palette: "Quiet Studio / Reef".** A softened-cyan ("reef") accent over an
> ink-dark or warm-sand basin — the direction all live surfaces ship today.
> (The earlier saturated cyan-9/slate canon was reconciled to this in 2026-06.)

### Two themes, one system
- **Dark** is the default. Marketing site and dashboard both ship dark-first.
  Background: `#0a0a10` (ink-1). UI sits on glass panels at `#ffffff0f–#ffffff1f`
  over the base — never solid charcoal cards.
- **Light** ("Soft Scandinavian") is for docs and dashboard's day-mode.
  Background: `#f5f3ed` (sand-1) — warm, not gray. Cards lift to `sand-3` (`#ffffff`).
  Both feel native, not derivative.

### Color
- **Primary** is reef-dark `#4ec5d4` (dark) / reef-light `#0c8aa8` (light). One
  single softened-cyan accent across both surfaces. Never two accents in one screen.
- **Secondary** is violet `#8b5cf6` — used sparingly for groups/templates and
  chart-2. Avoid as a button background; secondary is a category color, not a CTA.
- **Status colors** are encoded *both* as dot + label (never color alone — colorblind safe):
  - 🟢 success → `#10b981` dark / `#2f7d4a` light — RUNNING / ONLINE
  - 🔵 primary → reef — STARTING / SCHEDULED / PENDING
  - 🟡 warning → `#f59e0b` dark / `#a8651e` light — DRAINING / CORDONED / STOPPING
  - 🔴 destructive → `#f43f5e` dark / `#a02d2d` light — CRASHED / UNREACHABLE
  - ⚪ muted → ink-7 `#7c7c8a` — OFFLINE / STOPPED
- **All combinations meet WCAG AA** at the body size. The primary on background
  meets AAA. Validated using APCA / contrast ratio.

### Typography
- **UI**: Inter (variable). 14–16px body, 24px page title.
- **Display**: Inter Tight (variable). Marketing hero only — never inside dashboard.
- **Mono**: JetBrains Mono. Code, terminal, IDs, IP addresses, table numerics.
- 8-step modular scale, 1.2 ratio. See `colors_and_type.css` `--text-*`.
- Tracking: tight on display (`-0.02em` to `-0.04em`), normal on body, wide
  uppercase on eyebrows + table column heads (`0.12em`).

### Backgrounds & texture
- **Ambient glows**: 2–4 large blurred color blobs (cyan, violet, occasionally
  green) sit *fixed* behind every dashboard view at low opacity. They drift on
  page load only — no continuous animation. See `dashboard/AmbientGlows.vue`.
- **Dot pattern**: a 16px radial-gradient dot grid at ~8% opacity. Used inside
  hero cards and dialog headers. Never on the body itself.
- **No full-bleed photos.** No gradients-as-section-dividers.
- **No textures or noise.** The dot pattern is the only texture allowed.

### Cards
- The **glass card** is the unit of UI. `.bg-glass/60 backdrop-blur-xl
  rounded-2xl border border-glass-border p-5`. Hover lifts it to
  `glass-hover` + `glass-border-hover`. No drop shadow on cards in dark mode —
  the border + blur do the lifting. Light mode adds a `--shadow-sm`.
- Cards may carry a corner gradient *overlay* keyed to status:
  `from-success/10` for running, `from-destructive/10` for crashed,
  `from-warning/10` for transitions. Always `from-X/10 to-transparent` —
  never deeper than 10%.
- Status dot in bottom-right of the card icon — 12px circle, 2px
  background-colored border so it floats.

### Animation
- **Easing**: cubic-bezier(0.4, 0, 0.2, 1) (`ease-in-out` Tailwind default) for
  most things. Spring physics nowhere — this is infra.
- **Durations**: 150ms (micro: button hover), 200ms (standard transitions),
  300ms (card hover), 500ms (dialog enter, exit). No animation longer than
  500ms in the product.
- **Reduced motion**: every animation cuts to 0.01ms via `@media
  (prefers-reduced-motion: reduce)`.
- **Card-poof animation** (`@keyframes card-poof`) for delete: scale + blur out
  in 500ms. Only delete uses it; never use it for navigation.

### Hover & press
- **Hover** = lighter background tint (`glass-hover`) + slightly stronger border.
  Never a color shift; primary buttons darken `bg-primary/90`.
- **Press** = no shrink, no inset shadow. Just the active focus ring.
- **Focus ring** = 3px `ring-ring/50` at 50% opacity. Always visible on
  keyboard-only nav.
- **Disabled** = `opacity-50` + `pointer-events-none`. No grayscale filter.

### Borders
- Borders carry the UI rather than shadows. `1px solid var(--glass-border)`
  (`#ffffff15` dark, `var(--sand-7)` light). `--glass-border-hover` is one
  step stronger.
- Sticky table headers use a `border-b` only — no top border, no bg fill.

### Shadows / elevation
- Dark mode shadows are tinted with the primary color glow rather than black:
  `--shadow-glow-primary` lifts a CTA button; `--shadow-lg` is reserved for
  dialogs that overlay the page.
- Light mode uses neutral shadows: `--shadow-sm` on cards, `--shadow-lg` on
  dialogs.
- Never combine a shadow + glow on the same element. Pick one.

### Transparency & blur
- Glass surfaces are `8–15%` opaque white over the background, with `blur(20px)`
  on the backdrop. Always over a non-flat background — glass on glass is OK
  (palette/dialog over a page) but glass on solid black is not (no point).
- The popover/command palette uses `popover` (~93% opaque) — readable text first,
  glass second.
- Avoid `backdrop-filter` on full-screen overlays — performance.

### Corner radii
- `rounded-md` (12px) — buttons, inputs, small chips.
- `rounded-xl` (16px) — cards, badges with icons.
- `rounded-2xl` (24px) — large cards, dialogs, the entire dashboard surface.
- `rounded-full` — avatars, status dots, badges-without-icons (pills).
- All radii reference one token `--ui-radius: 0.75rem` so they scale together.

### Layout rules
- **Sidebar**: 256px expanded, 64px icon-collapsed. Always sticky, always
  on the left. Dark gets a subtle separator shade `var(--sidebar)` slightly
  lighter than `--background` to read as a panel.
- **Page header pattern** is fixed: `h1 (gradient title) + muted subtitle +
  actions row on the right`. Never two headers per page.
- **Section gap** is `var(--space-5)` (20px) within a page; `var(--space-6)`
  (24px) between sections. Marketing uses `var(--space-24)` (96px) between
  sections.
- **Max content width**: 1280px (marketing), full-width inside dashboard.

---

## 7. Iconography

We use **[Lucide](https://lucide.dev)** as the only icon family.
The dashboard already imports `lucide-vue-next`; the website pulls from `lucide-static`
(SVG sprite); the CLI uses Unicode glyphs (see CLI section).

### Rules
- **Stroke width 2px**, no exceptions. Lucide's default.
- **Sizes**: `size-3.5` (14px) for inline-with-text, `size-4` (16px) for buttons
  and table cells, `size-5` (20px) for nav, `size-6` (24px) for stat-card icons,
  `size-8` (32px) and up only inside empty states.
- **Color** inherits from text via `currentColor`. Never set fill on a Lucide icon.
- **No emoji** anywhere in the product UI. Never used as substitutes for icons.
- **Custom icons**: if Lucide doesn't have it, draw a *new* one in the same
  style (24×24 viewBox, 2px stroke, round caps + joins, no fill) and add it to
  `assets/custom-icons/`. So far we have **none**.

### Established assignments (from the dashboard)
| Icon | Concept |
|---|---|
| `Cloud` | The product (logo) |
| `Server` | Node |
| `Box` | Instance |
| `Layers` | Group |
| `Network` | Network/proxy |
| `FileCode` | Template |
| `Package` | Catalog |
| `Puzzle` | Module |
| `AlertTriangle` | Crash |
| `Users` | Players |
| `Gauge` | Dashboard overview |
| `Settings`, `User`, `LogOut` | Account/system |
| `Wifi`, `WifiOff` | Connection state |
| `Play`, `Square`, `Pause` | Instance state actions |

CDN: `https://unpkg.com/lucide@latest/dist/umd/lucide.js`
Static SVGs: `https://unpkg.com/lucide-static@latest/icons/<name>.svg`

---

## 8. Logo treatment

### Mark
The proposed mark is a **voxel cloud**: a Minecraft-block silhouette of the
Lucide cloud icon. This is the one place we explicitly fuse "infra cloud" and
"Minecraft" into a single object. It's intentionally pixel-aligned (4×4 cells)
to read as Minecraft at thumbnail sizes; the cyan plate keeps it serious.

Proposed file set (only the favicon exists today — see *Status* below):

| File | Use | Status |
|---|---|---|
| `website/public/favicon.svg` | 32×32 mark for browser tabs / app icon | **exists** |
| logomark.svg | App icon, OG center mark, anywhere ≤ 64px | not produced |
| logo-dark.svg | Full lockup for dark surfaces (marketing, GitHub README) | not produced |
| logo-light.svg | Full lockup for light surfaces (docs day mode) | not produced |

### Usage rules
- **Clearspace**: at least the height of one wordmark cap (≈ 16px at 24px wordmark).
- **Minimum size**: 24px for the mark alone, 96px for the lockup.
- **Backgrounds**: dark variant on `ink-1`, `ink-2`, or `ink-3`. Light variant
  on `sand-1`–`sand-4`. Never on a saturated color other than the mark's own plate.
- **Don't**: rotate, recolor the plate, drop-shadow, or extract the cloud onto a
  non-rounded plate. The plate + cloud are one shape.

### Status: proposed, not yet produced
The voxel-cloud mark is a **proposal**. The only brand asset committed to the
repo is `website/public/favicon.svg`; the lockups above haven't been drawn.
When they are, commit them to `website/public/` and link them here.

---

## 9. CLI design tokens (`prexorctl`)

The CLI is a first-class surface. It mirrors the dashboard's color semantics in
the terminal so the two products are visually linked.

### Color usage
| Role | Truecolor | ANSI-256 | When |
|---|---|---|---|
| `primary` | `#06b6d4` | `38;5;38` | Section headers, prompts, links |
| `success` | `#10b981` | `38;5;36` | RUNNING, deploy success, ✓ |
| `warning` | `#f59e0b` | `38;5;214` | DRAINING/STARTING, deprecation, ! |
| `error` | `#ef4444` | `38;5;203` | CRASHED, exit ≠ 0, ✗ |
| `muted` | `#64748b` | `38;5;243` | Secondary metadata, timestamps |
| `bold-fg` | (no color) | `1` | Headers, labels |

### Glyphs (Unicode, no emoji)
| Glyph | Role | Use |
|---|---|---|
| `✓` U+2713 | Success ticker | Per-step completion |
| `✗` U+2717 | Failure ticker | Per-step failure |
| `●` U+25CF | Status dot, filled | RUNNING, ONLINE |
| `○` U+25CB | Status dot, hollow | STOPPED, OFFLINE |
| `→` U+2192 | Forward action | "next:", "transitions to" |
| `▶` U+25B6 | Section start, command | Header bullets |
| `│ └ ├` | Tree | Hierarchy in `node tree`, `instance ls` |
| `…` U+2026 | Truncation | Long IDs in narrow terminals |

Spinner uses **Braille pattern frames** `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏` (10-frame, 80ms) — the
same set as `cobra-cli` / `bubbles`.

### Section header pattern
Every command output starts with:
```
  ▶ <BOLD PRIMARY HEADER>
  <muted one-line subtitle>
  <one blank line>
```

Tables use 2-space indent, light borders (`├ ─ ┼`), header row in bold + uppercase.

### Examples — see the real CLI output in `cli/` (`cli/cmd/`, `cli/internal/tui/`).

---

## 10. Illustration style

**One direction: structured wireframe diagrams.**

Line-art only, single-stroke, drawn on a faint dot/grid background.
Components — nodes, instances, groups — appear as rectangles + arrows, not
characters. Color is *only* used to encode state (cyan for the highlighted
path, gray everywhere else). No people, no characters, no isometric
illustrations, no Minecraft mobs.

This is reproducible by a small team without an illustrator and works at any
size without resampling artifacts.

### AI-generation prompt prefix
> *"Technical infrastructure diagram, single-stroke line art, 2px cyan stroke
> on dark `#0a0a10` background, dot-grid backdrop at 8% opacity, isometric or
> top-down boxes connected by arrows, no text labels, no characters, no
> shading, no fills except `#4ec5d4` highlights at 30% opacity. Style: Linear
> docs / Tailscale architecture diagrams / Vercel system diagrams."*

We do **not** generate marketing hero illustrations with AI. Diagrams only.

---

## 11. Caveats

- **Tokens are now generated, but surfaces don't import them yet.**
  `build-tokens.mjs` emits `dist/tokens.{css,ts,json}` from `tokens.json`, and CI
  enforces that `tokens.json` and `colors_and_type.css` never drift. Each surface
  still *reimplements* the tokens in its own stack (Tailwind v4 in `dashboard/` +
  `website/`, ANSI in `cli/`) rather than importing `@prexorcloud/design-system`.
  Wiring the surfaces onto `dist/` (the npm-workspace import) is the remaining
  E.1 work. Until then this folder is canonical — and that is now **enforced**:
  `__tests__/surface-drift.test.mjs` asserts every raw scale (reef/ink/sand/state)
  in `website/`, `dashboard/` and `installer/` equals the canon, so a surface that
  re-tunes a colour fails CI until `tokens.json` is updated to match. (Semantic
  tokens stay surface-specific aliases; only the raw scales are pinned.) The
  Mermaid docs palette already consumes the generated tokens (§4).
- **The voxel-cloud logo is proposed, not produced** (see §8). Only
  `website/public/favicon.svg` exists today.
- **Fonts**: Inter / Inter Tight / JetBrains Mono. Self-host the `.woff2` files
  in each surface rather than relying on a CDN for production.
- **WCAG AA** has been validated for body and large text combinations on both
  themes. The few combinations that fall short of AAA (reef on ink-1 at
  small sizes) are reserved for *non-text* surfaces (button fills, dot
  indicators) where contrast is decorative, not informational.
- **CLI / ANSI tokens are tracked separately.** `tokens.json`'s `color.ansi`
  block and §9 below still describe the terminal palette; the CLI uses its own
  theme system (purple default) and was not part of the Quiet Studio color
  reconciliation. Revisit if the CLI accent is re-tuned to reef.
