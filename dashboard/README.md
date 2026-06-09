<p align="center">
  <strong>dashboard/</strong><br>
  <em>The operator control panel — Nuxt 4 SPA over the controller's REST + SSE API.</em>
</p>

<p align="center">
  <a href="https://github.com/prexorjustin/prexorcloud/actions"><img src="https://img.shields.io/github/actions/workflow/status/prexorjustin/prexorcloud/ci.yml?branch=main&style=flat-square" alt="CI"></a>
  <a href="../LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License"></a>
  <a href="https://prexor.cloud"><img src="https://img.shields.io/badge/docs-prexor.cloud-0c8aa8?style=flat-square" alt="Docs"></a>
</p>

---

## What is this?

The web UI operators use to run PrexorCloud: groups, instances, deployments, networks, players, modules, users, audit, and the cluster view. Vue 3 + Nuxt 4 + Pinia + Tailwind CSS 4 + shadcn-vue, talking to the controller over the auto-generated REST SDK and a live SSE event stream, themed with the Reef design system.

## Quickstart

```bash
cd dashboard
pnpm install
pnpm dev          # Nuxt dev server on :3000
```

In dev, requests to `/api/v1` and `/metrics` proxy to a controller on `localhost:8080` (see `nitro.devProxy` in `nuxt.config.ts`). To work without a backend, build the bundled dev mocks: `VITE_DEV_MOCK=1 pnpm build`.

## Layout

- `app/` — the Nuxt application (pages, components, stores, composables)
- `packages/api-sdk/` — REST client auto-generated from `../docs/openapi.json` (consumed by `app/`)
- `packages/module-sdk/` — `@prexorcloud/module-sdk`, published to npm, used by module frontends
- `packages/vscode-extension/` — VS Code extension for module authoring (see its [README](packages/vscode-extension/README.md))
- `i18n/locales/` — translation catalogs (`en.json`, `de.json`); parity is a hard CI gate
- `scripts/` — `a11y-lint.mjs`, `axe-authed.mjs`, `lint-voice.sh`
- `tests/` — Vitest unit tests + Playwright visual tests

## Usage

```bash
pnpm test                # vitest
pnpm test:coverage       # vitest with coverage
pnpm build               # sdk:check + nuxt build (production)
pnpm story               # Histoire component stories
pnpm lint                # eslint
pnpm lint:voice          # sentence-case + no-emoji guard (hard gate)
pnpm a11y:check          # static a11y lint (hard gate)
pnpm i18n:check          # en↔de locale parity (hard gate)
```

The api-sdk is generated from the controller's OpenAPI spec; after the controller regenerates `../docs/openapi.json`, run `pnpm sdk:check` to validate the client against it.

## Links

- [Design system](../design-system/README.md)
- [Module SDK reference](https://prexor.cloud/reference/module-sdk/)
- [Contributing](../CONTRIBUTING.md)

## License

Apache 2.0 — see [LICENSE](../LICENSE).
