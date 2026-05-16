import { createApiClient, type ApiClient, type Middleware } from '@prexorcloud/api-sdk'
import { clearAuthToken, getAuthToken, setAuthToken } from '~/lib/auth-storage'

let redirecting = false
let refreshPromise: Promise<string | null> | null = null

let _client: ApiClient | null = null

/**
 * Returns a singleton typed API client with JWT auth + automatic token refresh.
 * Replaces the old useApi() composable.
 */
export function useApiClient(): ApiClient {
  if (_client) return _client

  const config = useRuntimeConfig()
  const apiBase = config.public.apiBase as string

  const client = createApiClient({ baseUrl: apiBase, getToken: getAuthToken })

  // Middleware: handle 401 → try refresh → retry or logout
  const refreshMiddleware: Middleware = {
    async onResponse({ response, request, options }) {
      if (response.status !== 401 || redirecting) return response

      const url = new URL(request.url)
      if (url.pathname.endsWith('/auth/login') || url.pathname.endsWith('/auth/refresh')) {
        return response
      }

      const newToken = await tryRefresh(apiBase)
      if (newToken) {
        // Retry original request with new token
        const retryReq = new Request(request, {
          headers: new Headers(request.headers),
        })
        retryReq.headers.set('Authorization', `Bearer ${newToken}`)
        return fetch(retryReq)
      }

      // Refresh failed — logout
      redirecting = true
      clearAuthToken()
      navigateTo('/login')
      setTimeout(() => { redirecting = false }, 1000)
      return response
    },
  }

  // Middleware: throw on non-2xx so existing try/catch patterns work
  const throwOnErrorMiddleware: Middleware = {
    async onResponse({ response }) {
      if (!response.ok) {
        throw new Error(`${response.url}: ${response.status} ${response.statusText}`)
      }
      return response
    },
  }

  client.use(refreshMiddleware)
  client.use(throwOnErrorMiddleware)
  _client = client
  return client
}

async function tryRefresh(apiBase: string): Promise<string | null> {
  if (refreshPromise) return refreshPromise

  refreshPromise = (async () => {
    try {
      const token = getAuthToken()
      if (!token) return null

      const res = await fetch(`${apiBase}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) return null

      const data = await res.json()
      if (data.token) {
        setAuthToken(data.token)
        return data.token as string
      }
      return null
    } catch {
      return null
    } finally {
      refreshPromise = null
    }
  })()

  return refreshPromise
}
