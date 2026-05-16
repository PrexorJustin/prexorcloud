import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { MaintenanceState } from '~/stores/maintenance'
import type { ServerGroup } from '~/types/api'
import Maintenance from '../operations/maintenance.vue'

const {
  fetchState, updateState, setGroupMaintenance,
  groupsState, fetchGroups,
} = vi.hoisted(() => ({
  fetchState: vi.fn(),
  updateState: vi.fn(),
  setGroupMaintenance: vi.fn(),
  groupsState: { groups: [] as ServerGroup[] },
  fetchGroups: vi.fn(),
}))

// One persistent reactive state object the page's `watch(() => store.state)`
// tracks across the whole file; beforeEach mutates it in place.
const maintStateRef = reactive<MaintenanceState>({
  globalEnabled: false,
  globalMessage: '',
  globalBypassUsernames: [],
  groups: [],
})

vi.mock('~/stores/maintenance', () => ({
  useMaintenanceStore: () => ({
    state: maintStateRef,
    loading: false,
    fetchState,
    updateState,
    setGroupMaintenance,
  }),
}))
vi.mock('~/stores/groups', () => ({
  useGroupsStore: () => ({
    get groups() { return groupsState.groups },
    fetchGroups,
  }),
}))

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

function setMaintState(next: MaintenanceState) {
  maintStateRef.globalEnabled = next.globalEnabled
  maintStateRef.globalMessage = next.globalMessage
  maintStateRef.globalBypassUsernames = next.globalBypassUsernames
  maintStateRef.groups = next.groups
}

beforeEach(() => {
  setMaintState({
    globalEnabled: false,
    globalMessage: '',
    globalBypassUsernames: [],
    groups: [],
  })
  groupsState.groups = []
  fetchState.mockReset().mockResolvedValue(undefined)
  updateState.mockReset().mockResolvedValue(undefined)
  setGroupMaintenance.mockReset().mockResolvedValue(undefined)
  fetchGroups.mockReset().mockResolvedValue(undefined)
})

describe('operations/maintenance', () => {
  it('renders the page header and global toggle card', async () => {
    const wrapper = await mountSuspended(Maintenance)
    expect(wrapper.text()).toContain('Maintenance')
    expect(wrapper.text()).toContain('Block player joins cluster-wide or per group')
    expect(wrapper.text()).toContain('Cluster maintenance')
  })

  it('fetches maintenance state and groups on mount', async () => {
    await mountSuspended(Maintenance)
    expect(fetchState).toHaveBeenCalledTimes(1)
    expect(fetchGroups).toHaveBeenCalledTimes(1)
  })

  it('shows the live badge and no warning callout when maintenance is off', async () => {
    const wrapper = await mountSuspended(Maintenance)
    expect(wrapper.text()).toContain('Cluster live')
    expect(wrapper.text()).not.toContain('Cluster-wide maintenance is enabled')
  })

  it('shows the warning callout when global maintenance is enabled', async () => {
    setMaintState({ globalEnabled: true, globalMessage: 'down', globalBypassUsernames: [], groups: [] })
    const wrapper = await mountSuspended(Maintenance)
    expect(wrapper.text()).toContain('Cluster-wide maintenance is enabled')
    expect(wrapper.text()).toContain('Maintenance on')
  })

  it('shows the no-overrides hint when no per-group overrides exist', async () => {
    const wrapper = await mountSuspended(Maintenance)
    expect(wrapper.text()).toContain('No per-group overrides')
  })

  it('keeps the Save button disabled until the global form is dirty', async () => {
    const wrapper = await mountSuspended(Maintenance)
    const saveBtn = wrapper.findAll('button').find(b => b.text().includes('Save changes'))
    expect(saveBtn?.attributes('disabled')).toBeDefined()
    await wrapper.find('textarea#m-message').setValue('Going down for migration')
    await flush()
    expect(saveBtn?.attributes('disabled')).toBeUndefined()
  })

  it('saves the global form by calling updateState with the edited fields', async () => {
    const wrapper = await mountSuspended(Maintenance)
    await wrapper.find('textarea#m-message').setValue('Going down for migration')
    await wrapper.find('input#m-bypass').setValue('ops, alice')
    await flush()
    const saveBtn = wrapper.findAll('button').find(b => b.text().includes('Save changes'))!
    await saveBtn.trigger('click')
    await flush()
    expect(updateState).toHaveBeenCalledWith({
      globalEnabled: false,
      globalMessage: 'Going down for migration',
      globalBypassUsernames: ['ops', 'alice'],
    })
  })

  it('renders a per-group override card and removes it via the trash button', async () => {
    setMaintState({
      globalEnabled: false,
      globalMessage: '',
      globalBypassUsernames: [],
      groups: [{ groupName: 'lobby', enabled: true, message: 'patching' }],
    })
    const wrapper = await mountSuspended(Maintenance)
    expect(wrapper.text()).toContain('lobby')
    const removeBtn = wrapper.find('button[aria-label="Remove override"]')
    expect(removeBtn.exists()).toBe(true)
    // Mirror the real store: removing an override drops it from state.groups.
    setGroupMaintenance.mockImplementationOnce(async (name: string) => {
      maintStateRef.groups = maintStateRef.groups.filter(g => g.groupName !== name)
    })
    await removeBtn.trigger('click')
    await flush()
    expect(setGroupMaintenance).toHaveBeenCalledWith('lobby', false)
    expect(maintStateRef.groups).toHaveLength(0)
  })

  it('adds a new per-group override from the add panel', async () => {
    groupsState.groups = [{ name: 'survival' } as ServerGroup]
    const wrapper = await mountSuspended(Maintenance)
    const addBtn = wrapper.findAll('button').find(b => b.text().includes('Add override'))!
    await addBtn.trigger('click')
    await flush()
    await wrapper.find('select#m-add-group').setValue('survival')
    await flush()
    const enableBtn = wrapper.findAll('button').find(b => b.text().includes('Enable maintenance'))!
    await enableBtn.trigger('click')
    await flush()
    expect(setGroupMaintenance).toHaveBeenCalledWith('survival', true)
  })
})
