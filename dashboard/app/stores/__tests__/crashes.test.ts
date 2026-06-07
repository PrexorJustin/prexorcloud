import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useCrashesStore } from '../crashes'

const mockGET = vi.fn()
const mockPOST = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: mockPOST }),
}))

vi.mock('vue-sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
}))

describe('useCrashesStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    mockGET.mockReset()
    mockPOST.mockReset()
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  it('starts with empty crashes and not loading', () => {
    const store = useCrashesStore()
    expect(store.crashes).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchCrashes populates list on success', async () => {
    const mockCrashes = [
      { id: '1', instanceId: 'lobby-1', group: 'lobby', exitCode: 1 },
      { id: '2', instanceId: 'survival-1', group: 'survival', exitCode: 137 },
    ]
    mockGET.mockResolvedValueOnce({ data: { data: mockCrashes, total: 2 } })

    const store = useCrashesStore()
    await store.fetchCrashes()

    expect(store.crashes).toEqual(mockCrashes)
    expect(store.loading).toBe(false)
  })

  it('fetchCrashes shows error toast on failure', async () => {
    mockGET.mockRejectedValueOnce({ statusCode: 500 })

    const store = useCrashesStore()
    await store.fetchCrashes()

    expect(store.crashes).toEqual([])
    expect(toast.error).toHaveBeenCalledWith('Failed to load crashes')
  })

  it('fetchCrash returns single crash on success', async () => {
    const mockCrash = { id: '1', instanceId: 'lobby-1', group: 'lobby', exitCode: 1 }
    mockGET.mockResolvedValueOnce({ data: mockCrash })

    const store = useCrashesStore()
    const result = await store.fetchCrash('1')

    expect(result).toEqual(mockCrash)
  })

  it('fetchCrash returns null on failure', async () => {
    mockGET.mockRejectedValueOnce({ statusCode: 404 })

    const store = useCrashesStore()
    const result = await store.fetchCrash('nonexistent')

    expect(result).toBeNull()
  })

  it('shareCrash returns share result and toasts success', async () => {
    mockPOST.mockResolvedValueOnce({ data: { url: 'https://pste.dev/abc' } })

    const store = useCrashesStore()
    const result = await store.shareCrash('crash-1')

    expect(mockPOST).toHaveBeenCalledWith('/api/v1/crashes/crash-1/share', { body: {} })
    expect(result).toEqual({ url: 'https://pste.dev/abc' })
    expect(toast.success).toHaveBeenCalled()
  })

  it('shareCrash surfaces a friendly toast when sharing is disabled (409)', async () => {
    mockPOST.mockRejectedValueOnce({ response: { status: 409 } })

    const store = useCrashesStore()
    const result = await store.shareCrash('crash-1')

    expect(result).toBeNull()
    expect(toast.error).toHaveBeenCalled()
  })
})
