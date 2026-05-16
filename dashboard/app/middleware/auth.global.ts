import { getAuthToken } from '~/lib/auth-storage'

export default defineNuxtRouteMiddleware(async (to) => {
  if (to.path === '/login') return

  const token = getAuthToken()
  if (!token) return navigateTo({ path: '/login', query: to.path !== '/' ? { redirect: to.fullPath } : undefined })

  // Wait for user hydration so auth.user and permissions are available before rendering
  const auth = useAuthStore()
  await auth.ready
  if (!auth.user) return navigateTo({ path: '/login', query: to.path !== '/' ? { redirect: to.fullPath } : undefined })
})
