import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useActivityStore } from '../activity'

const mockGET = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn(), PATCH: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

beforeEach(() => {
  setActivePinia(createPinia())
  mockGET.mockReset()
  vi.mocked(toast.error).mockReset()
})

describe('useActivityStore', () => {
  it('starts empty and idle', () => {
    const store = useActivityStore()
    expect(store.events).toEqual([])
    expect(store.loading).toBe(false)
    expect(store.offset).toBe(0)
    expect(store.hasMore).toBe(true)
    expect(store.pageSize).toBe(100)
  })

  it('fetchEvents(0) hits page 1 with the configured page size', async () => {
    mockGET.mockResolvedValue({ data: { data: [], total: 0 } })
    const store = useActivityStore()
    await store.fetchEvents()
    expect(mockGET).toHaveBeenCalledWith('/api/v1/events?page=1&pageSize=100')
  })

  it('fetchEvents derives page from offset', async () => {
    mockGET.mockResolvedValue({ data: { data: [], total: 0 } })
    const store = useActivityStore()
    await store.fetchEvents(200)
    expect(mockGET).toHaveBeenCalledWith('/api/v1/events?page=3&pageSize=100')
    expect(store.offset).toBe(200)
  })

  it('hasMore becomes false when page*size >= total', async () => {
    mockGET.mockResolvedValue({ data: { data: [{ id: '1', type: 'x', message: 'y', timestamp: '' }], total: 50 } })
    const store = useActivityStore()
    await store.fetchEvents(0)
    expect(store.hasMore).toBe(false)
    expect(store.events).toHaveLength(1)
  })

  it('hasMore stays true while more pages exist', async () => {
    mockGET.mockResolvedValue({
      data: { data: Array.from({ length: 100 }, (_, i) => ({ id: String(i), type: 'x', message: '', timestamp: '' })), total: 250 },
    })
    const store = useActivityStore()
    await store.fetchEvents(0)
    expect(store.hasMore).toBe(true)
  })

  it('fetchEvents tolerates missing total/data', async () => {
    mockGET.mockResolvedValue({ data: {} })
    const store = useActivityStore()
    await store.fetchEvents(0)
    expect(store.events).toEqual([])
    expect(store.hasMore).toBe(false)
  })

  it('failure toasts and clears loading', async () => {
    mockGET.mockRejectedValue(new Error('boom'))
    const store = useActivityStore()
    await store.fetchEvents()
    expect(store.loading).toBe(false)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })
})
