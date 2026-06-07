import { test, expect, type Page } from "@playwright/test"

/**
 * Visual baselines covering every key dashboard page. Uses the dev-mock layer
 * (sentinel auth token) so no controller is required — every fetch and SSE
 * connection is short-circuited to canned data.
 *
 * Add a page: append to `PAGES` below, run `pnpm test:visual:update`, commit
 * the new baseline next to this file.
 */

interface Surface { name: string; path: string }

const PAGES: Surface[] = [
  { name: "overview",         path: "/" },
  { name: "instances-list",   path: "/instances" },
  { name: "instance-detail",  path: "/instances/lobby-01" },
  { name: "nodes-list",       path: "/nodes" },
  { name: "node-detail",      path: "/nodes/node-1" },
  { name: "groups-list",      path: "/groups" },
  { name: "group-detail",     path: "/groups/lobby" },
  { name: "networks",         path: "/networks" },
  { name: "templates-list",   path: "/templates" },
  { name: "modules",          path: "/modules" },
  { name: "catalog-list",     path: "/catalog" },
  { name: "catalog-detail",   path: "/catalog/Paper" },
  { name: "crashes",          path: "/crashes" },
  { name: "audit",            path: "/audit" },
  { name: "settings",         path: "/settings" },
  { name: "profile",          path: "/profile" },
]

async function devSignIn(page: Page) {
  await page.goto("/login")
  await page.getByRole("button", { name: /sign in as dev/i }).click()
  await page.waitForURL(url => !url.pathname.startsWith("/login"))
}

test.describe("dashboard visual baselines", () => {
  test.beforeEach(async ({ page }) => {
    await devSignIn(page)
  })

  for (const surface of PAGES) {
    test(`${surface.name}`, async ({ page }) => {
      await page.goto(surface.path)

      // Wait for the page-header gradient title to render — every page has one.
      await page.waitForLoadState("networkidle")

      // Disable animations so screenshots are deterministic.
      await page.addStyleTag({ content: `
        *, *::before, *::after {
          animation-duration: 0s !important;
          transition-duration: 0s !important;
        }
      ` })

      await expect(page).toHaveScreenshot(`${surface.name}.png`, { fullPage: true })
    })
  }

  test("login", async ({ page }) => {
    await page.context().clearCookies()
    await page.evaluate(() => localStorage.clear())
    await page.goto("/login")
    await page.waitForLoadState("networkidle")
    await page.addStyleTag({ content: `*,*::before,*::after{animation-duration:0s!important;transition-duration:0s!important;}` })
    await expect(page).toHaveScreenshot("login.png", { fullPage: true })
  })
})
