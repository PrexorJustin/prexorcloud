import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { installDevMock } from '../install'
import {
  DEV_MOCK_TOKEN, mockOverview, mockNodes, mockGroups, mockUser,
} from '../data'
import { AUTH_TOKEN_KEY } from '~/lib/auth-storage'

// installDevMock captures whatever globalThis.fetch is at install time as the
// passthrough target, so swap in a spy *before* installing.
const originalFetch = vi.fn(async () => new Response('passthrough', { status: 200 }))

beforeAll(() => {
  globalThis.fetch = originalFetch as unknown as typeof fetch
  installDevMock()
})

beforeEach(() => {
  originalFetch.mockClear()
  localStorage.clear()
})

function withDevToken() {
  localStorage.setItem(AUTH_TOKEN_KEY, DEV_MOCK_TOKEN)
}

describe('lib/dev-mock/install', () => {
  it('replaces globalThis.fetch with the dev-mock interceptor', () => {
    expect(globalThis.fetch).not.toBe(originalFetch)
    expect(globalThis.fetch.name).toBe('devMockFetch')
  })

  it('is idempotent — a second installDevMock() does not re-wrap fetch', () => {
    const patched = globalThis.fetch
    installDevMock()
    expect(globalThis.fetch).toBe(patched)
  })

  it('passes requests through to the original fetch when the dev token is absent', async () => {
    const res = await fetch('http://localhost:8080/api/v1/overview')
    expect(originalFetch).toHaveBeenCalledTimes(1)
    expect(await res.text()).toBe('passthrough')
  })

  it('passes non-API requests through even with the dev token set', async () => {
    withDevToken()
    await fetch('http://localhost:8080/some/asset.png')
    expect(originalFetch).toHaveBeenCalledTimes(1)
  })

  it('short-circuits a known GET route with canned JSON when the dev token is set', async () => {
    withDevToken()
    const res = await fetch('http://localhost:8080/api/v1/overview')
    expect(originalFetch).not.toHaveBeenCalled()
    expect(res.headers.get('Content-Type')).toBe('application/json')
    expect(await res.json()).toEqual(mockOverview)
  })

  it('serves /api/v1/auth/me from the mock user fixture', async () => {
    withDevToken()
    const res = await fetch('http://localhost:8080/api/v1/auth/me')
    expect(await res.json()).toEqual(mockUser)
  })

  it('echoes the dev token back from /api/v1/auth/refresh', async () => {
    withDevToken()
    const res = await fetch('http://localhost:8080/api/v1/auth/refresh', { method: 'POST' })
    expect(await res.json()).toEqual({ token: DEV_MOCK_TOKEN })
  })

  it('uses the posted username when handling /api/v1/auth/login', async () => {
    withDevToken()
    const res = await fetch('http://localhost:8080/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username: 'alice' }),
    })
    const body = await res.json()
    expect(body.token).toBe(DEV_MOCK_TOKEN)
    expect(body.user.username).toBe('alice')
  })

  it('returns the raw node/group fixtures for their list routes', async () => {
    withDevToken()
    const nodes = await (await fetch('http://localhost:8080/api/v1/nodes')).json()
    const groups = await (await fetch('http://localhost:8080/api/v1/groups')).json()
    expect(nodes).toEqual(mockNodes)
    expect(groups).toEqual(mockGroups)
  })

  it('honours page/pageSize query params on paginated routes', async () => {
    withDevToken()
    const res = await fetch('http://localhost:8080/api/v1/audit?page=2&pageSize=5')
    const body = await res.json()
    expect(body.page).toBe(2)
    expect(body.pageSize).toBe(5)
  })

  it('resolves a single node by id from the path', async () => {
    withDevToken()
    const first = mockNodes[0]!
    const res = await fetch(`http://localhost:8080/api/v1/nodes/${first.id}`)
    expect((await res.json()).id).toBe(first.id)
  })

  it('echoes a success-shaped 200 for unknown non-GET mutations', async () => {
    withDevToken()
    const res = await fetch('http://localhost:8080/api/v1/groups/foo/scale', { method: 'POST' })
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({})
  })

  it('returns an empty object for unknown GET routes', async () => {
    withDevToken()
    const res = await fetch('http://localhost:8080/api/v1/some/unknown/thing')
    expect(await res.json()).toEqual({})
  })
})
