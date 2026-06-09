<p align="center">
  <strong>website/</strong><br>
  <em>The public site and docs for prexor.cloud — Astro Starlight.</em>
</p>

<p align="center">
  <a href="https://github.com/prexorjustin/prexorcloud/actions/workflows/website.yml"><img src="https://img.shields.io/github/actions/workflow/status/prexorjustin/prexorcloud/website.yml?branch=main&style=flat-square" alt="Website CI"></a>
  <a href="../LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License"></a>
  <a href="https://prexor.cloud"><img src="https://img.shields.io/badge/site-prexor.cloud-0c8aa8?style=flat-square" alt="Site"></a>
</p>

---

## What is this?

The Astro 5 + Starlight static site behind [prexor.cloud](https://prexor.cloud): the marketing pages, blog, and the full documentation tree, plus the OpenAPI reference (`starlight-openapi` + Scalar viewer) and generated CLI/gRPC pages. The docs content itself is **not** stored here — Starlight sources the `docs` collection from `../docs/public/en/` at the repo root (see `src/content.config.ts`), so docs and the site that renders them ship in one PR.

## Quickstart

```bash
cd website
pnpm install
pnpm dev          # astro dev on :4321
```

## Layout

- `src/content.config.ts` — points the Starlight `docs` collection at `../docs/public/en/`
- `src/components/` — Astro + Vue island components (`components/docs/` for doc widgets)
- `src/pages/` — marketing pages, `blog/`, and OG-image routes (`pages/og/`)
- `src/scripts/`, `src/styles/`, `src/assets/`, `src/lib/` — diagram theming, styles, assets, helpers
- `public/` — static assets (favicon, images)
- `scripts/` — prebuild generators (see below)

## Usage

```bash
pnpm build          # prebuild generators, then astro build (production static site)
pnpm preview        # serve the built site
pnpm exec astro check
```

`pnpm build` runs three prebuild generators first (the `prebuild` script):

- `scripts/sync-openapi.mjs` — pulls `../docs/openapi.json` into the Starlight tree
- `scripts/gen-mermaid-theme.mjs` — emits the Mermaid palette from the design-system tokens
- `scripts/gen-starlight-theme.mjs` — emits `starlight-theme.generated.css` from the same tokens

The theme generators run under a freshness guard: CI fails if the generated files drift from `../design-system/dist/tokens.json`. Re-run `pnpm build` after the controller regenerates the OpenAPI spec or the design tokens change.

## Links

- [docs/ tree](../docs/README.md)
- [Design system](../design-system/README.md)
- [Contributing](../CONTRIBUTING.md)

## License

Apache 2.0 — see [LICENSE](../LICENSE).
