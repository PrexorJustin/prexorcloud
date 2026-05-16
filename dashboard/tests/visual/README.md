# Visual baselines

Playwright captures full-page screenshots of every key dashboard page using
the **dev-mock layer** — the sentinel auth token + canned data — so no
controller has to be running.

## Run

```bash
pnpm test:visual          # compare against baselines (fails on diff)
pnpm test:visual:update   # rewrite baselines after intentional UI changes
```

Playwright will boot `pnpm dev` automatically (or reuse one already running on
port 3000). On first run, install the browser binary:

```bash
pnpm exec playwright install chromium
```

## What's captured

The list lives in `pages.spec.ts`. Each entry produces one `<name>.png` baseline
under `pages.spec.ts-snapshots/`. Currently:
overview · instances list + detail · nodes list + detail · groups list +
detail · networks · templates list · modules · catalog list + detail ·
crashes · audit · settings · profile · login.

Animations and transitions are forced to zero before each screenshot so glass
hover states and ambient-glow drifts don't introduce flakiness.

## Adding a page

1. Append `{ name, path }` to `PAGES` in `pages.spec.ts`.
2. Make sure the path's responses are covered in `app/lib/dev-mock/install.ts`.
3. `pnpm test:visual:update` to generate the baseline.
4. Commit the new `.png`.

## CI

The workflow runs in `dark` mode on Chromium at 1440×900. Threshold settings
in `playwright.config.ts` allow ~1% pixel drift to absorb subpixel
antialiasing differences across machines without missing real regressions.
