import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { usePlayersStore } from '../players'

const mockGET = vi.fn()
const mockPOST = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: mockPOST, DELETE: vi.fn(), PUT: vi.fn(), PATCH: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

beforeEach(() => {
  setActivePinia(createPinia())
  mockGET.mockReset()
  mockPOST.mockReset()
  vi.mocked(toast.success).mockReset()
  vi.mocked(toast.error).mockReset()
})

describe('usePlayersStore', () => {
  it('starts empty', () => {
    const store = usePlayersStore()
    expect(store.players).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchPlayers populates the list', async () => {
    mockGET.mockResolvedValue({
      data: { data: [{ id: 'p1', uuid: 'u1', username: 'alice' }], total: 1, page: 1, pageSize: 500 },
    })
    const store = usePlayersStore()
    await store.fetchPlayers()
    expect(store.players[0]!.username).toBe('alice')
    expect(mockGET).toHaveBeenCalledWith('/api/v1/players?page=1&pageSize=500')
    expect(store.total).toBe(1)
  })

  it('fetchPlayers toasts and clears loading on failure', async () => {
    mockGET.mockRejectedValue(new Error('boom'))
    const store = usePlayersStore()
    await store.fetchPlayers()
    expect(store.loading).toBe(false)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('fetchJourney returns entries and URL-encodes the id', async () => {
    mockGET.mockResolvedValue({
      data: { data: [{ ts: '2026-01-01', type: 'connected' }] },
    })
    const store = usePlayersStore()
    const entries = await store.fetchJourney('id with spaces')
    expect(entries[0]!.type).toBe('connected')
    expect(mockGET).toHaveBeenCalledWith('/api/v1/players/id%20with%20spaces/journey')
  })

  it('fetchJourney returns [] and toasts on failure', async () => {
    mockGET.mockRejectedValue(new Error('boom'))
    const store = usePlayersStore()
    const entries = await store.fetchJourney('id')
    expect(entries).toEqual([])
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('transfer POSTs to the encoded id and refetches', async () => {
    mockPOST.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { data: [], total: 0, page: 1, pageSize: 500 } })
    const store = usePlayersStore()
    await store.transfer('p/1', 'lobby-1')
    expect(mockPOST).toHaveBeenCalledWith('/api/v1/players/p%2F1/transfer', { body: { instanceId: 'lobby-1' } })
    expect(mockGET).toHaveBeenCalledWith('/api/v1/players?page=1&pageSize=500')
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it('transfer rethrows a typed error and skips refetch on failure', async () => {
    mockPOST.mockRejectedValue(new Error('boom'))
    const store = usePlayersStore()
    await expect(store.transfer('p1', 'lobby-1')).rejects.toThrow('transfer-player')
    expect(mockGET).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledTimes(1)
  })
})
