# dashboard/ — admin UI

Vue 3 + Nuxt 4 + Pinia + TailwindCSS 4. Operator-facing control panel for PrexorCloud — groups/instances/deployments/audit/users/networks/modules.

## Layout

- `app/` — Nuxt application
- `packages/api-sdk/` — auto-generated REST client from OpenAPI (consumed by app)
- `packages/module-sdk/` — `@prexorcloud/module-sdk` published to npm; used by module-frontends
- `packages/vscode-extension/` — VS Code extension for module authoring
- `i18n/` — translation files (en, de)
- `scripts/` — sync-from-openapi, design-token-sync
- `tests/` — Vitest + Playwright

## Common commands

```bash
pnpm install
pnpm dev                 # nuxt dev server on :3000
pnpm test                # vitest
pnpm build               # static build for production
pnpm storybook           # Histoire stories
```

Auth in dev: see `app/server/api/` mocks or point at a local controller (`PREXORCLOUD_CONTROLLER_URL=http://localhost:8080`).
