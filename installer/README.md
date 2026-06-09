<p align="center">
  <strong>installer/</strong><br>
  <em>The browser setup wizard — Vite + Vue, embedded into the prexorctl binary.</em>
</p>

<p align="center">
  <a href="https://github.com/prexorjustin/prexorcloud/actions"><img src="https://img.shields.io/github/actions/workflow/status/prexorjustin/prexorcloud/ci.yml?branch=main&style=flat-square" alt="CI"></a>
  <a href="../LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License"></a>
  <a href="https://prexor.cloud"><img src="https://img.shields.io/badge/docs-prexor.cloud-0c8aa8?style=flat-square" alt="Docs"></a>
</p>

---

## What is this?

The browser setup wizard an operator sees when they run `prexorctl setup` — the
screens that collect controller/daemon config, generate secrets, and write the
YAML. Vite + Vue 3 + Pinia, built as a single inlined `index.html` that's copied
into `cli/internal/setupweb/static/` and embedded into the `prexorctl` binary via
Go's `//go:embed`.

## For operators

You don't build this. Install `prexorctl`, then run:

```bash
prexorctl setup
```

That opens the wizard in your browser and walks you through mode → essentials →
security → review, writing `controller.yml` and `daemon.yml` at the end. The full
walkthrough — including the screens and what each field does — is in the docs:
[getting-started/wizard](../docs/public/en/getting-started/wizard.md) and
[getting-started/installation](../docs/public/en/getting-started/installation.md).

## For developers

Everything below is for contributors hacking on the wizard itself.

It replaces the hand-edited 2058-line single-file wizard that previously lived
under `cli/internal/setupweb/static/index.html`. The Go server is unchanged — it
still embeds one HTML file from `static/*`.

## Dev workflow

```sh
pnpm -C installer install
pnpm -C installer dev          # Vite dev server, hot reload
pnpm -C installer typecheck    # vue-tsc --noEmit
pnpm -C installer test         # vitest run
pnpm -C installer build        # produces installer/dist/index.html
```

To rebuild and re-embed in one shot from the repo root:

```sh
bash scripts/build-installer.sh
```

That script runs `pnpm install --frozen-lockfile && pnpm build` inside
`installer/`, then copies `installer/dist/index.html` over
`cli/internal/setupweb/static/index.html`. Re-running it overwrites the
embedded file. The repo still compiles with plain `go build` even when the
JS toolchain isn't installed locally — the embedded HTML stays committed.

### Embedded file is a generated artifact

`cli/internal/setupweb/static/index.html` is treated like
`docs/openapi.json`: tracked in git so `go build` works on a fresh clone
without pnpm, but flagged `linguist-generated=true` in `.gitattributes` so
GitHub's diff view collapses it and the language-stats counter ignores it.

The CI `drift-check` job rebuilds the installer on every push and fails
with a clear error if the committed embed file differs from a fresh
build. So the workflow when touching `installer/` is:

```sh
# edit installer/src/...
pnpm -C installer test
bash scripts/build-installer.sh   # regenerates the embed
git add installer/ cli/internal/setupweb/static/index.html
git commit
```

Forgetting the rebuild surfaces in CI as:

```
::error::Embedded wizard is stale. Run `bash scripts/build-installer.sh`
from the repo root and commit cli/internal/setupweb/static/index.html.
```

## Architecture

```
src/
├─ main.ts              createApp + Pinia, imports tokens + wizard CSS
├─ App.vue              shell, brand bar, stepper, screen router
├─ screens/
│  └─ Mode.vue          step 1 — 5-card mode picker (ported)
├─ stores/
│  └─ wizard.ts         Pinia store, full wizard state shape (typed)
├─ lib/
│  ├─ api.ts            apiFetch + SETUP_TOKEN bearer header
│  └─ generators.ts     genUuid / genSecret / genPassword
└─ styles/
   ├─ tokens.css        canonical Reef tokens (Inter / Inter Tight / Instrument Serif / JetBrains Mono)
   └─ wizard.css        wizard-specific classes ported from the legacy <style>
```

The dev server reads `index.html` at the project root. Production builds
inline every asset into a single `dist/index.html` via
`vite-plugin-singlefile`, which is the form the Go server expects.

## Migration status

| Screen        | Function in legacy `index.html`     | Status |
|---------------|-------------------------------------|--------|
| Mode          | `modeScreen()`         L1140-1181   | ✅ ported (`screens/Mode.vue`) |
| Essentials    | `essentialsScreen()`   L1183-1206   | ✅ ported (`screens/Essentials.vue`) |
| Security      | `securityScreen()`     L1394-1583   | ✅ ported (`screens/Security.vue`) |
| Review        | `reviewScreen()`       L1586-1638   | ✅ ported (`screens/Review.vue`) |
| CLI Login     | `cliLoginScreen()`     L1683-1755   | ✅ ported (`screens/CliLogin.vue`) |

Supporting helpers:

| Helper             | Source                                | Module |
|--------------------|---------------------------------------|--------|
| YAML emitters      | `controllerYaml` / `daemonYaml` / …   | `lib/yaml.ts` |
| YAML highlighter   | `highlightYaml` / `escapeHtml`        | `lib/highlight.ts` |
| Validators         | `isPort` / `isMongoOrRedis` / …       | `lib/validators.ts` |
| Generators         | `cryptoUuid` / `genSecret` / …        | `lib/generators.ts` |
| Setup-token API    | `apiHeaders` + bearer header          | `lib/api.ts` |

The Pinia store (`stores/wizard.ts`) holds the full ~100-field state shape
and the install / cli-login actions.

## CI

`.github/workflows/ci.yml`:

- **`installer` job** — runs `pnpm install --frozen-lockfile`, `pnpm
  typecheck`, `pnpm test`, `pnpm build` on every push / PR. Mirrors the
  `dashboard` job's structure.
- **`drift-check` job** — rebuilds the installer and fails if the
  committed `cli/internal/setupweb/static/index.html` drifted. Same
  pattern the repo already uses for CLI / gRPC / benchmarks / openapi
  freshness.

## Links

- [Wizard walkthrough](../docs/public/en/getting-started/wizard.md) — the operator-facing screen-by-screen guide
- [`scripts/`](../scripts/README.md) — `build-installer.sh` and the other codegen helpers
- [Contributing](../CONTRIBUTING.md) — repo-wide build and PR conventions

## License

Apache 2.0 — see [LICENSE](../LICENSE).
