import { defineConfig, devices } from "@playwright/test"

/**
 * Visual baselines for the dashboard. Boots `nuxt dev`, signs in via the
 * dev-mock layer (no controller required), and screenshots key pages in
 * dark + light modes. Run via `pnpm test:visual`; refresh baselines with
 * `pnpm test:visual:update`.
 */
export default defineConfig({
  testDir: "./tests/visual",
  testMatch: /.*\.spec\.ts$/,
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI ? "github" : "list",

  use: {
    baseURL: "http://localhost:3000",
    trace: "retain-on-failure",
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
  },

  // Snapshot settings — small per-pixel tolerance because chrome subpixel
  // antialiasing differs slightly between machines. Threshold tuned for the
  // glass surfaces and Sparkline canvases without being lax enough to miss
  // real regressions.
  expect: {
    toHaveScreenshot: {
      maxDiffPixelRatio: 0.01,
      threshold: 0.2,
    },
  },

  projects: [
    {
      name: "dark",
      use: {
        ...devices["Desktop Chrome"],
        colorScheme: "dark",
      },
    },
  ],

  webServer: {
    command: "pnpm dev",
    url: "http://localhost:3000/login",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    stdout: "ignore",
    stderr: "pipe",
  },
})
