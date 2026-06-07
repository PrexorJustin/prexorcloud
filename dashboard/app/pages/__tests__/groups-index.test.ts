import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { ServerGroup } from '~/types/api'
import GroupsIndex from '../groups/index.vue'

const { storeState, fetchGroups, connectSse, disconnectSse, postMock, toastSuccess, toastError } = vi.hoisted(() => ({
  storeState: { groups: [] as ServerGroup[], loading: false },
  fetchGroups: vi.fn(),
  connectSse: vi.fn(),
  disconnectSse: vi.fn(),
  postMock: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('~/stores/groups', () => ({
  useGroupsStore: () => ({
    get groups() { return storeState.groups },
    get loading() { return storeState.loading },
    fetchGroups,
    connectSse,
    disconnectSse,
  }),
}))
vi.mock('~/composables/useApiClient', () => ({ useApiClient: () => ({ POST: postMock }) }))
vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))

function group(overrides: Partial<ServerGroup> = {}): ServerGroup {
  return {
    name: 'lobby', platform: 'paper', platformVersion: '1.21.1', scalingMode: 'DYNAMIC',
    maintenance: false, runningInstances: 2, maxInstances: 8,
    minInstances: 1, totalPlayers: 30, maxPlayers: 200, memoryMb: 4096,
    updateStrategy: 'ROLLING',
    ...overrides,
  } as ServerGroup
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  storeState.groups = []
  storeState.loading = false
  fetchGroups.mockReset().mockResolvedValue(undefined)
  connectSse.mockReset()
  disconnectSse.mockReset()
  postMock.mockReset().mockResolvedValue({})
  toastSuccess.mockReset()
  toastError.mockReset()
})

describe('groups/index', () => {
  it('renders the page header and search toolbar', async () => {
    const wrapper = await mountSuspended(GroupsIndex)
    expect(wrapper.text()).toContain('Groups')
    expect(wrapper.text()).toContain('Server groups and their scaling policies')
    expect(wrapper.find('input').exists()).toBe(true)
  })

  it('fetches groups and connects SSE on mount', async () => {
    await mountSuspended(GroupsIndex)
    expect(fetchGroups).toHaveBeenCalledTimes(1)
    expect(connectSse).toHaveBeenCalledTimes(1)
  })

  it('shows the empty-create state when there are no groups', async () => {
    const wrapper = await mountSuspended(GroupsIndex)
    expect(wrapper.text()).toContain('No groups found')
    expect(wrapper.text()).toContain('No server groups configured yet')
  })

  it('renders a group card per group in grid view', async () => {
    storeState.groups = [group({ name: 'lobby' }), group({ name: 'survival' })]
    const wrapper = await mountSuspended(GroupsIndex)
    expect(wrapper.text()).toContain('lobby')
    expect(wrapper.text()).toContain('survival')
  })

  it('filters groups by the search field', async () => {
    storeState.groups = [group({ name: 'lobby' }), group({ name: 'survival' })]
    const wrapper = await mountSuspended(GroupsIndex)
    await wrapper.find('input').setValue('survival')
    await flush()
    expect(wrapper.text()).toContain('survival')
    expect(wrapper.text()).not.toContain('lobby')
  })

  it('shows the filter-hint empty state when the search excludes everything', async () => {
    storeState.groups = [group({ name: 'lobby' })]
    const wrapper = await mountSuspended(GroupsIndex)
    await wrapper.find('input').setValue('zzz-nothing')
    await flush()
    expect(wrapper.text()).toContain('No groups found')
    expect(wrapper.text()).toContain('Try adjusting your filters')
  })

  it('opens the detail sheet when a table row is clicked', async () => {
    storeState.groups = [group({ name: 'lobby' })]
    const wrapper = await mountSuspended(GroupsIndex)
    // Switch to table view via the view-mode toggle.
    const toggles = wrapper.findAll('button')
    const tableBtn = toggles.find(b => b.attributes('aria-label')?.toLowerCase().includes('table')
      || b.html().toLowerCase().includes('table'))
    if (tableBtn) await tableBtn.trigger('click')
    await flush()
    const row = wrapper.find('.cursor-pointer')
    expect(row.exists()).toBe(true)
    await row.trigger('click')
    await flush()
    // DetailSheet renders via a teleport into the document body.
    expect(document.body.textContent).toContain('Update strategy')
  })

  it('disconnects SSE on unmount', async () => {
    const wrapper = await mountSuspended(GroupsIndex)
    wrapper.unmount()
    expect(disconnectSse).toHaveBeenCalledTimes(1)
  })
})
