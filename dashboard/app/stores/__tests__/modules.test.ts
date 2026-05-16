import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { useModuleStore } from '../modules'

const { mockGetAuthToken, mockBusOn, mockBusOff, mockBusConnect, fetchMock } = vi.hoisted(() => ({
  mockGetAuthToken: vi.fn(),
  mockBusOn: vi.fn(),
  mockBusOff: vi.fn(),
  mockBusConnect: vi.fn(),
  fetchMock: vi.fn(),
}))

vi.mock('~/lib/auth-storage', () => ({
  AUTH_TOKEN_KEY: 'auth_token',
  getAuthToken: mockGetAuthToken,
  setAuthToken: vi.fn(),
  clearAuthToken: vi.fn(),
}))

vi.mock('~/composables/useSseEventBus', () => ({
  useSseEventBus: () => ({ on: mockBusOn, off: mockBusOff, connect: mockBusConnect }),
}))

const mockGET = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: vi.fn(), PUT: vi.fn(), PATCH: vi.fn(), DELETE: vi.fn() }),
}))

vi.stubGlobal('useRuntimeConfig', () => ({
  public: { apiBase: 'http://localhost:8080' },
}))
vi.stubGlobal('fetch', fetchMock)

function jsonResponse(body: unknown, init: Partial<ResponseInit> = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
    ...init,
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockGET.mockReset()
  fetchMock.mockReset()
  mockGetAuthToken.mockReset()
  mockGetAuthToken.mockReturnValue('tok')
  mockBusOn.mockReset()
  mockBusOff.mockReset()
  mockBusConnect.mockReset()
  // Clear any DOM <link> leftovers from a previous test
  document.head.querySelectorAll('link[data-module]').forEach((el) => el.remove())
})

