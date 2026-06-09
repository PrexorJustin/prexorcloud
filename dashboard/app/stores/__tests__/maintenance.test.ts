import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useMaintenanceStore } from '../maintenance'

const mockGET = vi.fn()
const mockPUT = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, PUT: mockPUT, POST: vi.fn(), PATCH: vi.fn(), DELETE: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

const mockToastSuccess = toast.success as ReturnType<typeof vi.fn>
const mockToastError = toast.error as ReturnType<typeof vi.fn>

beforeEach(() => {
  setActivePinia(createPinia())
  mockGET.mockReset()
  mockPUT.mockReset()
  mockToastSuccess.mockReset()
  mockToastError.mockReset()
})

describe('useMaintenanceStore', () => {
  it('starts with disabled global and no groups', () => {
    const store = useMaintenanceStore()
    expect(store.state.globalEnabled).toBe(false)
    expect(store.state.globalMessage).toBe('')
    expect(store.state.globalBypassUsernames).toEqual([])
    expect(store.state.groups).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchState populates state and toggles loading', async () => {
    mockGET.mockResolvedValue({
      data: {
        globalEnabled: true,
        globalMessage: 'down for upgrade',
        globalBypassUsernames: ['ops'],
        groups: [{ groupName: 'lobby', enabled: true }],
      },
    })

    const store = useMaintenanceStore()
    const promise = store.fetchState()
    expect(store.loading).toBe(true)
    await promise
    expect(store.loading).toBe(false)
    expect(store.state.globalEnabled).toBe(true)
    expect(store.state.globalMessage).toBe('down for upgrade')
    expect(store.state.globalBypassUsernames).toEqual(['ops'])
    expect(store.state.groups).toHaveLength(1)
    expect(mockGET).toHaveBeenCalledWith('/api/v1/maintenance')
  })

  it('fetchState fills defaults when fields are missing', async () => {
    mockGET.mockResolvedValue({ data: {} })
    const store = useMaintenanceStore()
    await store.fetchState()
    expect(store.state.globalEnabled).toBe(false)
    expect(store.state.globalMessage).toBe('')
    expect(store.state.globalBypassUsernames).toEqual([])
    expect(store.state.groups).toEqual([])
  })

  it('fetchState surfaces a toast on failure but resets loading', async () => {
    mockGET.mockRejectedValue(new Error('boom'))
    const store = useMaintenanceStore()
    await store.fetchState()
    expect(store.loading).toBe(false)
    expect(mockToastError).toHaveBeenCalledTimes(1)
  })

  it('updateState PUTs the partial body then refetches', async () => {
    mockPUT.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { globalEnabled: true } })

    const store = useMaintenanceStore()
    await store.updateState({ globalEnabled: true })

    expect(mockPUT).toHaveBeenCalledWith('/api/v1/maintenance', { body: { globalEnabled: true } })
    expect(mockGET).toHaveBeenCalledWith('/api/v1/maintenance')
    expect(store.state.globalEnabled).toBe(true)
  })

  it('updateState rethrows a typed error and toasts on PUT failure', async () => {
    mockPUT.mockRejectedValue(new Error('boom'))
    const store = useMaintenanceStore()
    await expect(store.updateState({ globalEnabled: true })).rejects.toThrow('update-maintenance')
    expect(mockToastError).toHaveBeenCalledTimes(1)
    expect(mockGET).not.toHaveBeenCalled()
  })

  it('setGlobal turns the flag on with the current message and toasts success', async () => {
    mockPUT.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { globalEnabled: true, globalMessage: 'hi' } })

    const store = useMaintenanceStore()
    store.state.globalMessage = 'hi'
    await store.setGlobal(true)

    expect(mockPUT).toHaveBeenCalledWith('/api/v1/maintenance', {
      body: { globalEnabled: true, globalMessage: 'hi' },
    })
    expect(mockToastSuccess).toHaveBeenCalledWith('Cluster maintenance enabled')
  })

  it('setGlobal turning off toasts the disabled label and honours an override message', async () => {
    mockPUT.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { globalEnabled: false } })

    const store = useMaintenanceStore()
    await store.setGlobal(false, 'back later')

    expect(mockPUT).toHaveBeenCalledWith('/api/v1/maintenance', {
      body: { globalEnabled: false, globalMessage: 'back later' },
    })
    expect(mockToastSuccess).toHaveBeenCalledWith('Cluster maintenance disabled')
  })

  it('setGroupMaintenance(enabled=true) inserts a new override entry', async () => {
    mockPUT.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { groups: [{ groupName: 'lobby', enabled: true }] } })

    const store = useMaintenanceStore()
    await store.setGroupMaintenance('lobby', true, 'just a sec')

    const body = mockPUT.mock.calls[0]![1] as { body: { groups: unknown[] } }
    expect(body.body.groups).toEqual([
      { groupName: 'lobby', enabled: true, message: 'just a sec', bypassUsernames: [] },
    ])
    expect(mockToastSuccess).toHaveBeenCalledWith('Group "lobby" maintenance enabled')
  })

  it('setGroupMaintenance(enabled=false) without a message removes the group entry', async () => {
    mockPUT.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { groups: [] } })

    const store = useMaintenanceStore()
    // Seed an existing override.
    store.state.groups = [
      { groupName: 'lobby', enabled: true, bypassUsernames: ['mod1'] },
      { groupName: 'survival', enabled: false },
    ]

    await store.setGroupMaintenance('lobby', false)

    const body = mockPUT.mock.calls[0]![1] as { body: { groups: unknown[] } }
    // 'lobby' is filtered out entirely because enabled=false and no message.
    expect(body.body.groups).toEqual([{ groupName: 'survival', enabled: false }])
    expect(mockToastSuccess).toHaveBeenCalledWith('Group "lobby" maintenance disabled')
  })

  it('setGroupMaintenance preserves bypassUsernames when re-inserting', async () => {
    mockPUT.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { groups: [] } })

    const store = useMaintenanceStore()
    store.state.groups = [
      { groupName: 'lobby', enabled: false, bypassUsernames: ['mod1', 'mod2'] },
    ]

    await store.setGroupMaintenance('lobby', true)

    const body = mockPUT.mock.calls[0]![1] as { body: { groups: { bypassUsernames: string[] }[] } }
    expect(body.body.groups[0]!.bypassUsernames).toEqual(['mod1', 'mod2'])
  })
})
