import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useNetworksStore } from '../networks'

const mockGET = vi.fn()
const mockPOST = vi.fn()
const mockPUT = vi.fn()
const mockDELETE = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: mockPOST, PUT: mockPUT, DELETE: mockDELETE, PATCH: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

describe('useNetworksStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGET.mockReset(); mockPOST.mockReset(); mockPUT.mockReset(); mockDELETE.mockReset()
    vi.mocked(toast.success).mockReset()
    vi.mocked(toast.error).mockReset()
  })

  it('starts with empty networks and not loading', () => {
    const store = useNetworksStore()
    expect(store.networks).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchNetworks populates networks on success', async () => {
    const items = [
      { name: 'main', lobbyGroup: 'lobby', fallbackGroups: ['fallback'] },
      { name: 'creative', lobbyGroup: 'creative_lobby' },
    ]
    mockGET.mockResolvedValueOnce({ data: { data: items, total: items.length } })

    const store = useNetworksStore()
    await store.fetchNetworks()

    expect(store.networks).toEqual(items)
    expect(store.loading).toBe(false)
  })

  it('fetchNetworks toasts error on failure', async () => {
    mockGET.mockRejectedValueOnce(new Error('500'))
    const store = useNetworksStore()
    await store.fetchNetworks()
    expect(store.networks).toEqual([])
    expect(toast.error).toHaveBeenCalledWith('Failed to load networks')
  })

  it('createNetwork posts and refetches', async () => {
    mockPOST.mockResolvedValueOnce({ data: {} })
    mockGET.mockResolvedValueOnce({ data: { data: [{ name: 'main', lobbyGroup: 'lobby' }] } })

    const store = useNetworksStore()
    await store.createNetwork({ name: 'main', lobbyGroup: 'lobby' })

    expect(mockPOST).toHaveBeenCalled()
    expect(toast.success).toHaveBeenCalledWith('Network created', { description: '"main" has been created' })
    expect(mockGET).toHaveBeenCalled()
  })

  it('updateNetwork puts and refetches', async () => {
    mockPUT.mockResolvedValueOnce({ data: {} })
    mockGET.mockResolvedValueOnce({ data: { data: [] } })

    const store = useNetworksStore()
    await store.updateNetwork('main', { name: 'main', lobbyGroup: 'lobby2' })

    expect(mockPUT).toHaveBeenCalledWith(
      '/api/v1/networks/{name}',
      expect.objectContaining({ params: { path: { name: 'main' } } }),
    )
    expect(toast.success).toHaveBeenCalledWith('Network updated', { description: '"main" has been updated' })
  })

  it('deleteNetwork calls DELETE and refetches', async () => {
    mockDELETE.mockResolvedValueOnce({ data: {} })
    mockGET.mockResolvedValueOnce({ data: { data: [] } })

    const store = useNetworksStore()
    await store.deleteNetwork('main')

    expect(mockDELETE).toHaveBeenCalledWith('/api/v1/networks/{name}', { params: { path: { name: 'main' } } })
    expect(toast.success).toHaveBeenCalledWith('Network deleted', { description: '"main" has been deleted' })
  })
})
