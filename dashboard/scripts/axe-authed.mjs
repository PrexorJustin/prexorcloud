// Authed-flow accessibility scan (northstar Track E, E-P1.1).
//
// The dashboard is an authed SPA, so the unauthed axe-cli job can only see the
// login + root shell. This drives the *authenticated* app — every critical route
// behind login — and runs axe-core in a real browser, catching contrast and
// computed-role defects the static lint can't.
//
// How it stays backend-free: the dashboard is built with VITE_DEV_MOCK=1, which
// bakes in the dev-mock fetch/EventSource interceptors. Seeding
// localStorage.auth_token = DEV_MOCK_TOKEN before each navigation makes the auth
// store rehydrate against the mocked /api/v1/auth/me, so the app boots logged in
// with canned data — no controller required.
//
// Usage:  node scripts/axe-authed.mjs            (BASE_URL defaults to preview)
//         BASE_URL=http://127.0.0.1:3000 node scripts/axe-authed.mjs
//         AXE_EXTRA_ROUTES=/groups/lobby,/instances/i-1 node scripts/axe-authed.mjs
//
// Exit code: 1 if any route has a serious/critical violation, else 0. The CI
// step runs this soft (continue-on-error) until it's proven stable, then hard.

import { chromium } from "playwright"
import { AxeBuilder } from "@axe-core/playwright"

const BASE_URL = (process.env.BASE_URL || "http://127.0.0.1:3000").replace(/\/$/, "")
const AUTH_TOKEN_KEY = "auth_token"
const DEV_MOCK_TOKEN = "dev-mock-token-v1"

// Critical authed routes (static — no fixture ids needed). Detail routes
// (/groups/[name], /instances/[id]) need stable mock ids; pass them via
// AXE_EXTRA_ROUTES once the dev-mock exposes deterministic fixtures.
const ROUTES = [
  "/",
  "/groups",
  "/instances",
  "/nodes",
  "/networks",
  "/cluster/controllers",
  "/cluster/config",
  "/templates",
  "/catalog",
  "/modules",
  "/modules/registry",
  "/audit",
  "/observability/system",
  "/users",
  "/roles",
  "/settings",
  ...(process.env.AXE_EXTRA_ROUTES ? process.env.AXE_EXTRA_ROUTES.split(",").map((r) => r.trim()).filter(Boolean) : []),
]

const SEVERITIES = new Set(["serious", "critical"])

async function main() {
  const browser = await chromium.launch()
  const context = await browser.newContext()

  // Seed the dev-mock auth token before any document script runs, on every
  // navigation in this context — so each page.goto() boots authenticated.
  await context.addInitScript(
    ([key, token]) => {
      try {
        localStorage.setItem(key, token)
      } catch {
        /* about:blank etc. — ignored; set succeeds on the real origin */
      }
    },
    [AUTH_TOKEN_KEY, DEV_MOCK_TOKEN],
  )

  const page = await context.newPage()
  const offenders = []
  let scanned = 0

  for (const route of ROUTES) {
    const url = BASE_URL + route
    try {
      await page.goto(url, { waitUntil: "networkidle", timeout: 30_000 })
      // Guard against silent redirect-to-login (auth didn't rehydrate).
      if (new URL(page.url()).pathname.startsWith("/login") && route !== "/login") {
        console.log(`::warning::${route} redirected to /login — auth did not rehydrate, skipped`)
        continue
      }
      const results = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
        .analyze()
      scanned++
      const serious = results.violations.filter((v) => SEVERITIES.has(v.impact))
      if (serious.length) {
        offenders.push([route, serious])
        console.log(`✗ ${route} — ${serious.length} serious/critical`)
        for (const v of serious) {
          console.log(`    [${v.impact}] ${v.id}: ${v.help} (${v.nodes.length} node(s))`)
        }
      } else {
        console.log(`✓ ${route}`)
      }
    } catch (err) {
      console.log(`::warning::${route} failed to scan: ${err.message}`)
    }
  }

  await browser.close()

  console.log(`\nScanned ${scanned}/${ROUTES.length} routes; ${offenders.length} with serious/critical violations.`)
  if (offenders.length) process.exit(1)
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
