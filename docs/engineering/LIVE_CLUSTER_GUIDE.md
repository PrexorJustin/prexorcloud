# Live-Cluster Verification Guide

Everything in the codebase is engineering-complete. What is left is work that
**cannot be done from a code session** — it needs a running controller +
daemon, a Minecraft client, a terminal, or a browser. This guide is the
operator runbook for those steps.

It covers three tracks that all share the same prerequisite (a live cluster):

1. **cli-redesign verification matrix** — `cli-redesign-plan.md` §Verification.
2. **VS Code extension MVP** — `dashboard/packages/vscode-extension/`.
3. **Website launch assets + cutover** — `WEBSITE_PLAN.md` Phases 10–11.

---

## 0. Bring up a cluster

The repo's `docker-compose.yml` is the fastest path to a full stack — Mongo,
Redis, controller, daemon, and dashboard:

```bash
docker compose up -d --build
# controller REST  → http://localhost:8080
# controller gRPC  → localhost:9090
# dashboard        → http://localhost:3000
```

Wait for the daemon to report `READY`, then build the CLI and log in:

```bash
cd cli && go build -o prexorctl . && cd -
./cli/prexorctl login --controller http://localhost:8080
# default credentials + first-run setup: see
# docs/public/en/getting-started/installation.md
```

Seed a group so the views and screenshots have something to show
(from `docs/public/en/getting-started/quickstart.md`):

```bash
./cli/prexorctl group create lobby --platform paper --version 1.21.4 \
    --min 1 --max 3 --port-range 25600-25699 --memory 1024
```

Within ~5s the scheduler places `lobby-1`. Confirm with
`prexorctl instance list` and the dashboard's Instances page.

> A reusable, deterministic seed (Mongo snapshot) for screenshot regression
> is tracked as `tools/seed-demo-cluster.sh` in `WEBSITE_PLAN.md` risk #5 —
> not yet written; the manual steps above are the interim path.

---

## 1. cli-redesign verification matrix

Source of truth: `docs/engineering/cli-redesign-plan.md` §Verification. The
code (foundation + all 7 scenes) shipped in `97aea6a..2a5c7b4`; this matrix is
the QA gate that needs a real controller.

### 1.1 Visual diff against the prototype

Run each scene against the live controller and eyeball the output against the
design-pass mockup — column widths, copy strings, glyph choices. Treat the
mockup as final and matched-exactly.

| Scene | Command |
|---|---|
| status | `prexorctl status` |
| group list | `prexorctl group list` |
| group info | `prexorctl group info lobby` |
| instance console | `prexorctl instance console lobby-1` |
| logs follow | `prexorctl logs --follow` |
| deploy | `prexorctl deploy lobby` (cancel at the `[y/N]` confirm) |
| setup | `prexorctl setup` (against a fresh config dir) |

### 1.2 `--json` regression

For every redesigned command, the machine-readable output must be untouched:

```bash
for c in "status" "group list" "instance list"; do
  ./cli/prexorctl $c --json > /tmp/after-$(echo $c | tr ' ' -).json
done
# diff each against the same capture from a pre-2a5c7b4 build — must be byte-identical
```

### 1.3 Color-profile matrix

Run each of the 7 scenes under every profile below. No crashes; output is
degraded but legible in each:

```bash
prexorctl status                          # truecolor terminal
TERM=xterm-256color prexorctl status      # 256-color
NO_COLOR=1 prexorctl status               # no color
prexorctl status --ascii                  # ASCII glyph fallback
prexorctl status | cat                    # non-TTY / piped
```

### 1.4 End-to-end smoke

One pass exercising all seven scenes on the real controller:

```
prexorctl setup → status → group list → group info lobby
  → instance console lobby-1   (type `list`, detach with Ctrl-Q)
  → logs --follow              (Ctrl-C to exit)
  → deploy lobby               (dry-run / cancel at confirm)
```

> `go test ./cli/...` (267 tests) already passes in CI — that part of
> §Verification needs no cluster.

---

## 2. VS Code extension MVP

Source: `dashboard/packages/vscode-extension/`. Bundles clean (`tsc --noEmit`
+ esbuild); this is the interactive smoke test against the live controller
from §0.

### 2.1 Launch the Extension Development Host

```bash
cd dashboard && pnpm install
pnpm --filter @prexorcloud/vscode-extension compile
code dashboard/packages/vscode-extension
```

