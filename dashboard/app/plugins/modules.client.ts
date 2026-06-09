import { getAuthToken } from '~/lib/auth-storage'

/**
 * Initializes the module registry on app load (client-side only).
 * Fetches the list of installed modules and starts SSE for live updates.
 */
export default defineNuxtPlugin(async () => {
  const token = getAuthToken()
  if (!token) return

  const moduleStore = useModuleStore()
  await moduleStore.refreshPlatformState()
  moduleStore.connectSse()
})
