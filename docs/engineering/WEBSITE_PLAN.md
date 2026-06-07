# PrexorCloud Website — Top-Notch Rewrite Plan

> Status: **Engineering complete (shipped in v1.0, 2026-05-05).**
> Last updated: 2026-05-09.
> Domain: **prexor.cloud** (single-domain).
> Stack: Astro Starlight + Astro Vue + starlight-openapi + Scalar + Pagefind, hosted on Cloudflare Pages.

This document is the working plan for replacing the current `website/` (Nuxt 4 + `shadcn-docs-nuxt`, with content stuck on the pre-Layer-3 backend) with a top-notch documentation + marketing site that ships at v1.0 of PrexorCloud. It replaces no individual layer of `API_OVERHAUL.md` — it's a parallel product track.

---

## Context — what we're replacing and why

The existing `website/` is a community-theme starter (`ZTL-UwU/shadcn-docs-nuxt`) with the README still showing the boilerplate. The framework is current (Nuxt 4.4.2, Vue 3.5.30, Vite 7.3.1, TS 5.9.3) but the **content** is heavily out of sync:

- 28+ references to SQLite (backend has been Mongo + optional Redis-HA for several layers).
- `@CloudModule` / `CloudModuleBase` / `PlatformModuleContext` everywhere — pre-Layer-3 module API that no longer exists.
- No coverage of Layer 5 (player-journey + webhook-alerts as standalone modules), Layer 7 (daemon-side modules), Layer 8 (`useCapability` SSE), Layer 9 (`prexorctl plugin new`).
- Hand-written API reference (`5.api/*.md`) duplicates `docs/openapi.yaml` (151 endpoints) — two sources, drift guaranteed.

The framework choice is also questionable: `shadcn-docs-nuxt` is single-maintainer (bus factor 1), Nuxt is overkill for static MD, and marketing landing + docs are coupled in one app where they don't have to be.

Decision: **content-rewrite is mandatory; framework migration is justified once we're rewriting anyway**. Astro Starlight is the better fit for a docs-heavy OSS project. Vue components for the landing page survive via Astro's Vue integration.

---

## Decisions locked in

| Axis | Decision | Notes |
|---|---|---|
| Framework | **Astro 5 + Starlight 0.30+** | Docs-purpose-built, Pagefind built-in, MDX support, Astro Islands for Vue components |
| Landing | **Astro pages + Vue components via `@astrojs/vue`** | Existing Hero / Bento / Showcase / CTA Vue components migrate as Astro Islands |
| Audience priority | Server-Admins **+** Module/Plugin-Devs **+** OSS-Contributors | Ton: serious infra tool. No hosting-provider tier (out of scope). |
| Vertriebsmodell | Pure OSS, self-host only | No pricing, no signup, GitHub stars + downloads as success metric |
| Domain | `prexor.cloud` single-domain | `/`, `/docs/`, `/api/`, `/blog/`, `/playground` as paths |
| Hosting | Cloudflare Pages | Free, edge CDN, preview deploys per PR, Web Analytics built-in |
| OpenAPI strategy | **starlight-openapi + Scalar /playground** | starlight-openapi generates one Starlight page per endpoint (suchbar via Pagefind, eigene URLs); Scalar gives `/playground` for "Try it" with code samples |
| OpenAPI source | `docs/openapi.yaml` (existing 10 246-line, hand-written) | Long-term: switch to javalin-openapi auto-generation (Phase ≥ 11, post-launch) |
| gRPC docs | `protoc-gen-doc` → `/docs/internals/protocol/*` | Source-of-truth = `.proto` files. Framed as "internal cluster protocol, not public API" |
| Versioning | **Single-current at v1.0**, retrofittable | No version segments in internal links → `starlight-versions` plug-in clean later |
| Search | **Pagefind built-in at launch**, Algolia DocSearch as parallel application | Algolia approval takes 1-2 weeks, swap when approved |
| i18n | **i18n-ready architecture, Launch only EN** | Content collections structured `docs/public/en/...` even though only en exists |
| Comparisons | CloudNet 4 + SimpleCloud V2 only at v1.0 | Pterodactyl is a different category (panel vs orchestrator) — call out the framing, no full vs-page |
| Live demo | **No** — Screenshots + asciinema casts only | Avoids 24/7 demo cluster ops cost |
| Branding | Design-system parallel track | Plan references `design-system.md` as a phase-1 dependency; tokens land before Phase 2 |
| Maintenance model | Hybrid: MD by hand + auto for OpenAPI/gRPC | Edit-on-GitHub: yes; Giscus / comments: no; manual `CHANGELOG.md` (keep-a-changelog) |
| Analytics | Cloudflare Web Analytics | Built-in with CF Pages, no cookie banner, no script tag needed |
| Surface stability | All Polishing-Phasen are internal | No public surface changes pending → safe to write reference docs now |

---

## Repository layout

The website code lives in the existing `prexorcloud` monorepo. **No separate repo.** Content source lives in `docs/public/` so a single PR can change code + docs in one review.

