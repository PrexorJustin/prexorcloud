import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useGroupsStore } from '../groups'

const mockGET = vi.fn()
const mockPOST = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: mockPOST, PATCH: vi.fn(), DELETE: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
}))

const sseHandlers: Array<(event: any) => void> = []
vi.mock('~/composables/useSseEventBus', () => ({
  useSseEventBus: () => ({
    on: vi.fn((_types: string[], handler: (event: any) => void) => {
      sseHandlers.push(handler)
    }),
    off: vi.fn(),
    connect: vi.fn(),
    disconnect: vi.fn(),
    connected: { value: false },
  }),
}))

describe('useGroupsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGET.mockReset()
    mockPOST.mockReset()
    sseHandlers.length = 0
    vi.mocked(toast.success).mockReset()
    vi.mocked(toast.error).mockReset()
  })

  it('starts with empty groups and not loading', () => {
    const store = useGroupsStore()
    expect(store.groups).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchGroups populates groups on success', async () => {
    const mockGroups = [
      { name: 'lobby', platform: 'PAPER', minInstances: 1, maxInstances: 5 },
      { name: 'bedwars', platform: 'PAPER', minInstances: 0, maxInstances: 10 },
    ]
    mockGET.mockResolvedValueOnce({ data: { data: mockGroups } })

    const store = useGroupsStore()
    await store.fetchGroups()

    expect(store.groups).toEqual(mockGroups)
    expect(store.loading).toBe(false)
  })

  it('fetchGroups shows error toast on failure', async () => {
    mockGET.mockRejectedValueOnce(new Error('500'))

    const store = useGroupsStore()
    await store.fetchGroups()

    expect(store.groups).toEqual([])
    expect(toast.error).toHaveBeenCalledWith('Failed to load groups')
  })

  it('sets loading=true during fetch', async () => {
    let resolvePromise: (v: any) => void
    mockGET.mockImplementation(() => new Promise(r => { resolvePromise = r }))

    const store = useGroupsStore()
    const promise = store.fetchGroups()
    expect(store.loading).toBe(true)

    resolvePromise!({ data: { data: [] } })
    await promise
    expect(store.loading).toBe(false)
  })

  it('createGroup calls API and refetches', async () => {
    mockPOST.mockResolvedValueOnce({ data: {} })
    mockGET.mockResolvedValueOnce({ data: { data: [{ name: 'lobby' }] } })

    const store = useGroupsStore()
    await store.createGroup({ name: 'lobby' })

    expect(mockPOST).toHaveBeenCalled()
    expect(toast.success).toHaveBeenCalledWith('Group created', { description: '"lobby" has been created' })
    expect(mockGET).toHaveBeenCalled()
  })

  it('lastDeploymentEvent starts as null', () => {
    const store = useGroupsStore()
    expect(store.lastDeploymentEvent).toBeNull()
  })

  it('GROUP_AGGREGATES_UPDATED patches running/totalPlayers in place without refetching', async () => {
    mockGET.mockResolvedValueOnce({ data: { data: [
      { name: 'lobby', platform: 'PAPER', minInstances: 1, maxInstances: 5, runningInstances: 2, totalPlayers: 10 },
      { name: 'bedwars', platform: 'PAPER', minInstances: 0, maxInstances: 10, runningInstances: 0, totalPlayers: 0 },
    ] } })

    const store = useGroupsStore()
    await store.fetchGroups()
    store.connectSse()
    expect(sseHandlers.length).toBeGreaterThan(0)

    sseHandlers[0]!({
      type: 'GROUP_AGGREGATES_UPDATED',
      groupName: 'lobby',
      runningInstances: 3,
      totalPlayers: 17,
      timestamp: '2026-05-11T19:00:00Z',
    })

    const lobby = store.groups.find(g => g.name === 'lobby')!
    expect(lobby.runningInstances).toBe(3)
    expect(lobby.totalPlayers).toBe(17)
    // Other config fields should be preserved.
    expect(lobby.maxInstances).toBe(5)
    // Only one fetch — patch did not trigger a re-fetch.
    expect(mockGET).toHaveBeenCalledTimes(1)
  })

  it('GROUP_AGGREGATES_UPDATED for unknown group is a no-op', async () => {
    mockGET.mockResolvedValueOnce({ data: { data: [
      { name: 'lobby', platform: 'PAPER', runningInstances: 2, totalPlayers: 10 },
    ] } })

    const store = useGroupsStore()
    await store.fetchGroups()
    store.connectSse()

    sseHandlers[0]!({
      type: 'GROUP_AGGREGATES_UPDATED',
      groupName: 'nonexistent',
      runningInstances: 99,
      totalPlayers: 99,
      timestamp: '2026-05-11T19:00:00Z',
    })

    expect(store.groups).toHaveLength(1)
    expect(store.groups[0]!.runningInstances).toBe(2)
  })
})