describe('useModuleStore', () => {
  it('starts with empty registry and no error', () => {
    const store = useModuleStore()
    expect(store.modules).toEqual([])
    expect(store.platformModules).toEqual([])
    expect(store.capabilityGraph).toBeNull()
    expect(store.platformExtensions).toEqual([])
    expect(store.resolvedExtensions).toEqual([])
    expect(store.platformError).toBeNull()
  })

  it('fetchRegistry populates modules from /api/v1/modules', async () => {
    mockGET.mockResolvedValue({ data: { data: [{ name: 'm1', frontend: null }] } })
    const store = useModuleStore()
    await store.fetchRegistry()
    expect(store.modules).toHaveLength(1)
    expect(mockGET).toHaveBeenCalledWith('/api/v1/modules')
  })

  it('fetchRegistry resets to [] on failure (silent)', async () => {
    mockGET.mockRejectedValue(new Error('boom'))
    const store = useModuleStore()
    // @ts-expect-error — seed prior state
    store.modules = [{ name: 'old', frontend: null }]
    await store.fetchRegistry()
    expect(store.modules).toEqual([])
  })

  it('fetchPlatformOverview hits /modules/platform via raw fetch with auth header', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ modules: [{ moduleId: 'core', state: 'ACTIVE' }] }))
    const store = useModuleStore()
    await store.fetchPlatformOverview()
    expect(store.platformModules).toEqual([{ moduleId: 'core', state: 'ACTIVE' }])
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('http://localhost:8080/api/v1/modules/platform')
    const headers = (init as RequestInit).headers as Headers
    expect(headers.get('Authorization')).toBe('Bearer tok')
  })

  it('fetchPlatformOverview surfaces server error message into platformError', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ error: { message: 'bad day' } }), {
        status: 500,
        headers: { 'content-type': 'application/json' },
      }),
    )
    const store = useModuleStore()
    await store.fetchPlatformOverview()
    expect(store.platformModules).toEqual([])
    expect(store.platformError).toBe('bad day')
  })

  it('fetchPlatformOverview falls back to status+statusText when no JSON body', async () => {
    fetchMock.mockResolvedValueOnce(new Response('plaintext', { status: 503, statusText: 'Unavailable' }))
    const store = useModuleStore()
    await store.fetchPlatformOverview()
    expect(store.platformError).toBe('503 Unavailable')
  })

  it('fetchCapabilityGraph populates capabilityGraph', async () => {
    const graph = { capabilities: [{ id: 'prexor.foo', providers: ['m1'] }] }
    fetchMock.mockResolvedValueOnce(jsonResponse(graph))
    const store = useModuleStore()
    await store.fetchCapabilityGraph()
    expect(store.capabilityGraph).toEqual(graph)
    expect((fetchMock.mock.calls[0]![0] as string)).toBe('http://localhost:8080/api/v1/modules/platform/capabilities')
  })

  it('fetchCapabilityGraph nulls the value on error', async () => {
    fetchMock.mockResolvedValueOnce(new Response('', { status: 500, statusText: 'Err' }))
    const store = useModuleStore()
    // @ts-expect-error
    store.capabilityGraph = { capabilities: [] }
    await store.fetchCapabilityGraph()
    expect(store.capabilityGraph).toBeNull()
    expect(store.platformError).toBe('500 Err')
  })

  it('fetchPlatformExtensions builds ?target=… when given', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ extensions: [] }))
    const store = useModuleStore()
    await store.fetchPlatformExtensions('paper:1.21')
    expect(fetchMock.mock.calls[0]![0]).toBe('http://localhost:8080/api/v1/modules/platform/extensions?target=paper%3A1.21')
  })

  it('fetchPlatformExtensions omits ?target= when no target is given', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ extensions: [{ id: 'e1' }] }))
    const store = useModuleStore()
    await store.fetchPlatformExtensions()
    expect(fetchMock.mock.calls[0]![0]).toBe('http://localhost:8080/api/v1/modules/platform/extensions')
    expect(store.platformExtensions).toHaveLength(1)
  })

  it('resolvePlatformExtensions builds target + version + repeated extensionId params', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ resolved: [{ id: 'e1' }] }))
    const store = useModuleStore()
    await store.resolvePlatformExtensions('paper', '1.21', ['e1', 'e2'])
    const url = fetchMock.mock.calls[0]![0] as string
    expect(url).toMatch(/^http:\/\/localhost:8080\/api\/v1\/modules\/platform\/extensions\/resolve\?/)
    expect(url).toMatch(/target=paper/)
    expect(url).toMatch(/version=1\.21/)
    expect(url).toMatch(/extensionId=e1/)
    expect(url).toMatch(/extensionId=e2/)
    expect(store.resolvedExtensions).toEqual([{ id: 'e1' }])
  })

  it('resolvePlatformExtensions resets to [] and records the error on failure', async () => {
    fetchMock.mockResolvedValueOnce(new Response('', { status: 500, statusText: 'Err' }))
    const store = useModuleStore()
    // @ts-expect-error
    store.resolvedExtensions = [{ id: 'leftover' }]
    await store.resolvePlatformExtensions('paper', '1.21')
    expect(store.resolvedExtensions).toEqual([])
    expect(store.platformError).toBe('500 Err')
  })

  it('refreshPlatformState fans out across all four endpoints', async () => {
    mockGET.mockResolvedValue({ data: { data: [] } })
    fetchMock.mockResolvedValue(jsonResponse({ modules: [], capabilities: [], extensions: [] }))
    const store = useModuleStore()
    await store.refreshPlatformState()
    expect(mockGET).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledTimes(3) // overview + graph + extensions
  })

  it('uninstallPlatformModule DELETEs the encoded moduleId and refreshes', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 })) // DELETE
    fetchMock.mockResolvedValue(jsonResponse({})) // refresh fetches
    mockGET.mockResolvedValue({ data: { data: [] } })

    const store = useModuleStore()
    await store.uninstallPlatformModule('mod with space')
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('http://localhost:8080/api/v1/modules/platform/mod%20with%20space')
    expect((init as RequestInit).method).toBe('DELETE')
  })

  it('modulesWithFrontend filters out registry entries with frontend === null', () => {
    const store = useModuleStore()
    // @ts-expect-error — seed Pinia state directly
    store.modules = [
      { name: 'with', frontend: { entry: '/mod.js', routes: [{ path: '/', component: 'X' }] } },
      { name: 'without', frontend: null },
    ]
    expect(store.modulesWithFrontend.map((m) => m.name)).toEqual(['with'])
  })

  it('frontendByModuleId indexes every module entry by its name', () => {
    const store = useModuleStore()
    // @ts-expect-error
    store.modules = [
      { name: 'a', frontend: null },
      { name: 'b', frontend: null },
    ]
    expect(store.frontendByModuleId.get('a')?.name).toBe('a')
    expect(store.frontendByModuleId.get('b')?.name).toBe('b')
    expect(store.frontendByModuleId.get('missing')).toBeUndefined()
  })

  it('resolveRoute matches the longest module name prefix and route path', () => {
    const store = useModuleStore()
    // @ts-expect-error
    store.modules = [
      {
        name: 'announcements',
        frontend: {
          entry: '/a.js',
          routes: [
            { path: '/', component: 'AnnouncementsPage' },
            { path: '/:id', component: 'AnnouncementsDetail' },
          ],
        },
      },
    ]
    expect(store.resolveRoute('announcements')).toEqual({
      moduleName: 'announcements',
      componentName: 'AnnouncementsPage',
    })
    expect(store.resolveRoute('announcements/42')).toEqual({
      moduleName: 'announcements',
      componentName: 'AnnouncementsDetail',
    })
    expect(store.resolveRoute('other')).toBeNull()
    expect(store.resolveRoute('announcements/extra/segment')).toBeNull()
  })

  it('invalidate removes any injected <link data-module> stylesheet for the module', () => {
    const link = document.createElement('link')
    link.rel = 'stylesheet'
    link.dataset.module = 'm1'
    document.head.appendChild(link)
    expect(document.querySelector('link[data-module="m1"]')).not.toBeNull()

    const store = useModuleStore()
    store.invalidate('m1')
    expect(document.querySelector('link[data-module="m1"]')).toBeNull()
  })

  it('connectSse subscribes once and is idempotent', () => {
    const store = useModuleStore()
    store.connectSse()
    store.connectSse()
    expect(mockBusOn).toHaveBeenCalledTimes(1)
    expect(mockBusOn).toHaveBeenCalledWith(
      ['MODULE_LOADED', 'MODULE_UNLOADED', 'MODULE_FRONTEND_RELOADED', 'RESYNC_REQUIRED'],
      expect.any(Function),
    )
    expect(mockBusConnect).toHaveBeenCalledTimes(1)
  })

  it('disconnectSse only fires after a prior connect', () => {
    const store = useModuleStore()
    store.disconnectSse()
    expect(mockBusOff).not.toHaveBeenCalled()
    store.connectSse()
    store.disconnectSse()
    expect(mockBusOff).toHaveBeenCalledTimes(1)
  })

  it('SSE handler refreshes platform state on RESYNC_REQUIRED', async () => {
    mockGET.mockResolvedValue({ data: { data: [] } })
    fetchMock.mockResolvedValue(jsonResponse({}))

    const store = useModuleStore()
    store.connectSse()
    const handler = mockBusOn.mock.calls[0]![1] as (e: unknown) => void
    handler({ type: 'RESYNC_REQUIRED' })
    // give microtasks a chance
    await Promise.resolve()
    await Promise.resolve()
    expect(mockGET).toHaveBeenCalled()
  })

  it('SSE handler invalidates the affected module on MODULE_UNLOADED', () => {
    const link = document.createElement('link')
    link.rel = 'stylesheet'
    link.dataset.module = 'gone'
    document.head.appendChild(link)

    const store = useModuleStore()
    store.connectSse()
    const handler = mockBusOn.mock.calls[0]![1] as (e: unknown) => void
    handler({ type: 'MODULE_UNLOADED', moduleName: 'gone' })

    expect(document.querySelector('link[data-module="gone"]')).toBeNull()
  })
})
