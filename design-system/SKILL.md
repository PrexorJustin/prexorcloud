# PrexorCloud — Design System Skill

> Skill manifest for design tooling producing any artifact under the PrexorCloud
> brand: dashboard screens, marketing pages, docs blocks, social cards, READMEs,
> CLI help text. Read this in full before generating anything.

## Identity in one sentence
**PrexorCloud is Kubernetes for Minecraft** — an open-source, MIT-licensed,
self-hosted control plane that orchestrates Minecraft servers across a fleet
of nodes. The visual + verbal system pulls from Linear, Vercel, and Tailscale,
*not* from gaming/community panels (Pterodactyl, Plesk).

## Mandatory imports
Every artifact starts from the same token file:
```html
<link rel="stylesheet" href="<root>/colors_and_type.css"/>
```
For machine consumption: `tokens.json`. Treat that file as the source of truth.
Never invent new color values; pick from the scale or use `color-mix()` with
existing tokens.

## Voice & content rules (don't violate these)
1. **Sound like an SRE talking to other SREs.** Calm, plain, technical.
   No hype. *Never* "magical", "blazing-fast", "supercharged".
2. **Sentence case** in body, headings, and dashboard chrome.
   **Title Case** only inside button labels.
3. **Imperative mood** in the dashboard ("Start instance", "Drain node"),
   **second person** in marketing ("You can run…"), **declarative** in docs.
4. **No emoji in product UI.** Acceptable in README badges, *never* inside the
   dashboard, CLI, or docs.
5. **Failure messages name the fix.** Never *"Connection failed"*; instead
   *"Can't reach node-2 on :8080 — check the firewall or run
   `prexorctl node ping node-2`."*
6. **Numbers are nouns.** Tabular numerics, no abbreviation under 1k.
   *3 nodes*, *147 players*, *1.2k crashes*.
7. **Glossary terms only.** Use *Node / Instance / Group / Network / Template /
   Catalog / Module / Crash* exactly as defined in `README.md` §5.
8. **No filler content.** If a section feels empty, leave it empty or remove
   it. Do not pad with placeholder paragraphs.

## Visual rules
- **Dark is default** (`--background: #0a0a12`). Light theme = warm Sand
  (`#D8D5CD`), never gray.
- **One saturated accent per screen** — the cyan `--primary`. Violet is a
  category color (groups/templates/chart-2), never a CTA background.
- **Cards are glass**, not charcoal: `bg-glass + border + backdrop-blur(20px)`.
  Add a corner gradient `from-{status}/10 to-transparent` to encode state.
- **Status is dot + label, never color alone.** Map: success→Running/Online,
  primary→Starting/Scheduled, warning→Draining/Stopping, destructive→
  Crashed/Unreachable, muted→Stopped/Offline.
- **Borders carry the UI**, not shadows. Dark mode shadows are tinted glows
  (`--shadow-glow-primary`) reserved for elevation, not for cards.
- **No textures.** The 16px radial-dot pattern at 8% opacity is the *only*
  texture allowed, and only inside hero / dialog headers.
- **Animation is brief** (≤500ms) and uses `ease-in-out`. Spring physics
  *nowhere*. Always honor `prefers-reduced-motion`.
- **Iconography**: Lucide @ stroke-width 2, sizes 14/16/20/24px. No fills,
  no custom families.

## Type rules
- **UI**: Inter (variable). Body 14–16px, page title 24px, tight tracking on
  display.
- **Display**: Inter Tight, marketing only. Tracking `-0.04em` for hero scale.
- **Mono**: JetBrains Mono. All terminals, IDs, IP addresses, table numerics.
- **Eyebrow / column heads**: 11–12px, weight 600, `0.12em` tracking, uppercase.
- **Page header pattern** is fixed: gradient title (linear-gradient(to left,
  primary 60%, foreground)) + muted subtitle + actions row right.

## Component vocabulary (don't reinvent)
These components already exist in the dashboard — read the real implementations
under `dashboard/app/components/` rather than recreating them:
- glass card — the universal status-tinted card (the unit of UI)
- page header — gradient title + muted subtitle + actions row
- buttons — primary / outline / ghost / destructive @ 3 sizes
- status badges — instance + node states (dot + label, never colour alone)
- callouts — info / OK / warning / error
- terminal / code block — for code samples (see `website/src/components/docs/`)
- sidebar — group structure (Overview / Infrastructure / Content / Monitoring /
  Administration)