```
prexorcloud/
├── docs/
│   ├── adr/                        ← internal Architecture Decision Records (private to engineering)
│   ├── engineering/                ← internal plan docs (API_OVERHAUL.md, this file, …)
│   ├── public/                     ← Source-of-Truth for the published website
│   │   ├── en/
│   │   │   ├── index.mdx           ← Marketing landing (Astro page actually, see below)
│   │   │   ├── getting-started/
│   │   │   ├── concepts/
│   │   │   ├── guides/
│   │   │   ├── reference/
│   │   │   │   ├── rest-api/       ← starlight-openapi-generated
│   │   │   │   ├── cli/            ← cobra --help dumps + curated
│   │   │   │   ├── module-sdk/     ← Javadoc-extracted + curated
│   │   │   │   └── module-yaml/
│   │   │   ├── operations/
│   │   │   ├── recipes/
│   │   │   ├── compare/
│   │   │   ├── internals/
│   │   │   │   ├── protocol/       ← protoc-gen-doc-generated
│   │   │   │   └── architecture/
│   │   │   ├── blog/               ← Astro Content collection
│   │   │   └── changelog.md        ← keep-a-changelog
│   ├── openapi.yaml                ← consumed by starlight-openapi + Scalar
│   └── design-system.md            ← consumed by Phase 1 (output of the design prompt)
├── website/                        ← REPLACED — Astro Starlight project
│   ├── astro.config.mjs
│   ├── src/
│   │   ├── content/config.ts       ← Astro Content collections pointing at docs/public/en
│   │   ├── components/
│   │   │   ├── landing/            ← migrated Vue components (Hero, Bento, Showcase, CTA)
│   │   │   ├── docs/               ← Starlight overrides (custom Card, Callout, Terminal block)
│   │   │   ├── og/                 ← OG-image template (satori)
│   │   │   └── playground.astro    ← Scalar embed
│   │   ├── pages/
│   │   │   ├── index.astro         ← marketing landing
│   │   │   ├── playground.astro    ← Scalar API playground
│   │   │   └── og/[...slug].png.ts ← dynamic OG-image generator
│   │   ├── styles/
│   │   │   ├── tokens.css          ← CSS variables from design-system.md
│   │   │   └── starlight.css       ← Starlight theme overrides
│   │   ├── assets/
│   │   └── env.d.ts
│   ├── public/
│   │   ├── logo.svg / logo-dark.svg
│   │   ├── favicon.ico
│   │   ├── og/default.png
│   │   ├── robots.txt
│   │   └── sitemap will be auto-generated
│   ├── package.json
│   └── tsconfig.json
├── tools/
│   ├── gen-cli-docs.ts             ← reads `prexorctl ... --help` and emits MD
│   └── gen-grpc-docs.sh            ← runs `buf generate` with protoc-gen-doc plugin
└── .github/workflows/
    └── website.yml                 ← build + Cloudflare Pages deploy
```

The current `website/` directory gets deleted in Phase 1 after the new project is wired and producing the same landing-page content. No backwards-compat preservation.

---

## Tech-stack rationale (one paragraph each)

- **Astro 5 + Starlight 0.30+** — Astro renders static HTML by default and only ships JS where Islands need it. Starlight is the official Astro docs theme (used by Astro itself, Cloudflare, Bun, htmx). Pagefind is built in, no Algolia required at launch. MDX 4 support means the existing MDC components (`::card-group`, `::alert`) get straight-forward replacements via Starlight's component slots and custom MDX components.
- **`@astrojs/vue`** — lets the existing Vue 3.5 landing components (Hero with terminal animation, Bento grid, Showcase, CTA) be reused as Astro Islands without a React rewrite. Hydration cost is per-component; landing page picks `client:visible` for animations, `client:idle` for everything else.
- **`starlight-openapi`** — plugin that walks `docs/openapi.yaml` at build time and emits one Starlight page per endpoint (request/response, schemas, examples). Pages get real URLs, Pagefind-indexed, sidebar-integrated. Single source of truth.
- **Scalar** — separate `/playground` page embedding `<api-reference data-url="/openapi.yaml">`. Provides interactive "Try it" + multi-language code samples. Doesn't replace starlight-openapi — complements it.
- **`@buf/protoc-gen-doc`** — generates Markdown from `cloud-protocol/src/main/proto/*.proto`. Run in CI before Astro build. Output dropped into `docs/public/en/internals/protocol/*.md`. gRPC contract drift becomes impossible by construction.
- **Pagefind** — full-text search, runs entirely in-browser, indexes built statically. Free, no third-party dependency, perfect for ≤500 pages.
- **Cloudflare Pages** — zero-config Astro deploys, automatic preview URLs per PR, Web Analytics out-of-the-box (no script tag needed), free for OSS.
- **Tailwind 4 (alpha-stable) via Astro's official integration** — design tokens from `design-system.md` map to CSS variables, shadcn-compatible naming so the dashboard (existing Nuxt SPA) and the website share one source of truth.
- **Lucide icons** — current site already uses `lucide:*`, dashboard uses Lucide, CLI can produce Unicode glyphs that semantically match. Single icon family across surfaces.

---

## Phased rollout

> **Status (2026-05-14): engineering-complete.** Phases 1–9 are done — 270
> pages build clean, `astro check` 0 errors, all 3 launch blog posts +
> changelog + 404 + `robots.txt` + OG-image route + sitemap in the tree.
> Phase 10's blog posts and diagrams shipped; what remains of Phase 10
> (dashboard screenshots, asciinema casts, component illustrations) and all
> of Phase 11 (Lighthouse/cross-browser passes, DNS cutover, analytics,
> announcements) needs a live cluster, design tooling, or production
> accounts — captured as an operator runbook in
> [`LIVE_CLUSTER_GUIDE.md`](./LIVE_CLUSTER_GUIDE.md) §3.

The plan splits into **eleven phases**. Phases 1-3 are foundation and can run in parallel with the design-system prompt. Phases 4-9 are content-heavy. Phase 10 is launch. Phase 11 is post-launch.

Effort estimates assume one engineer, full-time, and exclude design-system iteration time.

