import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useOverviewStore } from '../overview'

const mockGET = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET }),
}))

vi.mock('vue-sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
}))

vi.mock('~/composables/useSseEventBus', () => ({
  useSseEventBus: () => ({
    on: vi.fn(),
    off: vi.fn(),
    connect: vi.fn(),
    disconnect: vi.fn(),
    connected: { value: false },
  }),
}))

describe('useOverviewStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    mockGET.mockReset()
  })

  it('starts with null stats and not loading', () => {
    const store = useOverviewStore()
    expect(store.stats).toBeNull()
    expect(store.loading).toBe(false)
  })

  it('fetchOverview sets stats on success', async () => {
    const mockStats = { nodeCount: 3, instanceCount: 10, playerCount: 42, groupCount: 5 }
    mockGET.mockResolvedValueOnce({ data: mockStats })

    const store = useOverviewStore()
    await store.fetchOverview()

    expect(store.stats).toEqual(mockStats)
    expect(store.loading).toBe(false)
  })

  it('fetchOverview shows error toast on failure', async () => {
    mockGET.mockRejectedValueOnce({ statusCode: 500 })

    const store = useOverviewStore()
    await store.fetchOverview()

    expect(store.stats).toBeNull()
    expect(store.loading).toBe(false)
    expect(toast.error).toHaveBeenCalledWith('Failed to load overview')
  })

  it('sets loading=true during fetch', async () => {
    // fetchOverview makes two GETs on the first run: the main /overview
    // call and the /overview/timeseries seed for the history ring buffer.
    // Hold the first GET open so we can observe loading=true, then let it
    // resolve and stub the seed call so the action can complete.
    let resolveOverview!: (v: any) => void
    mockGET.mockImplementationOnce(() => new Promise(r => { resolveOverview = r }))
    mockGET.mockResolvedValueOnce({ data: { series: {} } })

    const store = useOverviewStore()
    const promise = store.fetchOverview()
    expect(store.loading).toBe(true)

    resolveOverview({ data: { nodeCount: 1 } })
    await promise
    expect(store.loading).toBe(false)
  })
})
