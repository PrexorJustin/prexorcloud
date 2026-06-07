import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import InstancesIndex from '../instances/index.vue'

const {
  fetchInstances, connectSse, disconnectSse, stopInstance, deleteInstance,
  fetchGroups, instancesStore, groupsStore, apiPost, toastSuccess, toastError,
} = vi.hoisted(() => ({
  fetchInstances: vi.fn(),
  connectSse: vi.fn(),
  disconnectSse: vi.fn(),
  stopInstance: vi.fn(),
  deleteInstance: vi.fn(),
  fetchGroups: vi.fn(),
  instancesStore: { instances: [] as unknown[], loading: false },
  groupsStore: { groups: [] as unknown[], loading: false },
  apiPost: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))
vi.mock('~/stores/instances', () => ({
  useInstancesStore: () => reactive(Object.assign(instancesStore, {
    fetchInstances, connectSse, disconnectSse, stopInstance, deleteInstance,
  })),
}))
vi.mock('~/stores/groups', () => ({
  useGroupsStore: () => reactive(Object.assign(groupsStore, { fetchGroups })),
}))
// useApiClient is a Nuxt auto-import — vi.stubGlobal can't intercept it, so
// the underlying composable module has to be mocked directly.
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ POST: apiPost }),
}))

function instance(overrides: Record<string, unknown> = {}) {
  return {
    id: 'lobby-1', group: 'lobby', node: 'node-a', state: 'RUNNING',
    port: 25565, playerCount: 17, uptimeMs: 3_600_000,
    startedAt: '2026-05-14T00:00:00Z', deploymentRevision: 1,
    ...overrides,
  }
}

beforeEach(() => {
  fetchInstances.mockReset()
  connectSse.mockReset()
  disconnectSse.mockReset()
  stopInstance.mockReset().mockResolvedValue(undefined)
  deleteInstance.mockReset().mockResolvedValue(undefined)
  fetchGroups.mockReset()
  apiPost.mockReset().mockResolvedValue({})
  toastSuccess.mockReset()
  toastError.mockReset()
  instancesStore.instances = []
  instancesStore.loading = false
  groupsStore.groups = []
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

// The Dialog teleports its content to document.body, so dialog buttons are
// not reachable through the mounted wrapper.
function bodyButton(text: string) {
  return Array.from(document.body.querySelectorAll('button')).find(b => (b.textContent ?? '').includes(text))
}

describe('InstancesIndex', () => {
  it('renders the page header and start button', async () => {
    const wrapper = await mountSuspended(InstancesIndex)
    expect(wrapper.text()).toContain('Instances')
    expect(wrapper.text()).toContain('Start instance')
  })

  it('loads instances and connects SSE on mount', async () => {
    await mountSuspended(InstancesIndex)
    expect(fetchInstances).toHaveBeenCalled()
    expect(connectSse).toHaveBeenCalled()
    expect(fetchGroups).toHaveBeenCalled()
  })

  it('shows the empty state when there are no instances', async () => {
    const wrapper = await mountSuspended(InstancesIndex)
    expect(wrapper.text()).toContain('No instances found')
    expect(wrapper.text()).toContain('No server instances running')
  })

  it('renders an instance card when the store has instances', async () => {
    instancesStore.instances = [instance()]
    const wrapper = await mountSuspended(InstancesIndex)
    expect(wrapper.text()).toContain('lobby-1')
    expect(wrapper.text()).not.toContain('No instances found')
  })

  it('shows the loading skeleton while the store is loading', async () => {
    instancesStore.loading = true
    const wrapper = await mountSuspended(InstancesIndex)
    expect(wrapper.text()).not.toContain('No instances found')
  })

  it('opens the start dialog and POSTs to the group start endpoint', async () => {
    groupsStore.groups = [{ name: 'lobby', platform: 'paper', platformVersion: '1.21', maintenance: false }]
    const wrapper = await mountSuspended(InstancesIndex)
    const startBtn = wrapper.findAll('button').find(b => b.text().includes('Start instance'))
    await startBtn!.trigger('click')
    await flush()
    bodyButton('lobby')!.click()
    await flush()
    bodyButton('Start Instance')!.click()
    await flush()
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v1/groups/{name}/start',
      expect.objectContaining({ params: { path: { name: 'lobby' } } }),
    )
    expect(toastSuccess).toHaveBeenCalled()
  })

  it('toasts an error when the start request fails', async () => {
    groupsStore.groups = [{ name: 'lobby', platform: 'paper', platformVersion: '1.21', maintenance: false }]
    apiPost.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountSuspended(InstancesIndex)
    const startBtn = wrapper.findAll('button').find(b => b.text().includes('Start instance'))
    await startBtn!.trigger('click')
    await flush()
    bodyButton('lobby')!.click()
    await flush()
    bodyButton('Start Instance')!.click()
    await flush()
    expect(toastError).toHaveBeenCalledWith('Start failed', expect.any(Object))
  })

  it('shows the no-groups hint in the start dialog when no groups are available', async () => {
    const wrapper = await mountSuspended(InstancesIndex)
    const startBtn = wrapper.findAll('button').find(b => b.text().includes('Start instance'))
    await startBtn!.trigger('click')
    await flush()
    expect(document.body.textContent).toContain('No groups available')
  })
})