| Phase | Title | Effort |
|---|---|---|
| 1 | Foundation: Astro Starlight skeleton, repo restructure, CI/CD, design-tokens wire-in | 2-3 d |
| 2 | Marketing landing migration (Vue components → Astro Islands) | 1-2 d |
| 3 | Information architecture + sidebars + branding polish | 1 d |
| 4 | Content rewrite: Getting Started + Concepts + Architecture | 4-5 d |
| 5 | Reference: REST (starlight-openapi + Scalar), CLI, Module SDK | 3-4 d |
| 6 | Reference: gRPC internals (protoc-gen-doc) | 1 d |
| 7 | Operations + Production-Hardening pages | 2-3 d |
| 8 | Recipes + Migration-Guides | 4-5 d |
| 9 | Comparison pages (CloudNet 4, SimpleCloud V2) | 2-3 d |
| 10 | Asset production track (parallel throughout, but converges in Phase 10) — screenshots, asciinema, diagrams, OG images, blog initial posts | 3-4 d (concurrent) |
| 11 | Pre-launch polish + launch | 2-3 d |

**Total focused effort: ~25-30 working days.** With the asset track running in parallel and the design-system prompt running on day 1, calendar time is **5-6 weeks** for a single engineer.

---

### Phase 1 — Foundation

**Goal:** Empty Astro Starlight project deploys to a Cloudflare Pages preview URL with three placeholder pages. Repo restructure complete. Old `website/` deleted.

**Tasks:**

