import { getAuthToken } from '~/lib/auth-storage'

interface CapabilityTarget { capability: string }

/**
 * Creates an API client scoped to a module's REST prefix.
 * All requests are automatically prefixed with /api/v1/modules/<moduleName>/
 *
 * Two forms:
 *   useScopedApi('player-journey')                        — direct module name; fast path
 *   useScopedApi({ capability: 'prexor.player.journey' }) — resolves at call time against
 *     /api/v1/modules/platform/capabilities so the client survives provider changes
 *     (capability migrating to a different module without a dashboard restart).
 *
 * Module endpoints are dynamic and not part of the OpenAPI spec, so this
 * uses raw fetch with the same auth token.
 */
export function useScopedApi(target: string | CapabilityTarget) {
  const config = useRuntimeConfig()
  const apiBase = config.public.apiBase as string

  function headers(hasBody = false): Record<string, string> {
    const h: Record<string, string> = {}
    const token = getAuthToken()
    if (token) h.Authorization = `Bearer ${token}`
    if (hasBody) h['Content-Type'] = 'application/json'
    return h
  }

  async function resolveModuleName(): Promise<string> {
    if (typeof target === 'string') return target
    const res = await fetch(`${apiBase}/api/v1/modules/platform/capabilities`, { headers: headers(false) })
    if (!res.ok) throw new Error(`capability lookup failed: ${res.status} ${res.statusText}`)
    const body = await res.json() as { bindings?: { capabilityId: string; moduleId: string }[] }
    const binding = (body.bindings ?? []).find((b) => b.capabilityId === target.capability)
    if (!binding) throw new Error(`capability '${target.capability}' has no active provider`)
    return binding.moduleId
  }

  async function request<T>(path: string, opts: RequestInit = {}): Promise<T> {
    const moduleName = await resolveModuleName()
    const url = `${apiBase}/api/v1/modules/${moduleName}${path}`
    const res = await fetch(url, { ...opts, headers: { ...headers(!!opts.body), ...(opts.headers as Record<string, string> ?? {}) } })
    if (!res.ok) throw new Error(`${res.url}: ${res.status} ${res.statusText}`)
    if (res.status === 204) return undefined as T
    return res.json() as Promise<T>
  }

  return {
    get: <T>(path: string) => request<T>(path, { method: 'GET' }),
    post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }),
    put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body: body ? JSON.stringify(body) : undefined }),
    del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  }
}
