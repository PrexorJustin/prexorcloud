import { installDevMock } from "~/lib/dev-mock"

/**
 * Install the dev-mock fetch + EventSource interceptors as early as possible
 * on the client. The plugin is a no-op in production builds because
 * `installDevMock` short-circuits unless `import.meta.env.DEV` is true.
 */
export default defineNuxtPlugin(() => {
  installDevMock()
})
