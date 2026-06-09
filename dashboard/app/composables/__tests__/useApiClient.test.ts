import { describe, it, expect, vi, beforeEach, beforeAll, afterEach } from 'vitest'
import type { Middleware } from '@prexorcloud/api-sdk'

import { useApiClient } from '../useApiClient'
import { lastTraceId } from '~/lib/trace-context'

const {
  mockGetAuthToken, mockSetAuthToken, mockClearAuthToken,
  fetchMock, createApiClientMock, clientUse,
} = vi.hoisted(() => {
  const clientUse = vi.fn()
  return {
    mockGetAuthToken: vi.fn(),
    mockSetAuthToken: vi.fn(),
    mockClearAuthToken: vi.fn(),
    fetchMock: vi.fn(),
    clientUse,
    createApiClientMock: vi.fn(() => ({ use: clientUse })),
  }
})

vi.mock('@prexorcloud/api-sdk', () => ({
  createApiClient: createApiClientMock,
}))

vi.mock('~/lib/auth-storage', () => ({
  AUTH_TOKEN_KEY: 'auth_token',
  getAuthToken: mockGetAuthToken,
  setAuthToken: mockSetAuthToken,
  clearAuthToken: mockClearAuthToken,
}))

vi.stubGlobal('useRuntimeConfig', () => ({ public: { apiBase: 'http://localhost:8080' } }))
vi.stubGlobal('fetch', fetchMock)

// useApiClient is a module-level singleton — capture its middlewares once.
let refreshMiddleware: Middleware
let traceMiddleware: Middleware
let throwMiddleware: Middleware

beforeAll(() => {
  useApiClient()
  const [first, second, third] = clientUse.mock.calls
  refreshMiddleware = first![0] as Middleware
  traceMiddleware = second![0] as Middleware
  throwMiddleware = third![0] as Middleware
})