Press **F5** — the bundled `.vscode/launch.json` builds and opens an Extension
Development Host window with the extension loaded.

### 2.2 Feature checklist

| Feature | Steps | Expected |
|---|---|---|
| **Connect** | Command Palette → `PrexorCloud: Connect to Controller`. Enter `http://localhost:8080` + admin credentials. | Info toast "Connected to …". URL lands in settings; token is in SecretStorage (survives reload). |
| **Browse instances** | Open the **PrexorCloud** activity-bar view. | `lobby` group expands to `lobby-1` with live state · node, players in the tooltip. The title-bar refresh re-fetches. |
| **Tail logs** | Click `lobby-1` (or the inline output icon). | A `PrexorCloud: lobby-1` Output channel opens — scrollback first, then live console lines. Send a server command from the controller and watch it appear. |
| **Edit templates inline** | Open the **Templates** view → expand a template → click a file. | File opens in a normal editor tab. Edit and **Save** → change persists; re-open confirms. Opening a binary file surfaces a clean "cannot be edited as text" error. |
| **Disconnect** | `PrexorCloud: Disconnect`. | Token cleared; views fall back to the "Not connected" welcome. |

### 2.3 Known MVP gaps (not bugs)

File create/rename/delete, multi-context switching, and instance lifecycle
actions are intentionally out of scope — see the extension `README.md`.

### 2.4 Packaging (optional)

To produce an installable artifact: `npx @vscode/vsce package` from the
extension directory. Not yet wired into release CI.

---

## 3. Website launch assets + cutover (WEBSITE_PLAN Phases 10–11)

The site itself is **engineering-complete**: `pnpm --filter website build` →
270 pages, `astro check` → 0 errors, all 3 launch blog posts, changelog,
404, `robots.txt`, OG-image route, and sitemap are in the tree. What remains
needs the live cluster from §0, design tooling, or production accounts.

### 3.1 Phase 10 — assets that need the live cluster

| Asset | How |
|---|---|
| Dashboard screenshots (12–15, PNG @2x) | Seed the cluster (§0), open `http://localhost:3000`, capture each surface. |
| asciinema casts (8) | `asciinema rec` real `prexorctl` sessions against the cluster — pair with the CLI reference pages. |
| Component illustrations (8 SVG) | Design-tool / AI-generated per the design-system style. Launch may ship placeholder gradients (WEBSITE_PLAN risk #6) — do **not** gate launch on these. |
| Architecture diagrams (6) | Several auto-generatable from existing Mermaid in `docs/engineering/`; the rest hand-drawn. |

### 3.2 Phase 11 — pre-launch checklist (human/ops)

Run against the final preview deploy. The full list is in `WEBSITE_PLAN.md`
§"Phase 11"; the items a code session cannot do:

- [ ] Lighthouse ≥ 95 (landing) / ≥ 90 (docs) on all four metrics.
- [ ] WCAG AA — axe-core CI green + manual keyboard pass.
- [ ] Cross-browser smoke (Chrome / Firefox / Safari / Edge).
- [ ] Mobile responsive at 320 / 375 / 768 / 1024 / 1440.
- [ ] Pagefind top-hits sanity check for the 10 most-searched terms.
- [ ] OG image share-preview check on Twitter / Discord / LinkedIn.
- [ ] Cloudflare Web Analytics live.
- [ ] Algolia DocSearch application submitted (non-blocking).

### 3.3 Launch sequence (owner-driven)

1. Final preview deploy → smoke test on the `*.pages.dev` URL.
2. DNS apex `prexor.cloud` cuts to CF Pages; `www` → 301 to apex.
3. GitHub repo description + topics updated.
4. Announcements: GitHub release (v1.0 tag) → r/admincraft → Show HN →
   Twitter/X → Discord → `awesome-minecraft-server` lists.
5. Monitor analytics for the first 48h.

---

## Status summary

| Track | Code/content | Needs a live cluster / human |
|---|---|---|
| cli-redesign | ✅ shipped (`97aea6a..2a5c7b4`) | §1 verification matrix |
| VS Code extension | ✅ shipped (`5a498e0`) | §2 interactive smoke + optional VSIX |
| Website | ✅ engineering-complete | §3 asset capture + Phase 11 launch ops |

When a track's section here is fully checked off against a real cluster, it
is done in every sense — there is no remaining code work behind any of them.