- dense table row — sticky-header table

## Surfaces (what to look at when generating each kind of artifact)
Read the real source — these surfaces all live in the monorepo now:

| If you're making… | Read first | Notes |
|---|---|---|
| A dashboard screen | `dashboard/app/` (pages + components) | 256-px sidebar, glass cards, gradient page title. Live data via WebSocket. |
| A marketing page | `website/src/` (Astro pages + components) | Sticky glass nav, hero with browser preview, feature 3-grid, two-up alternating, CTA band, 4-col footer. |
| A docs page | `website/src/` (light variant, Starlight) | Body 16/1.65, sidebar TOC, code block from `website/src/components/docs/`. |
| CLI output / help text | `cli/cmd/`, `cli/internal/tui/` | Section header pattern: `▶ HEADER` + muted subtitle + blank + content. Use ✓/✗/●/○/→/▶ glyphs. Spinner is the 10-frame Braille set. |
| Error / failure message | `cli/internal/tui/` + §"Voice" above | Always: 1-line problem + bullets of context + `→ next:` block of fixes. |
| Setup wizard | `installer/src/` | The browser install wizard — same tokens, glass cards, segmented controls. |
| Logo / favicon | `website/public/favicon.svg` (only asset that exists; see README §8) | Voxel cloud on cyan plate. Don't recolor the plate. |
| README / GitHub social | favicon + `colors_and_type.css` | Dark dominant. Single GIF/screenshot of dashboard, no animated headers. |

## Generation checklist (run mentally before emitting)
- [ ] Imported `colors_and_type.css`. Used Inter / Inter Tight / JetBrains Mono.
- [ ] One saturated accent (cyan-9). Violet only as category color.
- [ ] All status indicators use **dot + label**, not color alone.
- [ ] Dark mode by default. If light, used Sand neutrals (warm, not gray).
- [ ] Used Lucide icons, stroke-width 2, no fills.
- [ ] No emoji in product UI.
- [ ] Borders + glass for surfaces. Glow-shadow only on CTA buttons / dialog
      overlays. No drop-shadows on glass cards in dark mode.
- [ ] Voice is calm, technical, second-person (marketing) / imperative
      (dashboard) / declarative (docs).
- [ ] Failure copy names the fix, not just the symptom.
- [ ] Honored `prefers-reduced-motion`.
- [ ] No filler content.

## Anti-patterns — refuse to ship these
- Two saturated accents on the same screen (cyan + violet as both CTAs).
- Status conveyed by color *alone* — fails colorblind users.
- Drop shadows on glass cards in dark mode — looks pasted.
- Charcoal cards (`#1a1a2e` filled) instead of glass over the ambient base.
- Spring or bounce animations.
- Cartoon mascots, isometric character illustrations, gaming clichés.
- Marketing copy promising features that aren't documented.
- Title-cased dashboard headings (*"Start Instance"*).
- Emoji in dashboard, CLI, or docs.
- Free-form purple-blue gradients on section dividers.

## File index
- `README.md` — full design-system documentation; canonical voice & visual rules.
- `SKILL.md` — this file.
- `colors_and_type.css` — tokens + utility classes for both themes (the canonical
  values; surfaces reimplement them, see Caveats).
- `tokens.json` — machine-readable mirror of the tokens.

The live surfaces are the real reference for components — `dashboard/`,
`website/`, `cli/`, `installer/` (see Surfaces above).

## Caveats
- This folder is a **reference spec, not a build dependency** — no surface
  imports the token files at build time. Each reimplements them (Tailwind v4 in
  `dashboard/` + `website/`, ANSI in `cli/`) and treats this folder as canonical.
  Reconcile the surface to this folder when they drift.
- The voxel-cloud mark is **proposed, not produced** — only
  `website/public/favicon.svg` exists today (README §8).
- Fonts (Inter / Inter Tight / JetBrains Mono) should be self-hosted per surface
  for production rather than loaded from a CDN.
