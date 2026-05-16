import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { useSystemStore } from '../system'

const mockGET = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: vi.fn(), PATCH: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

describe('useSystemStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGET.mockReset()
  })

  it('starts with empty state and not loading', () => {
    const store = useSystemStore()
    expect(store.health).toBeNull()
    expect(store.version).toBeNull()
    expect(store.diagnostics).toEqual([])
    expect(store.keyspace).toBeNull()
    expect(store.redisSchema).toEqual([])
    expect(store.settings).toEqual({})
    expect(store.loading).toBe(false)
  })

  it('fetchAll fans out across the six system endpoints', async () => {
    mockGET.mockImplementation((path: string) => {
      switch (path) {
        case '/api/v1/system/health':         return Promise.resolve({ data: { status: 'UP' } })
        case '/api/v1/system/version':        return Promise.resolve({ data: { version: '1.2.3', commit: 'abc' } })
        case '/api/v1/system/diagnostics':    return Promise.resolve({ data: { items: [{ id: 'd1', severity: 'info', message: 'ok' }] } })
        case '/api/v1/system/redis/keyspace': return Promise.resolve({ data: { keys: 42, expires: 5, avgTtl: 1000 } })
        case '/api/v1/system/redis/schema':   return Promise.resolve({ data: { entries: [{ prefix: 'instances:', count: 10 }] } })
        case '/api/v1/system/settings':       return Promise.resolve({ data: { theme: 'dark' } })
      }
      return Promise.reject(new Error('unexpected path: ' + path))
    })

    const store = useSystemStore()
    await store.fetchAll()

    expect(store.health).toEqual({ status: 'UP' })
    expect(store.version?.version).toBe('1.2.3')
    expect(store.diagnostics).toHaveLength(1)
    expect(store.keyspace?.keys).toBe(42)
    expect(store.redisSchema[0]?.prefix).toBe('instances:')
    expect(store.settings).toEqual({ theme: 'dark' })
    expect(store.loading).toBe(false)
    expect(mockGET).toHaveBeenCalledTimes(6)
  })

  it('keeps successful slices when one endpoint fails', async () => {
    // The store uses Promise.allSettled so a flaky endpoint should not
    // discard the responses that did succeed.
    mockGET.mockImplementation((path: string) => {
      if (path === '/api/v1/system/diagnostics') return Promise.reject(new Error('500'))
      if (path === '/api/v1/system/health')      return Promise.resolve({ data: { status: 'DEGRADED' } })
      return Promise.resolve({ data: null })
    })

    const store = useSystemStore()
    await store.fetchAll()

    expect(store.health).toEqual({ status: 'DEGRADED' })
    expect(store.diagnostics).toEqual([]) // unchanged from initial
    expect(store.loading).toBe(false)
  })

  it('tolerates missing items/entries wrappers', async () => {
    mockGET.mockResolvedValue({ data: {} })
    const store = useSystemStore()
    await store.fetchAll()
    expect(store.diagnostics).toEqual([])
    expect(store.redisSchema).toEqual([])
  })
})
