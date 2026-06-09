# Design system

The canonical design system — voice, visual foundations, tokens, component
vocabulary, CLI glyphs, and logo treatment — lives at the repo root in
[`design-system/`](../../design-system/):

- [`design-system/README.md`](../../design-system/README.md) — the full spec.
- [`design-system/SKILL.md`](../../design-system/SKILL.md) — agent manifest for
  generating on-brand artifacts.
- [`design-system/colors_and_type.css`](../../design-system/colors_and_type.css)
  / [`tokens.json`](../../design-system/tokens.json) — the tokens each surface
  mirrors.

This file used to be a verbatim copy of that README; it's a pointer now so there
is a single source. The `design-system/` folder is a **reference spec, not a
build dependency** — the dashboard, website, CLI, and installer each implement
the tokens in their own stack and treat it as canonical.

For the website-rebuild plan specifically, see
[`northstar-plan.md`](./northstar-plan.md) (the standalone `WEBSITE_PLAN.md`
folded into it during the docs cleanup).
