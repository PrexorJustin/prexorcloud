# website/ — public site & docs

Astro 5 + Starlight static site for prexorcloud.dev. Public-facing documentation, OpenAPI reference (via `starlight-openapi` + Scalar viewer), CLI/gRPC docs, architecture diagrams.

## Layout

- `src/content/docs/` — Markdown content (en + de)
- `src/components/` — Astro/Vue island components
- `src/scripts/mermaid.ts` — diagram theming
- `public/` — static assets (favicon, images)
- `scripts/sync-openapi.mjs` — pulls `docs/openapi.json` into the Starlight tree on every dev/build

## Common commands

```bash
pnpm install
pnpm dev                 # astro dev on :4321
pnpm build               # production static site
pnpm preview             # preview built site
```

OpenAPI spec is sourced from `../docs/openapi.json` (controller-generated). Re-run `pnpm dev` after the controller regenerates it.
