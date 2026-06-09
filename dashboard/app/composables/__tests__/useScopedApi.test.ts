import { describe, it, expect, vi, beforeEach } from 'vitest'

import { useScopedApi } from '../useScopedApi'

const { mockGetAuthToken, fetchMock } = vi.hoisted(() => ({
  mockGetAuthToken: vi.fn(),
  fetchMock: vi.fn(),
}))

vi.mock('~/lib/auth-storage', () => ({
  AUTH_TOKEN_KEY: 'auth_token',
  getAuthToken: mockGetAuthToken,
  setAuthToken: vi.fn(),
  clearAuthToken: vi.fn(),
}))

vi.stubGlobal('useRuntimeConfig', () => ({
  public: { apiBase: 'http://localhost:8080' },
}))

vi.stubGlobal('fetch', fetchMock)

beforeEach(() => {
  mockGetAuthToken.mockReset()
  mockGetAuthToken.mockReturnValue('tok')
  fetchMock.mockReset()
})

function jsonResponse(body: unknown, init: Partial<ResponseInit> = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
    ...init,
  })
}

describe('useScopedApi', () => {
  describe('with a literal module name', () => {
    it('prefixes /api/v1/modules/<name>/ for GET and forwards Authorization', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }))
      const api = useScopedApi('player-journey')
      const out = await api.get<{ ok: boolean }>('/players')
      expect(out).toEqual({ ok: true })
      const [url, init] = fetchMock.mock.calls[0]!
      expect(url).toBe('http://localhost:8080/api/v1/modules/player-journey/players')
      expect((init as RequestInit).method).toBe('GET')
      expect((init as RequestInit).headers).toMatchObject({ Authorization: 'Bearer tok' })
    })

    it('omits Content-Type on GET but sets it on POST with a body', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}))
      await useScopedApi('m').post('/x', { a: 1 })
      const [, init] = fetchMock.mock.calls[0]!
      expect((init as RequestInit).method).toBe('POST')
      expect((init as RequestInit).body).toBe(JSON.stringify({ a: 1 }))
      expect((init as RequestInit).headers).toMatchObject({ 'Content-Type': 'application/json' })
    })

    it('skips the Authorization header when no token is stored', async () => {
      mockGetAuthToken.mockReturnValue(null)
      fetchMock.mockResolvedValueOnce(jsonResponse({}))
      await useScopedApi('m').get('/x')
      const [, init] = fetchMock.mock.calls[0]!
      const headers = (init as RequestInit).headers as Record<string, string>
      expect(headers.Authorization).toBeUndefined()
    })

    it('PUT sends a stringified body', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({}))
      await useScopedApi('m').put('/x', { y: 2 })
      const [, init] = fetchMock.mock.calls[0]!
      expect((init as RequestInit).method).toBe('PUT')
      expect((init as RequestInit).body).toBe(JSON.stringify({ y: 2 }))
    })

    it('DELETE has no body and returns undefined on 204', async () => {
      fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
      const r = await useScopedApi('m').del('/x')
      expect(r).toBeUndefined()
      const [, init] = fetchMock.mock.calls[0]!
      expect((init as RequestInit).method).toBe('DELETE')
      expect((init as RequestInit).body).toBeUndefined()
    })

    it('throws with status + statusText on non-2xx', async () => {
      fetchMock.mockResolvedValueOnce(new Response('nope', { status: 500, statusText: 'Server Error' }))
      await expect(useScopedApi('m').get('/x')).rejects.toThrow(/500/)
    })
  })

  describe('with a capability target', () => {
    it('resolves moduleId via /capabilities then issues the scoped request', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({
        bindings: [
          { capabilityId: 'prexor.foo', moduleId: 'foo-impl-v2' },
          { capabilityId: 'prexor.bar', moduleId: 'bar' },
        ],
      }))
      fetchMock.mockResolvedValueOnce(jsonResponse({ hit: true }))

      const api = useScopedApi({ capability: 'prexor.foo' })
      const out = await api.get<{ hit: boolean }>('/data')

      expect(fetchMock).toHaveBeenCalledTimes(2)
      expect(fetchMock.mock.calls[0]![0]).toBe('http://localhost:8080/api/v1/modules/platform/capabilities')
      expect(fetchMock.mock.calls[1]![0]).toBe('http://localhost:8080/api/v1/modules/foo-impl-v2/data')
      expect(out).toEqual({ hit: true })
    })

    it('throws when no binding is registered for the capability', async () => {
      fetchMock.mockResolvedValueOnce(jsonResponse({ bindings: [] }))
      const api = useScopedApi({ capability: 'prexor.missing' })
      await expect(api.get('/x')).rejects.toThrow(/has no active provider/)
    })

    it('throws when the /capabilities lookup itself fails', async () => {
      fetchMock.mockResolvedValueOnce(new Response('nope', { status: 503, statusText: 'Unavailable' }))
      const api = useScopedApi({ capability: 'prexor.foo' })
      await expect(api.get('/x')).rejects.toThrow(/capability lookup failed/)
    })
  })
})
