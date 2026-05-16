import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { useDeploymentsAggregateStore } from '../deploymentsAggregate'
import { useGroupsStore } from '../groups'

const mockGET = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: vi.fn(), DELETE: vi.fn(), PUT: vi.fn(), PATCH: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

function dep(id: string, group: string, createdAt: string) {
  return {
    id,
    name: `${group}-${id}`,
    groupName: group,
    instanceId: `${group}-i${id}`,
    state: 'RUNNING',
    createdAt,
  } as unknown as Record<string, unknown>
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockGET.mockReset()
})

describe('useDeploymentsAggregateStore', () => {
  it('starts empty and idle', () => {
    const store = useDeploymentsAggregateStore()
    expect(store.deployments).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchAll fans out per group and tags entries with groupName', async () => {
    const groupsStore = useGroupsStore()
    // @ts-expect-error — seed Pinia state directly for test isolation
    groupsStore.groups = [{ name: 'lobby' }, { name: 'survival' }]

    mockGET.mockImplementation((path: string) => {
      if (path.includes('/lobby/')) {
        return Promise.resolve({ data: { data: [dep('1', 'lobby', '2026-05-14T10:00:00Z')] } })
      }
      if (path.includes('/survival/')) {
        return Promise.resolve({ data: { data: [dep('2', 'survival', '2026-05-14T11:00:00Z')] } })
      }
      return Promise.reject(new Error('unexpected: ' + path))
    })

    const store = useDeploymentsAggregateStore()
    await store.fetchAll()

    expect(store.deployments).toHaveLength(2)
    // Sorted newest-first → survival entry comes first.
    expect(store.deployments[0]!.groupName).toBe('survival')
    expect(store.deployments[1]!.groupName).toBe('lobby')
    expect(store.loading).toBe(false)
  })

  it('Promise.allSettled keeps fulfilled groups when one fails', async () => {
    const groupsStore = useGroupsStore()
    // @ts-expect-error — seed Pinia state directly
    groupsStore.groups = [{ name: 'lobby' }, { name: 'broken' }]

    mockGET.mockImplementation((path: string) => {
      if (path.includes('/broken/')) return Promise.reject(new Error('500'))
      return Promise.resolve({ data: { data: [dep('1', 'lobby', '2026-05-14T10:00:00Z')] } })
    })

    const store = useDeploymentsAggregateStore()
    await store.fetchAll()

    expect(store.deployments).toHaveLength(1)
    expect(store.deployments[0]!.groupName).toBe('lobby')
  })

  it('URL-encodes group names with special characters', async () => {
    const groupsStore = useGroupsStore()
    // @ts-expect-error — seed Pinia state directly
    groupsStore.groups = [{ name: 'staging env' }]

    mockGET.mockResolvedValue({ data: { data: [] } })
    const store = useDeploymentsAggregateStore()
    await store.fetchAll()
    expect(mockGET).toHaveBeenCalledWith('/api/v1/groups/staging%20env/deployments')
  })

  it('triggers groups.fetchGroups when no groups are loaded', async () => {
    const groupsStore = useGroupsStore()
    const fetchSpy = vi.spyOn(groupsStore, 'fetchGroups').mockResolvedValue()

    mockGET.mockResolvedValue({ data: { data: [] } })

    const store = useDeploymentsAggregateStore()
    await store.fetchAll()
    expect(fetchSpy).toHaveBeenCalledTimes(1)
  })

  it('tolerates a group response with no data field', async () => {
    const groupsStore = useGroupsStore()
    // @ts-expect-error — seed Pinia state directly
    groupsStore.groups = [{ name: 'lobby' }]

    mockGET.mockResolvedValue({ data: {} })

    const store = useDeploymentsAggregateStore()
    await store.fetchAll()
    expect(store.deployments).toEqual([])
  })
})
