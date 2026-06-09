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
// `__DEV_MOCK__` is inlined by the Vite `define` in nuxt.config (true when the
// build ran with VITE_DEV_MOCK=1, false otherwise). The `typeof` guard keeps
// this safe under vitest, where the define is absent and the global is truly
// undefined.
declare const __DEV_MOCK__: boolean | undefined

export const DEV_MOCK_ENABLED =
  import.meta.env.DEV || (typeof __DEV_MOCK__ !== "undefined" && __DEV_MOCK__ === true)
