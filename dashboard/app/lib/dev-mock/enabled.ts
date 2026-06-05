/**
 * Dev-mock activation gate.
 *
 * True when:
 *   • `import.meta.env.DEV` — normal local `pnpm dev`, unchanged; OR
 *   • the build was explicitly opted in with `VITE_DEV_MOCK=1` — used *only* by
 *     the CI authed-flow axe a11y scan (E-P1.1), which builds the dashboard with
 *     the canned-data backend baked in so it can drive the app past login with no
 *     real controller.
 *
 * Release builds never set `VITE_DEV_MOCK`, so the whole dev-mock tree (fetch +
 * EventSource patches, fixtures) stays dead-code-eliminated in production.
 */
export const DEV_MOCK_ENABLED =
  import.meta.env.DEV || (import.meta.env as Record<string, string | undefined>).VITE_DEV_MOCK === "1"
