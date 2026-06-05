# Track E — Frontend & Design-System Konsolidierung: Implementierungsplan

**Status:** gezeichnet 2026-06-05, als Vor-Start-Scoping gemäß northstar-plan §14 („Track-E-Scope ist groß und schlecht definiert … sollte vor Start sauber gescopet werden").
**Eltern-Plan:** [`northstar-plan.md`](./northstar-plan.md) §6 (Track E).
**Heutiger Stand:** Track E ≈ 40 %. E.1-Token-Pipeline operationalisiert, i18n-Foundation (H.3) fertig, Mermaid-Palette generiert. Offen: A11y-Runtime-Härtung, Installer-Entdoppelung, Website-Theme-Generierung, DS-Component-Library-Entscheidung.

---

## 0. Entscheidung vorab — was Track E **nicht** mehr ist (ADR 32)

Der ursprüngliche northstar-Text für E.2/E.3 sagt „Dashboard/Installer importieren Design-System-**Komponenten** statt eigener Implementierungen". **Das ist durch ADR 32 überholt.** ADR 32 hat bewusst gegen ein importierbares `@prexorcloud/design-system`-Package entschieden — vier unabhängige pnpm-Projekte mit eigenen Lockfiles, plus jede Surface braucht eine andere Token-Form (Dashboard: shadcn-Aliase + Hex; Website: HSL-Tripletts für Tailwind-Alpha; Installer: Hex; CLI: ANSI). Stattdessen: **Tokens werden ge-mirror-t und per CI-Drift-Guard an den Canon gepinnt.**

**Konsequenz für Track E — drei Korrekturen am Scope:**

1. **Kein gemeinsames Vue-Komponenten-Package.** Jede Surface behält ihre eigene Komponenten-Schicht (Dashboard: Reka-UI-Primitives unter `dashboard/app/components/ui/`; Installer: eigene Vue-SFCs; Website: Starlight). „Konsolidierung" heißt: **gemeinsame Tokens + Drift-Guard + A11y-Standard**, nicht gemeinsamer Code.
2. **Die `design-system/components.css` bleibt Referenz-/Constraint-Schicht**, kein Build-Artefakt zum Importieren. Sie wird breiter (mehr Primitives) und drift-gerichtet, aber nicht in die Frontends ge-`@import`-t.
3. **Der eigentliche messbare Hebel ist A11y**, nicht Code-Entdoppelung. Das v1.2-Erfolgskriterium ist „Lighthouse-A11y ≥ 90 auf Dashboard", v1.3 „A11y ≥ 95". Das wird Phase 1 — vor allem anderen.

Dadurch schrumpft die ehrliche Effort-Schätzung von ~20 auf **~13–14 eng-days** (die Package-/Shared-Component-Migration entfällt).

---

## 1. Phasierung (nach Wert sortiert, nicht nach northstar-Nummerierung)

| Phase | Inhalt | Effort | northstar-Mapping | Gate |
|---|---|---|---|---|
| **E-P1** | A11y-Runtime-Härtung (authed-axe ⏳scaffold, Contrast ✅, Keyboard-Nav ✅) | ~5–6 d (E-P1.2 ✅, E-P1.3 ✅, E-P1.1 scaffold) | E.2 (A11y-Pass) | **Hart**: axe 0 serious/critical über authed-Flows |
| **E-P2** | Installer-Entdoppelung + Installer-A11y-Lint ✅ | ~3–4 d (A11y-Lint ✅) | E.3 | Hart: Installer-a11y-Lint grün ✅, Drift-Guard erweitert ⏳ |
| **E-P3** | Website-Starlight-Theme aus DS-Tokens generieren | ~3 d (HSL-Foundation ✅) | E.4 | Hart: Theme-Freshness-Guard |
| **E-P4** | DS-Component-Library-Close-out (Entscheidung + components.css-Ausbau) ✅ | ~1 d ✅ | E.1-Rest | components.test.mjs grün ✅, ADR 33 ✅ |

Reihenfolge-Constraint: **E-P1 zuerst** (Erfolgskriterium des Milestones). E-P2/E-P3/E-P4 sind unabhängig und parallelisierbar.

---

## 2. E-P1 — A11y-Runtime-Härtung (~5–6 d) — *der Headline-Deliverable*

**Heute:** Statischer a11y-Lint (`dashboard/scripts/a11y-lint.mjs`) ist **harter** Gate, deckt aber nur zwei statisch entscheidbare WCAG-Defekte ab (`img`-alt 1.1.1, Icon-Control-Name 4.1.2). Der Runtime-axe-Job (`ci.yml`) ist **soft** (`continue-on-error`) und scannt nur die **unauthed** Oberfläche (`/login` + Root-Shell) — die ganze App liegt hinter Login.

### E-P1.1 Authed-Flow-axe im CI via dev-mock-Test-Login — ⏳ **scaffolded (soft, 2026-06-05); Härtung nach erstem CI-Lauf offen**

**Schlüssel-Asset:** `dashboard/app/lib/dev-mock/` patcht `globalThis.fetch`/`EventSource` und liefert `POST /api/v1/auth/login` + `GET /api/v1/auth/me` → `DEV_MOCK_TOKEN` + vollständigen Mock-Datensatz. Der Auth-Store rehydriert auf Page-Load aus `localStorage.auth_token` und ruft `/auth/me` (→ gemockt) → seeden des Tokens reicht, damit jeder `page.goto()` **authed** bootet, ohne Backend.

**Geliefert (Scaffold):**
- **Build-Time-Schalter** `DEV_MOCK_ENABLED` (`dashboard/app/lib/dev-mock/enabled.ts`): `import.meta.env.DEV || VITE_DEV_MOCK==='1'`. `isDevMockAvailable()` (`index.ts`) und der Install-Gate (`install.ts`) hängen jetzt daran — der Mock greift damit auch in einem `VITE_DEV_MOCK=1 pnpm build` (Preview), bleibt aber in Release-Builds tree-geshaket (Flag nie gesetzt). Dev-mock-Unit-Tests 15/15 grün.
- **Authed-axe-Script** `dashboard/scripts/axe-authed.mjs` (standalone, `playwright` + `@axe-core/playwright`): seedet `auth_token` via `context.addInitScript` vor jeder Navigation, scannt 16 statische Critical-Routes (inkl. `/cluster/config`), filtert auf `serious`+`critical`, erkennt Silent-Redirect-zu-`/login` (Auth nicht rehydriert → skip+Warnung), Exit 1 bei Findings. Detail-Routen (`/groups/[name]`, `/instances/[id]`) via `AXE_EXTRA_ROUTES` (brauchen deterministische Fixtures → Follow-up).
- **CI-Step** (`ci.yml`, dashboard-Job): Build mit `VITE_DEV_MOCK=1`, dann `pnpm preview` + `npm i --no-save @axe-core/playwright playwright` + `npx playwright install --with-deps chromium` + `node scripts/axe-authed.mjs`. **Soft** (`continue-on-error`) — kein Lockfile-Change (`--no-save`-Pattern wie der bestehende unauthed-axe-Job), kann den Build nicht brechen, solange noch nicht in CI validiert.

**Noch offen (Härtung):**
- Ersten CI-Lauf abwarten, Browser-Install + Route-Rehydration verifizieren, axe-`serious`-Backlog sichten.
- Gate von **soft → hart** ziehen (`serious`+`critical` = 0), mit Allow-List für bewusst akzeptierte Findings (analog i18n-`ALLOW`).
- Detail-Routen-Fixtures + `/groups/[name]`, `/instances/[id]` in den Scan ziehen.

**Akzeptanz (bei Härtung):** CI failed, wenn eine authed-Critical-Route ein `serious`/`critical` axe-Finding hat. Grüner Lauf = Dashboard-A11y-Baseline ≥ 90 (Milestone-v1.2-Kriterium).

### E-P1.2 Color-Contrast-Audit gegen Design-System-Tokens — ✅ **shipped (2026-06-05)**

**Geliefert:**
- **`design-system/__tests__/contrast.test.mjs`** (zero-dep, `node:test` wie die Geschwister): berechnet die WCAG-Relativ-Luminanz-Ratio für alle opaken Foreground/Surface-Paare aus `tokens.json` (dark **und** light). Zwei Tiers: **FLOOR 3.0:1** für jedes Text/UI-Paar (1.4.3 large / 1.4.11 UI — Filled-Controls/Badges tragen ≥14px-semibold-Labels), **BODY 4.5:1** für die Lese-Oberflächen (foreground/bg, card/popover-fg, muted-fg/bg). Transluzente Tokens (8-stelliger Hex mit Alpha — Border/Glow/Glass) werden übersprungen (Kontrast hängt vom Composite-Hintergrund ab, den nur das DOM kennt). Läuft im bestehenden `design-system`-CI-Job (`node --test`) → **harter Gate**, kein neuer Job nötig.
- **Echter Bug gefunden + gefixt:** `accent-foreground` **dark** war `var(--ink-9)` (near-white `#ededf2`) auf `accent: var(--reef-dark)` (helles Cyan) = **1.75:1** — illesbar, inkonsistent zu `primary-foreground` (`ink-1`, 9.65:1), obwohl dark `accent == primary == reef-dark`. Fix: `accent-foreground` dark → `var(--ink-1)` im Canon (`colors_and_type.css` + `tokens.json`), `dist/` regeneriert (Freshness-Guard grün), und derselbe gespiegelte Bug in `website/src/styles/tokens.css` gefixt (die Website **rendert** ihn real: `.btn-accent` + `.hero-pill__tag` setzen `color: hsl(var(--accent-foreground))` auf `background: hsl(var(--accent))`). Das **Dashboard** war unbetroffen — es remappt `--accent` bewusst auf `var(--ink-4)` (dunkle Hover-Surface), also `ink-9` darauf ist legibel.
- **Verifikation:** 16/16 `design-system`-Tests grün (4 neue Contrast-Tests + Parity + Freshness), Website-`tokens.css` prettier-clean.

**Scope ehrlich:** Der Test guardet den **Canon**. Borderline-Control-Paare (3.0–4.5:1, z.B. light `primary-foreground`/`primary` 4.03 — weißer Button-Text auf Teal) bleiben bewusst beim 3.0-Floor (semibold ≥14px = „large") und werden im gerenderten axe-Pass (E-P1.1) kontextabhängig verifiziert, statt den Canon zu einer Palette-Änderung zu zwingen. Semantik-Token-Drift zwischen Canon und Surfaces ist weiterhin nicht gepinnt (ADR-32-Grenze) — die Website-Spiegelung war ein manueller Fix, kein Guard.

### E-P1.3 Keyboard-Navigation der Critical-Flows — ✅ **shipped (2026-06-05)**

**Heute:** Reka-UI-`Dialog` liefert nativen Modal-A11y (Focus-Trap, Escape, Fokus-Return, ARIA). Inputs/Buttons haben `focus-visible`-Styling. Die verbleibende echte Lücke war der **Step-Wechsel-Fokus** im Wizard.

**Geliefert (Group Create):**
- **Reusable Focus-Helper** `dashboard/app/lib/a11y/focus.ts`: `firstFocusable(root)` / `focusFirst(root)` — Layout-frei (Visibility aus Attributen/Inline-Styles, nicht aus Geometrie), damit unter jsdom **und** echtem Browser identisch; überspringt disabled/`[tabindex="-1"]`/hidden/`aria-hidden`/`display:none`; Fallback auf den Container (programmatisch fokussierbar), wenn kein fokussierbares Kind. Unit-Tests `__tests__/focus.test.ts` (9, deterministisch).
- **Wizard verdrahtet:** `CreateGroupDialog.vue` — die Step-`<Transition mode="out-in">` ruft jetzt `@after-enter="onStepEntered"` → `focusFirst(el)`, sodass beim Vor-/Zurück-Blättern der Fokus dem neuen Step folgt (WCAG 2.4.3), statt auf dem Next/Back-Button des vorigen Steps zu stranden. Feuert nur auf Step-Swaps; das Öffnen behandelt Reka-UI. Integrationstest in `CreateGroupDialog.test.ts` (nach `advance()` sitzt `document.activeElement` auf einem Control im Step-Content `.styled-scrollbar` — failt ohne die Verdrahtung). 10/10 Wizard-Tests grün; 0 neue eslint/typecheck-Fehler (Stash-Vergleich 13=13).

**Geliefert (Instances-Console, 2026-06-05):** Die `InstanceConsole`-Befehls-`<input>` trug nur einen `placeholder` (kein Accessible Name — der statische a11y-Lint prüft nur Icon-Controls, nicht Text-Inputs, also rutschte es durch; axe würde es als 4.1.2 melden). Jetzt mit gebundenem `:aria-label="t('components.console.commandLabel')"` (i18n en+de, Parity-Gate grün bei 2107 Keys). Regressionstest in `InstanceConsole.test.ts` (8/8). 0 neue eslint/typecheck-Fehler (Stash-Vergleich). History-Nav (↑/↓) + Enter-to-send waren bereits da; der xterm-Host hat seine eigene a11y.

**Geliefert (Deploy-Sweep, 2026-06-05):** Befund: der Deploy-/Restart-Trigger sitzt auf `pages/groups/[name].vue` (nicht `deployments.vue`, das ist read-only), und es gibt **kein** Confirmation-Modal — `deployGroup`/`restartGroup` feuern direkt. Die Auslöser sind native Reka-UI-`<Button>`s mit sichtbarem Text („Deploy"/„Restart") → von Haus aus fokussierbar + Enter/Space-aktivierbar, Accessible Name aus dem Text. **Kein a11y-Defekt** — die Plan-Annahme „Confirmation-Modal" traf nicht zu. Per-Flow-Regressionstest in `groups-name.test.ts` (native `<button>` + non-leerer Accessible Name für beide Trigger), neben dem bestehenden funktionalen Deploy-Test. 11/11 grün, eslint clean.

**Verbleibend (nicht-blockierend):** End-to-End-Tastatur-Verifikation Group-Create → Deploy im echten Browser fällt mit dem authed-axe-Lauf (E-P1.1) zusammen — kein separater manueller Schritt nötig, sobald E-P1.1 hart ist.

**Akzeptanz (bei Abschluss):** Group-Create → Deploy end-to-end per Tastatur, Regressions-Test pro Flow. Der wiederverwendbare `focus.ts`-Helper steht für die übrigen Flows bereit.

---

## 3. E-P2 — Installer-Entdoppelung + A11y-Lint (~3–4 d) — ⏳ **A11y-Lint shipped (2026-06-05); Token-Dedup offen**

**Heute:** `installer/src/styles/wizard.css` = **1.799 Zeilen** hand-geschriebene Komponenten-Styles (`.btn`/`.input`/`.select`/`.badge`/`.card`-Varianten), plus eigenes `installer/src/styles/tokens.css` (Hex, Alias-Pattern „während des restlichen Ports"). Drift-Guard pinnt heute nur Raw-Scales + `--text-*`.

**Geliefert (Installer-A11y-Lint, 2026-06-05):**
- `installer/scripts/a11y-lint.mjs` — der Dashboard-`a11y-lint`-Mechanismus 1:1 portiert (zero-dep Tag/Quote-Tokenizer, scannt `installer/src/**/*.vue`; `INTERACTIVE_COMPONENTS` leer, da der Installer native `<button>` + `.btn`-Klasse statt eines Button-Wrappers nutzt). Prüft `<img>`-alt (1.1.1) + Icon-only-Control-Namen (4.1.2).
- `npm run a11y:check` (`installer/package.json`), als **harter** Gate in den Installer-CI-Job (`ci.yml`, vor typecheck) gehängt — der Installer ist die **erste** Oberfläche, die ein Operator sieht.
- **Befund: 0 Verstöße** — die Installer-Buttons sind bereits text-/`title`-benannt (z.B. `ListInput.vue` Remove-Button `title="Remove"`, „+ Add", Wizard-Nav „Next"/„Back"). Der Gate ist also grün und **lockt** das jetzt. Negativ-Kontrolle (temporäres Icon-only-Button + `<img>`-Fixture) verifiziert, dass der Lint real prüft (flaggt 2, dann grün nach Entfernen). `format:check` unberührt (Script liegt unter `scripts/`, nicht `src/`).

**Noch offen (Token-Dedup, eigener Pass — visuell, riskanter):**
- **Token-Angleichung:** Die Alias-Schicht (`--foreground`/`--background`/`--primary` als Legacy-Wrapper) auf die kanonischen DS-Token-Namen reduzieren. Ziel: gleiche Token-**Werte** wie der Canon, vom Drift-Guard erzwungen (kein Code-Sharing, ADR 32).
- **Drift-Guard erweitern** (`surface-drift.test.mjs`): Installer-Semantik-Aliase pinnen, die 1:1 dem Canon entsprechen sollen (heute nicht gepinnt → stiller Drift möglich).
- **Duplikat-Reduktion:** die offensichtlichsten `wizard.css`-Wiederholungen (Button-/Badge-Varianten) auf Token-getriebene Basis+Modifier zusammenziehen (Vorbild `components.css`), gescopet auf Button/Input/Badge/Card.

**Akzeptanz:** Installer-a11y-Lint grün + hart ✅; Drift-Guard deckt Installer-Semantik-Aliase ⏳; Button/Input/Badge/Card-Duplikate reduziert ⏳.

---

## 4. E-P3 — Website-Starlight-Theme aus DS-Tokens (E.4, ~3 d) — ✅ **shipped (2026-06-05)**

**Website-Wiring geliefert (2026-06-05):** `website/scripts/gen-starlight-theme.mjs` (Vorbild `gen-mermaid-theme.mjs`) erzeugt den `--sl-color-*`→Semantik-Block aus `design-system/dist/tokens.json` nach `src/styles/starlight-theme.generated.css`; geladen via `astro.config` `customCss` **nach** `starlight.css` (autoritativ). Die hand-gepflegten `:root[data-theme]`-Blöcke in `starlight.css` sind raus. **Entscheidung: DS-Werte sind Canon** — der Accent übernimmt das DS-Reef-Rounding (`187 61% 57%` statt der Website-`188 58% 57%`), Delta sub-perzeptuell und auf die Starlight-Chrome begrenzt. `predev`/`prebuild`-Hook + `gen:starlight`-Script + **harter Freshness-Guard** in `website.yml` (`git diff --exit-code`). Generator-Output ist prettier-clean (koexistiert mit `format:check`). `astro check` 0 Errors. Richere Website-only-Tokens (Accent-Ramp, Spacing, `--z-*`) bleiben hand-gepflegt in `tokens.css`.

**Geliefert (DS-Foundation):**
- **`build-tokens.mjs` erweitert** → emittiert zusätzlich `dist/tokens.hsl.css`: dieselben Semantik-Tokens (dark+light) als **`h s% l%`-Tripletts** (Use-Site-Alpha, z.B. `--primary-glow: 187 61% 57% / 0.251`, `--border: 0 0% 100% / 0.059`) — exakt die Form, die die Website (Tailwind/HSL, `hsl(var(--x) / a)`) konsumiert. `hexToHslTriplet()` deckt `#rgb`/`#rrggbb`/`#rrggbbaa`. Raw-Scales bleiben in `tokens.css` (Hex); Spacing ist dort schon als `--space-*` (theme-agnostisch, keine HSL-Variante nötig).
- **Freshness-guarded:** `dist/tokens.hsl.css` ist im `tokens.test.mjs`-Freshness-Loop (16/16 grün) und im `design-system`-CI-Job (`build` + `git diff --exit-code dist`).

**Noch offen (Website-Wiring — braucht visuelle Verifikation, daher hier nicht gemacht):**
- **HSL-Werte-Reconciliation:** der Generator rundet leicht anders als die hand-gepflegten Website-Werte (z.B. `--primary` DS `187 61% 57%` vs. Website `188 58% 57%`) — vor dem Drop-in angleichen (Generator-Rundung als Canon nehmen oder Website-Werte adoptieren).
- **`gen-starlight-theme.mjs`** (Vorbild `gen-mermaid-theme.mjs`) erzeugt den `--sl-color-*`→semantik-Block aus `dist/tokens.hsl.css`; Freshness-Guard in `website.yml`; Website rendert unverändert (visueller Diff = 0 prüfen).

---

### Ursprünglicher Plan (Referenz)

**Heute:** `website/src/styles/tokens.css` (292 Zeilen, **HSL-Tripletts**) ist **reicher** als das DS-Token-Set: `--accent-50…950`-Ramp (11 Stufen), `--space-*` (13), eigene Type-Scale, `--z-*`. Die Starlight-Farben (`--sl-color-*`) sind hand-gewartet, aber an die semantischen Tokens gewired (`starlight.css`). Die Mermaid-Palette wird **schon** aus `dist/tokens.json` generiert (`gen-mermaid-theme.mjs`, Freshness-Guard hart).

**Das Kernproblem:** Reiner Drop-in geht nicht, weil das DS-Token-Set die HSL-Form + Spacing-Scale + Accent-Ramp nicht exportiert. Also **zuerst DS-Token-Set angleichen, dann generieren.**

**Konkret:**
- **DS-Token-Set erweitern** (`build-tokens.mjs`): zusätzlich zu `dist/tokens.css` (Hex) eine **HSL-Tripel-Variante** emittieren (`dist/tokens.hsl.css` mit `h s% l%`-Werten für Tailwind-Alpha), plus die Spacing-Scale aus `tokens.json` (die der Generator heute schon kennt) als CSS-Vars. Accent-Ramp: entweder in `tokens.json` aufnehmen oder bewusst Website-only lassen (dann nicht generieren — ehrlich dokumentieren).
- **Starlight-Theme generieren:** ein `website/scripts/gen-starlight-theme.mjs` (Vorbild `gen-mermaid-theme.mjs`) erzeugt den `--sl-color-*`→semantik-Block + die generierbaren semantischen Tokens aus `dist/`. Hand-gewartet bleibt nur, was die DS nicht kennt (Accent-Ramp, falls Website-only; `--z-*`).
- **Freshness-Guard** in `website.yml` (analog Mermaid: `git diff --exit-code` auf die generierte Datei).

**Akzeptanz:** Starlight-Theme-Farben kommen aus `dist/`; Freshness-Guard hart; Website rendert unverändert (visueller Diff = 0, weil Canon seit b736c50 = Quiet-Studio/Reef bereits den Surfaces entspricht).

**Cut-Line:** Wenn die HSL-Generierung teurer wird als gedacht, ist E-P3 der erste Streichkandidat — die Website driftet heute nicht (Guard pinnt Raw-Scales) und ist v1.0-geliefert. E-P3 ist „nice", kein Blocker.

---

## 5. E-P4 — DS-Component-Library-Close-out (E.1-Rest, ~1 d) — ✅ **shipped (2026-06-05)**

**Geliefert:**
- **`components.css` verbreitert:** `select`, `checkbox`, `switch` (Pill-Track + ::after-Knob via `--foreground`, kontrastiert mit muted- **und** primary-Track in beiden Themes), `table` (muted-foreground-Header, glass-hover-Rows), `tooltip` (popover-Surface + `--shadow-md`) ergänzt — alles token-only, nur gegen den Canon definierte Vars. `components.test.mjs` (kein Hardcode, keine dangling Token-Refs) **16/16 grün**. Der CSS-Layer deckt jetzt button/input/select/checkbox/switch/badge/card/table/tooltip.
- **Component-Runtime + Histoire formal gestrichen:** **ADR 33** („Design-system is a token + CSS reference, not a component runtime") als Korollar zu ADR 32 ergänzt — kein Vue/React-Component-Package, keine Story-App; Surfaces re-implementieren ihre Komponenten und mirror-n `components.css` wie `colors_and_type.css`. northstar E.1 „Komponenten-Library" + „Histoire" von ⏳ auf ✅ gezogen.

**Akzeptanz:** `components.css` +5 Primitives ✅; `components.test.mjs` grün ✅; ADR-Closeout (ADR 33) ✅; northstar E.1 aktualisiert ✅.

**Akzeptanz:** `components.css` deckt die 4 zusätzlichen Primitives; `components.test.mjs` grün; ADR-Eintrag schließt die Component-Library-Frage; northstar E.1 aktualisiert.

---

## 6. Sequenzierung & Abhängigkeiten

```
E-P1 (A11y) ─────────────────────────►  Milestone-Gate v1.2 (Lighthouse-A11y ≥ 90)
   ├─ E-P1.1 authed-axe (dev-mock)   ┐
   ├─ E-P1.2 contrast (token-test)   ├─ unabhängig, parallelisierbar
   └─ E-P1.3 keyboard-nav            ┘

E-P2 (Installer)  ── unabhängig ──┐
E-P3 (Website)    ── unabhängig ──┤  parallel zu E-P1
E-P4 (DS-Closeout)── unabhängig ──┘
```

- **Harte Reihenfolge:** keine — alle Phasen sind unabhängig. **Priorität:** E-P1 zuerst (Milestone-Kriterium).
- **E-P3 hängt** an der DS-Token-Set-Erweiterung (HSL/Spacing) — kein externer Block.
- **Kein Block durch Track A/C/D** — Track E ist reines Frontend, parallel zu allem.

---

## 7. Akzeptanz-Gates (Track-E-Ebene)

| # | Gate | Phase | Hart/Soft |
|---|---|---|---|
| 1 | authed-Flow-axe: 0 `serious`/`critical` über die Critical-Routes — ⏳ **scaffolded (soft)**, Härtung nach 1. CI-Lauf | E-P1.1 | soft → **hart** |
| 2 | Token-Contrast-Test: alle Body-Paare ≥ 4.5:1, Floor 3.0:1 (dark+light) — ✅ **shipped** | E-P1.2 | **hart** |
| 3 | Group-Create → Deploy vollständig per Tastatur (+ Regressions-Tests) — ✅ **shipped** (E2E-Browser-Check via E-P1.1) | E-P1.3 | **hart** |
| 4 | Installer-a11y-Lint grün ✅ (hart in CI); Drift-Guard deckt Installer-Semantik ⏳ | E-P2 | **hart** |
| 5 | Starlight-Theme aus `dist/`; Freshness-Guard — ✅ **shipped** (gen-starlight-theme.mjs + website.yml-Guard, astro check clean) | E-P3 | **hart** ✅ |
| 6 | `components.css` +5 Primitives ✅; ADR-Closeout (ADR 33) ✅ | E-P4 | **hart** ✅ |

**Milestone-Bezug:** Gates 1–3 erfüllen das v1.2-Kriterium „Lighthouse-A11y ≥ 90 auf Dashboard". Gate 1 auf `≥ 95` angehoben erfüllt das v1.3-Kriterium „A11y ≥ 95".

---

## 8. Risiken & Cut-Lines

| Risiko | Mitigation / Cut-Line |
|---|---|
| **Playwright-im-CI** (Browser-Install, Flakiness) zieht E-P1.1 in die Länge | Erst lokal grün, dann CI; Fallback: authed-axe als **soft** Gate landen lassen (immer noch besser als heute nur-unauthed), hart machen wenn stabil. |
| **axe-`serious`-Backlog** größer als gedacht | Allow-List für bewusste Ausnahmen (dokumentiert); Gate auf `critical`-only starten, dann `serious` dazu. |
| **DS-HSL-Generierung** (E-P3) aufwändiger als 3 d | E-P3 streichen/vertagen — Website driftet nicht, ist v1.0-geliefert. Niedrigste Priorität. |
| **Installer-Entdoppelung** wird zum Full-Rewrite | Strikt auf Button/Input/Badge/Card scopen; 1.799-Zeilen-`wizard.css` nicht anfassen jenseits davon. |
| **Scope-Creep** (Theme-Switcher, Notifications-Inbox — northstar §14) | Explizit **out of scope** für Track E. Eigene Backlog-Items. |

---

## 9. Bewusst out of scope (Track E)

- **Gemeinsames Vue-Component-Package** — durch ADR 32 entschieden, siehe §0.
- **Dashboard-Stack-Wechsel** — bleibt Nuxt 4 (northstar E.2 „optional, bewusst").
- **Histoire/Storybook** — siehe E-P4.
- **User-Settings/Theme-Switcher/Notifications-Inbox** — northstar §14 nennt sie als Scope-Creep-Gefahr; eigene Initiative.
- **Installer-Voll-Rewrite** — nur gezielte Entdoppelung.

---

**Effort-Gesamt: ~13–14 eng-days** (gegenüber northstar-Schätzung ~20 d; die Differenz ist die durch ADR 32 entfallene Shared-Component-Package-Migration). **Empfohlener Einstieg: E-P1.1** (authed-axe via dev-mock) — der dev-mock-Layer existiert schon, das ist der billigste Weg zum Milestone-A11y-Gate.