beforeEach(() => {
  mockGetAuthToken.mockReset().mockReturnValue('tok')
  mockSetAuthToken.mockReset()
  mockClearAuthToken.mockReset()
  fetchMock.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

function ctx(response: Response, requestUrl = 'http://localhost:8080/api/v1/overview') {
  return {
    response,
    request: new Request(requestUrl, { headers: { Authorization: 'Bearer old' } }),
    options: {},
  } as unknown as Parameters<NonNullable<Middleware['onResponse']>>[0]
}

describe('useApiClient', () => {
  it('returns the same singleton instance on repeated calls', () => {
    expect(useApiClient()).toBe(useApiClient())
  })

  it('builds the client against the runtime apiBase with a token getter', () => {
    expect(createApiClientMock).toHaveBeenCalledWith({
      baseUrl: 'http://localhost:8080',
      getToken: mockGetAuthToken,
    })
  })

  it('registers exactly three middlewares (refresh, trace, throw-on-error)', () => {
    expect(clientUse).toHaveBeenCalledTimes(3)
  })

  describe('trace middleware', () => {
    it('records the X-Trace-Id header so the UI can deep-link to the trace', async () => {
      lastTraceId.value = ''
      const res = new Response('ok', { status: 200, headers: { 'X-Trace-Id': 'cafef00d' } })
      const out = await traceMiddleware.onResponse!(ctx(res))
      expect(out).toBe(res)
      expect(lastTraceId.value).toBe('cafef00d')
    })

    it('leaves the last trace id untouched when the header is absent', async () => {
      lastTraceId.value = 'previous'
      await traceMiddleware.onResponse!(ctx(new Response('ok', { status: 200 })))
      expect(lastTraceId.value).toBe('previous')
    })
  })

  describe('refresh middleware', () => {
    it('passes non-401 responses straight through', async () => {
      const res = new Response('ok', { status: 200 })
      const out = await refreshMiddleware.onResponse!(ctx(res))
      expect(out).toBe(res)
      expect(fetchMock).not.toHaveBeenCalled()
    })

    it('does not attempt a refresh for the /auth/login endpoint', async () => {
      const res = new Response('no', { status: 401 })
      const out = await refreshMiddleware.onResponse!(
        ctx(res, 'http://localhost:8080/api/v1/auth/login'),
      )
      expect(out).toBe(res)
      expect(fetchMock).not.toHaveBeenCalled()
    })

    it('does not attempt a refresh for the /auth/refresh endpoint', async () => {
      const res = new Response('no', { status: 401 })
      const out = await refreshMiddleware.onResponse!(
        ctx(res, 'http://localhost:8080/api/v1/auth/refresh'),
      )
      expect(out).toBe(res)
      expect(fetchMock).not.toHaveBeenCalled()
    })

    it('on 401 refreshes the token and retries the original request', async () => {
      fetchMock
        .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'fresh' }), { status: 200 }))
        .mockResolvedValueOnce(new Response('retried', { status: 200 }))

      const out = await refreshMiddleware.onResponse!(ctx(new Response('no', { status: 401 })))

      expect(mockSetAuthToken).toHaveBeenCalledWith('fresh')
      // First fetch: the refresh call. Second fetch: the retried request.
      expect(fetchMock).toHaveBeenCalledTimes(2)
      const retried = fetchMock.mock.calls[1]![0] as Request
      expect(retried.headers.get('Authorization')).toBe('Bearer fresh')
      expect(await (out as Response).text()).toBe('retried')
    })

    it('on a failed refresh clears the token and returns the original 401', async () => {
      vi.useFakeTimers()
      fetchMock.mockResolvedValueOnce(new Response('nope', { status: 401 }))
      const original = new Response('no', { status: 401 })

      const out = await refreshMiddleware.onResponse!(ctx(original))
      // `navigateTo` is a Nuxt auto-import we can't intercept here; the
      // observable logout effects are clearAuthToken() + the untouched 401.
      // Drain the 1s `redirecting`-guard reset timer before asserting so the
      // module flag doesn't leak into the next test.
      vi.advanceTimersByTime(1000)

      expect(mockClearAuthToken).toHaveBeenCalledTimes(1)
      expect(out).toBe(original)
    })

    it('skips the refresh attempt entirely when no token is stored', async () => {
      mockGetAuthToken.mockReturnValue(null)
      vi.useFakeTimers()
      const original = new Response('no', { status: 401 })

      const out = await refreshMiddleware.onResponse!(ctx(original))
      vi.advanceTimersByTime(1000)

      // tryRefresh bails before fetching; the 401 still triggers logout.
      expect(fetchMock).not.toHaveBeenCalled()
      expect(mockClearAuthToken).toHaveBeenCalled()
      expect(out).toBe(original)
    })

    it('while a logout is in flight (`redirecting`) further 401s pass straight through', async () => {
      // First 401 fails its refresh and flips the `redirecting` guard.
      vi.useFakeTimers()
      fetchMock.mockResolvedValueOnce(new Response('nope', { status: 401 }))
      await refreshMiddleware.onResponse!(ctx(new Response('no', { status: 401 })))
      mockClearAuthToken.mockClear()
      fetchMock.mockClear()

      // A second 401 arriving before the guard resets is a no-op.
      const second = new Response('still no', { status: 401 })
      const out = await refreshMiddleware.onResponse!(ctx(second))
      expect(out).toBe(second)
      expect(fetchMock).not.toHaveBeenCalled()
      expect(mockClearAuthToken).not.toHaveBeenCalled()

      // Drain the guard so the flag doesn't leak past this test.
      vi.advanceTimersByTime(1000)
    })
  })

  describe('throw-on-error middleware', () => {
    it('throws on a non-2xx response', async () => {
      const res = new Response('boom', { status: 500, statusText: 'Server Error' })
      await expect(throwMiddleware.onResponse!(ctx(res))).rejects.toThrow(/500/)
    })

    it('returns 2xx responses untouched', async () => {
      const res = new Response('ok', { status: 200 })
      const out = await throwMiddleware.onResponse!(ctx(res))
      expect(out).toBe(res)
    })
  })
})