1. **Repo restructure** — `docs/` split into `adr/`, `engineering/`, `public/`. Existing `docs/API_OVERHAUL.md` moves into `engineering/`. ADRs (if any get extracted later) go into `adr/`. The `docs/openapi.yaml` stays at top of `docs/` since it's consumed by both engineering and public surfaces.
2. **New Astro project** — `npm create astro@latest -- --template starlight website-new`, then rename `website-new → website` after deleting the old Nuxt project. Initial config: dark mode primary, light mode secondary, Tailwind via `@astrojs/tailwind`, Vue via `@astrojs/vue`, MDX via `@astrojs/mdx`.
3. **Astro Content collections wired to `docs/public/en/`** — `src/content/config.ts` declares the collection pointing at the absolute path; Starlight reads from there. Symlink-free (Astro supports source paths outside the project root).
4. **Domain wiring** — Cloudflare Pages project created, DNS for `prexor.cloud` (apex) and `www.prexor.cloud` configured to CF Pages. Preview deploys for every PR get `*.prexor-cloud-website.pages.dev` URLs automatically.
5. **CI/CD** — `.github/workflows/website.yml` runs `pnpm install`, `pnpm build` from `website/`, deploys to CF Pages via the official action. Triggers on push to `main` for production, on PR for preview.
6. **Design-tokens wire-in** — once `docs/engineering/design-system.md` lands (parallel design-system track), the CSS variables go into `website/src/styles/tokens.css` and Tailwind config is adapted. Until then: a placeholder palette derived from current `#3b82f6` blue + Inter/JetBrains Mono.
7. **Three placeholder pages** — `/` (landing skeleton), `/docs/getting-started/installation` (placeholder), `/api/auth/login` (will be auto-gen'd in Phase 5). Confirms the build pipeline works end-to-end.

**Verification:**
- `pnpm --filter website build` produces a static `dist/` directory.
- CF Pages preview URL renders all three placeholder pages.
- Pagefind is enabled and indexes the placeholders.
- Edit-on-GitHub link visible on placeholder docs page.
- Lighthouse score ≥ 90 on all four metrics for the landing placeholder.

---

### Phase 2 — Marketing landing migration

**Goal:** The four existing Vue landing components (`HeroSection`, `BentoFeatures`, `ShowcaseSection`, `CtaSection`) render on the new `/` page with no behavior loss.

**Tasks:**

1. **Copy components** from old `website/app/components/landing/*.vue` into `website/src/components/landing/`. Adjust imports (Vue stays Vue, Tailwind classes stay).
2. **Replace `NuxtLink` → `<a>` or Astro's `<a href>`**. Replace `Icon name="lucide:..."` → import lucide-vue-next icons directly (Astro Islands have no global Icon component).
3. **`src/pages/index.astro`** — uses Starlight's `<StarlightPage>` slot for navigation, hosts the four Vue components as Islands with `client:visible` directive for the terminal-animation hero, `client:idle` for the rest.
4. **OG-meta + JSON-LD** — moved from the Nuxt `useSeoMeta` composable into Astro's frontmatter. JSON-LD `SoftwareApplication` schema preserved.
5. **Theme toggle** — Starlight's built-in toggle replaces the Nuxt color-mode plugin. Default mode: dark on landing, light on docs (configurable per page via frontmatter).
6. **Marketing copy refresh** — verify the existing copy ("Deploy, scale, and manage Minecraft server infrastructure with zero-trust security…") still fits Layer 7 reality. Likely no major change needed.

**Verification:**
- Hero terminal animation still cycles through `prexorctl deploy` lines.
- Bento features display with current Lucide icons.
- Theme toggle works.
- Landing Lighthouse: ≥ 95 perf, ≥ 100 a11y, ≥ 100 best-practices, ≥ 100 SEO.
- Mobile responsive (375px, 768px, 1280px breakpoints all clean).

---

### Phase 3 — Information architecture + sidebars + branding polish

**Goal:** The site has a complete navigation structure for everything that's coming, even before the content is written. Empty pages get "coming in Phase X" placeholders that the content phases fill in.

**Sidebar structure (Starlight `astro.config.mjs`):**

```
Documentation
├── Getting Started          ← Phase 4
│   ├── What is PrexorCloud?
│   ├── Installation
│   ├── Quickstart (10 min)
│   ├── Core Concepts
│   └── Your First Network
├── Concepts                 ← Phase 4
│   ├── Architecture
│   ├── Cluster Model (Controller / Daemon / Plugin)
│   ├── Groups, Instances, Templates
│   ├── Scheduling + Auto-Scaling
│   ├── Module System
│   │   ├── Platform Modules (Controller-side)
│   │   ├── Daemon Modules (Layer 7)
│   │   ├── Capability Registry
│   │   └── Module Lifecycle
│   ├── Plugin System (Path A vs Path B)
│   ├── Events + EventBus
│   ├── Security (mTLS, JWT, RBAC)
│   └── Deployments + Rolling Updates
├── Guides                   ← Phase 4 (some) + Phase 8 (rest)
│   ├── Multi-Node Setup
│   ├── Rolling Deployments
│   ├── Crash Recovery
│   ├── Custom Scaling Rules
│   ├── Backup + Restore
│   └── HA Controller (Redis)
├── Operations               ← Phase 7
│   ├── Production Checklist
│   ├── Configuration Reference
│   ├── Monitoring + Metrics
│   ├── Logs + Audit
│   ├── Backups + DR
│   ├── Upgrading
│   └── Disaster Drill
└── Recipes                  ← Phase 8
    ├── BedWars Network
    ├── Survival Server
    ├── Multi-Game Network
    ├── Reverse Proxy in Front
    ├── CI/CD Deployments
    ├── Discord Notifications
    ├── Custom Scaling Logic
    ├── Migrate from CloudNet 4
    ├── Migrate from SimpleCloud V2
    └── Migrate from Pterodactyl

Reference
├── REST API                 ← Phase 5 (auto-generated)
│   └── (151 endpoints, generated by starlight-openapi)
├── /playground              ← Phase 5 (Scalar)
├── CLI (prexorctl)          ← Phase 5
│   ├── Setup + Auth
│   ├── Cluster Commands
│   ├── Group + Instance Commands
│   ├── Template Commands
│   ├── User + Role Commands
│   ├── Module + Plugin Commands
│   └── Utilities + Scripting
├── Module SDK               ← Phase 5
│   ├── PlatformModule Interface
│   ├── DaemonModule Interface
│   ├── ModuleContext
│   ├── EventBus
│   ├── Capability API
│   ├── Storage API (Mongo + Redis)
│   ├── REST Routes (PlatformModule only)
│   └── module.yaml Manifest
└── Plugin SDK
    ├── CloudPluginContext
    ├── EventHandler
    ├── Players + Commands
    └── @CloudPlugin Annotation

Internals (smaller font, separated section)
├── Architecture Deep-Dive
├── gRPC Protocol            ← Phase 6 (protoc-gen-doc)
│   ├── DaemonService
│   ├── BootstrapService
│   └── AdminService
├── Storage Schema (Mongo + Redis Keys)
├── Cosign Signing Pipeline
└── Tech Stack

Compare                       ← Phase 9
├── vs CloudNet 4
└── vs SimpleCloud V2

Blog                          ← Phase 10 (assets/initial posts)
Changelog                     ← Phase 7
Contributing
└── Development Setup, Tech Stack, Contribution Guide
```

**Branding-polish tasks (Phase 3 specific):**

- Apply design tokens from `docs/engineering/design-system.md` (assumes parallel track delivered).
- Custom Starlight components: `<Callout>`, `<TerminalBlock>`, `<StatusBadge>`, `<Bento>` overriding defaults to match dashboard styling.
- `<Footer>` override: GitHub link, version-tag link, edit-on-GitHub, license note (MIT).
- Favicon + 32/192/512 PWA icons (assets phase produces source).
- 404 page with sensible recovery links.

**Verification:**
- Sidebar shows complete tree, broken links to placeholder pages all return 200 with "coming in Phase X" content.
- Theme tokens: switch between light/dark mode flips every component cleanly (no leftover hard-coded colors).
- Custom components render in MDX (`<Callout type="warn">`, `<TerminalBlock command="prexorctl deploy">…</TerminalBlock>`).

---

### Phase 4 — Content rewrite: Getting Started + Concepts + Architecture

**Goal:** Every concept page reflects the **current** backend (Mongo, PlatformModule, daemon-side modules, capability registry, mTLS bootstrap, useCapability dashboard, etc.). Old SQLite / @CloudModule / etc. references are gone.

**Pages (24 total):**

- **Getting Started (5 pages):**
  - What is PrexorCloud? (architecture diagram refresh — Java 25, Mongo, optional Redis-HA)
  - Installation (compose-based + bare-metal walkthrough — cosign verification flow, mTLS bootstrap)
  - Quickstart (10 min: install → join token → first daemon → first group → first instance)
  - Core Concepts (10 minutes' worth of `groups`, `instances`, `templates`, `nodes`, `daemons`, `modules`)
  - Your First Network (full lobby + one game-mode in 30 minutes)

- **Concepts (12 pages):**
  - Architecture (the diagram, components, communication flow)
  - Cluster Model (Controller / Daemon / Plugin — mTLS + gRPC + REST roles)
  - Groups, Instances, Templates (the core nouns)
  - Scheduling + Auto-Scaling (weighted-scoring, cooldowns, strategies)
  - Module System overview
    - Platform Modules — the lifted ModuleLifecycleManager state machine
    - Daemon Modules (Layer 7) — instance-lifecycle hooks, no Mongo
    - Capability Registry — provides/requires graph, dynamic handles
    - Module Lifecycle — INSTALLED → WAITING → ACTIVE → STOPPING → UNLOADED → FAILED
  - Plugin System (Path A standalone vs Path B bundled) — links to `docs/engineering/plugin-vs-module.md` content
  - Events + EventBus — `api.event.EventBus`, subscribe-registration daemon side
  - Security — mTLS handshake, JWT issuance, RBAC's 28 permissions, cosign signing
  - Deployments + Rolling Updates — maxUnavailable, plan-hash, pause/resume

- **Architecture Deep-Dive (under Internals, 4 pages):**
  - Mongo schema reference
  - Redis-HA topology + Pub/Sub bridge
  - Storage layout (controller-side `data/`, daemon-side `cache/modules/`, etc.)
  - Cosign + Rekor signed install pipeline

**Source mining:** Layer 1-9 sections in `docs/engineering/API_OVERHAUL.md` are the canonical engineering record; concept pages are the user-friendly translation of that material. Engineering → public translation rules: drop "shipped" / "TODO" / file-path detail; add "why this exists" + "how to think about it" + concrete examples.

**Verification:**
- `grep -rE "SQLite|@CloudModule|CloudModuleBase|PlatformModuleContext"` over `docs/public/` returns zero hits.
- Every page has a one-paragraph "what you'll learn" up top + a "next up" link at the bottom (Starlight pattern).
- All internal links resolve (`pnpm --filter website astro check` passes).
- Pagefind index includes every concept page and ranks "module" / "daemon" / "capability" / "scheduler" hits sensibly.

---

### Phase 5 — Reference: REST + CLI + Module SDK

**Goal:** Reference is auto-generated where the source-of-truth allows it; hand-curated where it can't be (yet).

**Tasks:**

1. **REST API via `starlight-openapi`:**
   - Plugin reads `docs/openapi.yaml` at Astro build time.
   - 151 endpoint pages emitted under `/docs/reference/rest-api/<tag>/<operationId>`.
   - Sidebar groups by tag (Auth, Overview, Nodes, Groups, Instances, Players, Templates, Crashes, Users, Tokens, Modules, System, Plugin, Events, gRPC).
   - Plugin theme overridden to match Starlight tokens (custom CSS in `src/styles/starlight-openapi.css`).

2. **`/playground` via Scalar:**
   - Single Astro page embedding `<api-reference data-url="/openapi.yaml">` web component.
   - "Try it" with API key field (admin JWT), captures and replays curl + JS + Python + Go + Java samples.
   - Linked from every starlight-openapi page sidebar ("Try this endpoint").

3. **CLI Reference auto-generated by `tools/gen-cli-docs.ts`:**
   - Script runs `prexorctl --help` recursively (every subcommand), parses cobra output, emits one MD page per command tree under `docs/public/en/reference/cli/`.
   - Hand-curated wrapping pages stay (Setup + Auth, Scripting Patterns) — the script writes only into a `_generated/` subdirectory which the wrapping pages link to.
   - Run via `pnpm gen:cli` locally, in CI as a pre-build step.

4. **Module SDK Reference (hand-curated initially):**
   - Pages for `PlatformModule`, `DaemonModule`, `ModuleContext`, `EventBus`, `CapabilityHandle`, `CapabilityRegistry` API, `PlatformModuleStorage`, `module.yaml` manifest schema.
   - Each page: signature + one paragraph + minimal example. NOT a full Javadoc dump.
   - Future Phase ≥ 11: integrate a Doclet that emits MD from Javadoc, replace the hand-curated pages with generated ones.

5. **Plugin SDK Reference (hand-curated):**
   - `CloudPluginContext`, `EventHandler`, `players()` / `commands()` / `events()` APIs, `@CloudPlugin` annotation, `@ForVersion`, `VersionDispatcher`.

**Verification:**
- `pnpm --filter website build` succeeds with `starlight-openapi` plugin enabled and 151 endpoint pages generated.
- Scalar `/playground` loads the spec and the "Try it" panel shows all 151 endpoints.
- `tools/gen-cli-docs.ts` produces non-empty MD for every cobra subcommand.
- Module SDK page count matches `cloud-api/.../api/module/platform/` interface count.

---

### Phase 6 — Reference: gRPC internals via protoc-gen-doc

**Goal:** `cloud-protocol/src/main/proto/*.proto` becomes the source-of-truth for gRPC docs. Drift impossible.

**Tasks:**

1. **Buf configuration** — `buf.yaml` + `buf.gen.yaml` at the repo root. Plugin: `protoc-gen-doc` with `markdown` template.
2. **`tools/gen-grpc-docs.sh`** — runs `buf generate`, output dropped into `docs/public/en/internals/protocol/_generated/*.md`.
3. **Wrapping pages** for `DaemonService`, `BootstrapService`, `AdminService` — each: 2-paragraph context (when used, mTLS requirement, lifecycle), then `<include>` of the generated reference.
4. **Disclaimer banner** — every gRPC page starts with the *"Internal cluster protocol. Not a public API…"* warning.
5. **CI integration** — `pnpm gen:grpc` runs before Astro build. CI has `buf` + protoc-gen-doc on the runner image (or installed by the workflow).

**Verification:**
- All proto messages from `daemon_service.proto` (incl. the Layer 7 additions: `ModuleInstall`, `ModuleEvent`, etc.) appear in the generated docs.
- Each service page shows full RPC list with request/response shapes.
- The warning banner is impossible to miss (above the fold, accent color).

---

### Phase 7 — Operations + Production-Hardening

**Goal:** Operators can confidently run a production PrexorCloud cluster from this section alone.

**Pages (8 total):**

- Production Checklist (Mongo + Redis sizing, mTLS rotation, cosign setup, backup cadence, alerting hookups, ulimits)
- Configuration Reference (every key in `controller.yml` + `daemon.yml` with type + default + impact)
- Monitoring + Metrics (Prometheus scraping, key metrics, Grafana board recommendations — note: shipped Grafana board pack was removed at v1, so this is "build your own")
- Logs + Audit (controller log-buffer + daemon log forwarding + Mongo audit log queries + retention)
- Backups + DR (Mongo dumps, restore procedure, the nightly DR drill that ships)
- Upgrading (zero-downtime rolling controller-restart, daemon-by-daemon rolling, breaking-change checklist)
- HA Setup (Redis-HA-mode, primary/standby controller, failover behavior)
- Disaster Drill (manual run-through; references the `cloud-test-harness:drDrill` task)

**Source mining:** Layer 5 (extraction shipped → docs need to mention webhooks come from `webhook-alerts` module now), Layer 8 (capability events shipped → operators see this in the dashboard but no operations impact), nightly DR drill (`project_dr_drill` memory), perf-baselines (`project_perf_baselines` memory).

**Verification:**
- A reader can go from zero-cluster to production-grade following this section linearly.
- Configuration reference covers every key in `defaults/controller.yml` + `defaults/daemon.yml` (count match).

---

### Phase 8 — Recipes + Migration-Guides

**Goal:** SEO-rich, end-to-end "do this, get that" guides. Each recipe is self-contained — reader doesn't need to jump around.

**Pages (10 total):**

- BedWars Network (multi-game with proxy + 8 lobby instances + 24 game instances)
- Survival Server (single-game, persistence, scheduled backups, mod-pack template)
- Multi-Node Cluster (3-node setup, anti-affinity, region labels, scheduler weights)
- Reverse Proxy in Front (nginx + cloudflare in front of the proxy plugin, real-IP forwarding)
- CI/CD Deployments (GitHub Actions → `prexorctl deploy --plan` → controller webhook integration)
- Discord Notifications (webhook-alerts module configuration, channel mapping, event filtering)
- Custom Scaling Rules (writing a scaling controller as a platform module)
- Migrate from CloudNet 4 (concept-mapping, template-conversion, plugin-port-checklist)
- Migrate from SimpleCloud V2 (concept-mapping, group-conversion, module-equivalent-list)
- Migrate from Pterodactyl (framing: PTero is panel, this is orchestrator — what's gained, what's lost, deployment-style differences)

**Each recipe structure:**
1. "What you'll build" (1 paragraph + screenshot/diagram)
2. Prerequisites (with version numbers)
3. Step-by-step (numbered, with copyable code blocks)
4. "How to verify it works"
5. "Where to go next"
6. Edit-on-GitHub link at bottom

**Verification:**
- Each recipe is < 1500 words but complete.
- Every code block is copy-pastable (no `<your-thing-here>` without inline explanation what to substitute).
- Each migration guide includes a feature-mapping table (CloudNet/SimpleCloud term → PrexorCloud term).

---

### Phase 9 — Comparison pages (CloudNet 4, SimpleCloud V2)

**Goal:** Honest, well-researched comparison pages that rank well for "PrexorCloud vs X". Diplomatic tone — no hit pieces. Used for SEO, not flame-bait.

**Page structure (per competitor):**

1. **Hero + 1-paragraph summary** — "When PrexorCloud is the better fit, when CloudNet 4 is."
2. **Feature matrix** — 25-30 rows × 3 columns (PrexorCloud / CloudNet 4 / Notes). Categories: scheduling, scaling, templates, security, observability, module/plugin system, deployment, HA, OSS license + governance.
3. **Architecture comparison** — diagram side-by-side.
4. **Where PrexorCloud is stronger** — bulleted, evidence-backed (link to concept pages or features).
5. **Where the competitor is stronger** — also bulleted, also honest. "CloudNet 4 has a longer track record / larger community." This is the credibility-builder.
6. **Migration guide link** — points at the matching Phase-8 recipe.
7. **TL;DR table** — one-row summary at the end.

**Research effort:** ~1 day per competitor reading their docs + cross-referencing features. Add 2 extra hours per page if you want to verify by actually running the competitor.

**Constraint:** Pterodactyl gets called out in concepts ("not a comparison — different tool category") but **does not get a vs-page**. This avoids muddying the orchestrator-vs-panel framing.

**Verification:**
- Feature matrix rows match between both comparison pages (same categories, parallel structure).
- Both pages include "where competitor wins" — peer review check.
- No factually wrong claims — every "stronger because" links to actual code/docs.

---

### Phase 10 — Asset production track (parallel)

> **Status:** blog posts (3), changelog, OG-image Satori route, favicons, and
> the Mermaid diagrams are in the tree. Remaining — dashboard screenshots,
> asciinema casts, component illustrations — need a live cluster or design
> tooling: see [`LIVE_CLUSTER_GUIDE.md`](./LIVE_CLUSTER_GUIDE.md) §3.1.

**Goal:** Every page that needs a visual has one by launch. Asset work runs in parallel with phases 4-9, gates on Phase 1 design tokens.

**Asset inventory:**

| Asset | Count | Format | Source |
|---|---|---|---|
| Architecture diagrams | 6 | Mermaid (in MD) | Hand-drawn from existing engineering notes |
| Component illustrations | 8 | SVG (per design-system style) | AI-generated via design-system Midjourney/DALL-E prompt prefix |
| Dashboard screenshots | 12-15 | PNG @2x | Live dashboard with seeded demo data, Cmd+Shift+4 captures |
| asciinema casts | 8 | `.cast` JSON, embedded via asciinema-player | Recorded from real CLI sessions |
| OG images | dynamic | PNG @1200×630 | Dynamic generation via Satori (template at `src/components/og/Default.astro`) |
| Logo refresh? | TBD | SVG | Decide in design-system phase whether current logo stays or gets refresh |
| Hero terminal recording | 1 | inline animation (already exists) | Migrated from existing Vue component |
| Mermaid diagrams | per concept page | inline | Hand-drawn |
| Favicons | 1 source SVG | + PWA 32/192/512 PNGs | Generated from logo via `realfavicongenerator.net` |
| Blog header images | 3 (initial) | PNG @1200×630 | Per design-system style |

**Phase ordering:**
- Day 1: design-system delivers, defines illustration style + OG template
- Day 2-3: OG-image template (Satori-based dynamic generator emitting per-page OG images at build time)
- Day 4-7: dashboard screenshots (after dashboard has demo data; needs running Mongo + controller + daemon)
- Day 8-12: asciinema casts (simultaneously with CLI doc writing in Phase 5)
- Day 13-15: component illustrations, blog headers
- Day 16-18: architecture diagrams (some auto-generatable from existing Mermaid in `docs/engineering/`, others hand-drawn)

**Initial blog posts (3 at launch):**
- "PrexorCloud v1.0 — A Cloud System Built for Minecraft" (announcement, ~1500 words)
- "Why we built another cloud orchestrator" (vision + framing, ~2000 words)
- "Daemon-Side Modules — How Layer 7 unlocks node-local extension" (technical deep-dive, ~2500 words; mines the Layer 7 section of `API_OVERHAUL.md`)

**Verification:**
- Every concept page has at least one diagram or screenshot.
- OG images render correctly when shared on Twitter/Discord/LinkedIn (manual link-preview check).
- Every CLI command in Phase 5 has at least one asciinema cast or screenshot example.

---

### Phase 11 — Pre-launch polish + launch

> **Status:** owner-driven. The whole phase is verification + ops + comms —
> no code work remains. The checklist and launch sequence are reproduced as
> an operator runbook in [`LIVE_CLUSTER_GUIDE.md`](./LIVE_CLUSTER_GUIDE.md)
> §3.2–§3.3.

**Goal:** Site is launch-ready. DNS cuts over. Announcement goes out.

**Pre-launch checklist:**

- [ ] Lighthouse all four metrics ≥ 95 on landing, ≥ 90 on docs (worst-case heavy reference pages).
- [ ] WCAG AA: axe-core CI check green; manual keyboard-navigation pass on landing + 5 random doc pages.
- [ ] Cross-browser: Chrome, Firefox, Safari, Edge — manual smoke test.
- [ ] Mobile responsive: 320, 375, 768, 1024, 1440 breakpoints all clean.
- [ ] All internal links resolve (`pnpm --filter website astro check`).
- [ ] Pagefind search returns sensible top hits for "module", "daemon", "scaling", "rolling deploy", "mtls".
- [ ] Edit-on-GitHub link works from every page (configured per Starlight).
- [ ] 404 page exists with helpful recovery links.
- [ ] robots.txt + sitemap.xml.gz both correct.
- [ ] Favicon shows in tab + bookmarks.
- [ ] OG image renders for landing + 3 random pages (manual share preview).
- [ ] Cloudflare Web Analytics live and showing data.
- [ ] Algolia DocSearch application submitted (parallel — won't block launch).
- [ ] CHANGELOG.md exists with v1.0 entry.
- [ ] License visible (MIT) in footer + repo.

**Launch sequence:**

1. Final preview deploy → smoke test on `*.pages.dev` URL.
2. DNS apex `prexor.cloud` cuts to CF Pages.
3. `www.prexor.cloud` → 301 redirect to apex.
4. GitHub repo description + topics updated.
5. Announcement order:
   - GitHub release (v1.0 tag).
   - r/admincraft post (community).
   - r/feedthebeast (relevant?).
   - Show HN.
   - Twitter / X (@prexorcloud or personal account).
   - Discord server announcement.
   - Submit to `awesome-minecraft-server` lists.
6. Monitor analytics for first 48h, fix obvious issues.

---

## Critical files / paths

**Repo restructure (Phase 1):**

- `docs/engineering/WEBSITE_PLAN.md` — this file
- `docs/engineering/API_OVERHAUL.md` — moved from `docs/`
- `docs/adr/` — empty for now, populated lazily
- `docs/public/en/` — content tree (24 + 8 + 10 + 2 + 12 = 56 minimum pages at launch, more with reference)
- `docs/openapi.yaml` — stays as input
- `docs/engineering/design-system.md` — output of design-prompt, consumed by Phase 1
- `website/` — REPLACED with Astro Starlight project (delete old Nuxt content)

**New tooling:**

- `tools/gen-cli-docs.ts` — Node script, walks cobra `--help`
- `tools/gen-grpc-docs.sh` — wrapper around `buf generate`
- `buf.yaml` + `buf.gen.yaml` — at repo root
- `.github/workflows/website.yml` — CI/CD

**Astro project structure** (under `website/`):

- `astro.config.mjs` — Starlight config + integrations
- `src/content/config.ts` — Content collections
- `src/components/landing/{HeroSection,BentoFeatures,ShowcaseSection,CtaSection}.vue` — migrated
- `src/components/docs/{Callout,TerminalBlock,StatusBadge,Bento}.astro` — Starlight overrides
- `src/components/og/Default.astro` — Satori OG template
- `src/pages/index.astro` — landing
- `src/pages/playground.astro` — Scalar
- `src/pages/og/[...slug].png.ts` — dynamic OG generator
- `src/styles/{tokens.css,starlight.css}` — design tokens + theme overrides

---

## Open items / risks

1. **Design-system output quality.** The plan assumes the produced `docs/engineering/design-system.md` is usable as-is. If the output is weak, an extra design iteration is needed (~1-2 days). Mitigation: review output against the 10-section spec before adopting; iterate on weak sections only.
2. **starlight-openapi + OpenAPI 3.1 quirks.** The plugin's primary support is OpenAPI 3.0. Our `docs/openapi.yaml` declares `3.1.0`. Some 3.1-only constructs (`$schema`, JSON Schema 2020-12 keywords) may not render. Mitigation: pre-flight test with one tag (Auth) early in Phase 5; if blocked, downgrade spec to 3.0 or fork the plugin.
3. **protoc-gen-doc + buf.build version drift.** Buf has been moving fast. Pin specific versions in `buf.gen.yaml`. Mitigation: lock `protoc-gen-doc@1.5.1` (last known stable) explicitly.
4. **Algolia DocSearch approval timing.** 1-2 weeks. Don't block launch on it. Pagefind is good enough for v1.0.
5. **Dashboard screenshots require demo data.** A seeded Mongo + running stack is needed. Mitigation: write a `tools/seed-demo-cluster.sh` script that produces a deterministic snapshot — also useful for CI screenshot regression tests later.
6. **Asset production is the longest tail.** If illustrations slip, the site can launch with placeholder gradients (still better than the current site's nothing). Don't gate launch on illustration completeness.
7. **CloudNet 4 / SimpleCloud V2 comparison-page legal risk.** Comparison pages are accepted industry practice but the line between "fair comparison" and "trade libel" matters. Mitigation: every claim about a competitor links to that competitor's own docs; no claims about reliability, performance, or security without measured evidence.
8. **`tools/gen-cli-docs.ts` requires a built `prexorctl`.** CI must build the CLI before generating docs. Mitigation: workflow's `gen-cli` job depends on a `prexorctl-built` artifact from the same workflow.
9. **Tailwind 4 stability.** Tailwind 4 reached stable mid-2025 but the Astro integration occasionally has rough edges. Fallback: Tailwind 3.4 (stable, well-supported) — same tokens work.
10. **Multi-language (EN-only at launch but i18n-ready).** Astro Content collection paths use `docs/public/en/` to leave room for `docs/public/de/` later without restructuring. Don't block launch on translation.
11. **`docs/openapi.yaml` is hand-maintained.** Phase ≥ 11 should introduce javalin-openapi (`io.javalin:javalin-openapi-plugin`) so the spec becomes a build artifact. Out of scope for v1.0 launch. **Done 2026-05-12** — controller now uses `io.javalin.community.openapi:javalin-openapi-plugin` and publishes the generated spec to `docs/openapi.json`. `docs/openapi.yaml` and `scripts/check-openapi-routes.sh` deleted. See `refactor-backlog.md`.

---

## Verification (overall, end of Phase 11)

- `pnpm --filter website build` produces a static `dist/` < 5 MB excl. images.
- `pnpm --filter website astro check` — green (no broken internal links).
- Lighthouse CI on landing + 3 representative doc pages: ≥ 95 perf, 100 a11y, 100 best-practices, 100 SEO.
- `grep -rE "SQLite|@CloudModule|CloudModuleBase|PlatformModuleContext|sqlite" docs/public/` returns zero hits.
- All 151 OpenAPI endpoints have rendered pages under `/docs/reference/rest-api/`.
- `/playground` Scalar embed loads + spec-tries succeed against a running controller.
- All proto services from `cloud-protocol/src/main/proto/` render under `/docs/internals/protocol/`.
- Every concept page has at least one Mermaid diagram or screenshot.
- Pagefind search returns relevant hits for the 10 most-searched terms (manual check: "install", "module", "daemon", "scaling", "rolling deploy", "mtls", "rest api", "events", "capability", "template").
- Cloudflare Web Analytics shows live traffic.
- Edit-on-GitHub on every doc page resolves to the right file in `docs/public/en/`.
- Algolia DocSearch application submitted (parallel; non-blocking).
- v1.0 announcement live on GitHub + r/admincraft + Show HN + Twitter + Discord.

---

## What this plan deliberately does NOT include

- **Hosted offering / pricing / sign-up flow.** Pure OSS positioning. If hosted launches later, that's its own track.
- **Multi-language content at launch.** EN-only. i18n-ready but no DE/FR/ES translations until proven demand.
- **Live demo cluster.** Operational cost too high for benefit pre-v1.0. Reconsider if marketing data shows users churn at the "show me how it works" moment.
- **Comments / Giscus on docs.** Spam-vs-moderation cost not worth it. Issues + Discord serve.
- **Automated OpenAPI generation from Javalin.** Phase ≥ 11 follow-up. Hand-maintained spec is fine for v1.0.
- **A documentation versioning UI (`/v1.0/...` paths).** Single-current at launch. starlight-versions is a 1-day retrofit when v2.0 actually breaks something.
- **Tutorials in video format.** Text + asciinema only. Video is much higher production cost per minute and not great SEO.
- **Pterodactyl comparison page.** Different tool category — don't muddy the messaging.
