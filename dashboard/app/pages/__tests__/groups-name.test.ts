import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import GroupDetail from '../groups/[name].vue'
import type { ServerGroup } from '~/types/api'

const {
  routeParams, routerPush, apiGET, apiPOST, apiDELETE,
  toastSuccess, toastError, instancesStore, groupsStore,
} = vi.hoisted(() => ({
  routeParams: { value: { name: 'lobby' } as Record<string, string> },
  routerPush: vi.fn(),
  apiGET: vi.fn(),
  apiPOST: vi.fn(),
  apiDELETE: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
  instancesStore: {
    instances: [] as unknown[],
    fetchInstances: vi.fn(),
    connectSse: vi.fn(),
    disconnectSse: vi.fn(),
  },
  groupsStore: {
    groups: [] as unknown[],
    lastDeploymentEvent: null as unknown,
    connectSse: vi.fn(),
    disconnectSse: vi.fn(),
  },
}))

mockNuxtImport('useRoute', () => () => ({ params: routeParams.value }))
mockNuxtImport('useRouter', () => () => ({
  push: routerPush,
  replace: vi.fn(),
  afterEach: vi.fn(),
  beforeEach: vi.fn(),
  beforeResolve: vi.fn(),
  isReady: () => Promise.resolve(),
  resolve: (to: string | { path?: string }) => {
    const path = typeof to === 'string' ? to : (to.path ?? '/')
    return { href: path, fullPath: path, path, matched: [], params: {}, query: {}, hash: '' }
  },
  currentRoute: { value: { path: '/groups/lobby', params: routeParams.value } },
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: apiGET, POST: apiPOST, DELETE: apiDELETE }),
}))
vi.mock('~/stores/instances', () => ({ useInstancesStore: () => reactive(instancesStore) }))
vi.mock('~/stores/groups', () => ({ useGroupsStore: () => reactive(groupsStore) }))
vi.stubGlobal('navigateTo', vi.fn())

function group(overrides: Partial<ServerGroup> = {}): ServerGroup {
  return {
    name: 'lobby', platform: 'paper', platformVersion: '1.21.1',
    routing: 'LEAST_PLAYERS', portRangeStart: 30000, portRangeEnd: 30100,
    updateStrategy: 'ROLLING', memoryMb: 4096, jvmArgs: [], priority: 0,
    scalingMode: 'DYNAMIC', runningInstances: 2, minInstances: 1, maxInstances: 8,
    totalPlayers: 30, maxPlayers: 200, scaleUpThreshold: 75, scaleDownAfterSeconds: 300,
    scaleCooldownSeconds: 60, startupTimeoutSeconds: 120, shutdownGraceSeconds: 30,
    templates: [], nodeAffinity: [], nodeAntiAffinity: [], dependsOn: [],
    maintenance: false, static: false, predictiveScaling: false,
    ...overrides,
  } as ServerGroup
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

// Dialog / ConfirmDialog (reka-ui) teleport their content to document.body,
// so their buttons aren't reachable through the mounted wrapper.
function bodyButton(predicate: (text: string) => boolean): HTMLButtonElement {
  const btn = [...document.body.querySelectorAll('button')]
    .find(b => predicate((b.textContent ?? '').trim()))
  if (!btn) throw new Error('button not found in document.body')
  return btn as HTMLButtonElement
}

// GET dispatcher keyed by the path/template the page calls.
function setupGET(opts: { group?: ServerGroup | null; deployments?: unknown[]; resolved?: unknown } = {}) {
  apiGET.mockImplementation((path: string) => {
    if (path === '/api/v1/groups/{name}') return Promise.resolve({ data: opts.group ?? group() })
    if (path === '/api/v1/groups/{name}/deployments') return Promise.resolve({ data: { data: opts.deployments ?? [] } })
    if (typeof path === 'string' && path.includes('/resolved')) return Promise.resolve({ data: opts.resolved ?? null })
    return Promise.resolve({ data: null })
  })
}

beforeEach(() => {
  routeParams.value = { name: 'lobby' }
  routerPush.mockReset()
  apiGET.mockReset()
  apiPOST.mockReset().mockResolvedValue({ data: {} })
  apiDELETE.mockReset().mockResolvedValue({ data: {} })
  toastSuccess.mockReset()
  toastError.mockReset()
  instancesStore.instances = []
  instancesStore.fetchInstances.mockReset()
  instancesStore.connectSse.mockReset()
  instancesStore.disconnectSse.mockReset()
  groupsStore.groups = []
  groupsStore.lastDeploymentEvent = null
  groupsStore.connectSse.mockReset()
  groupsStore.disconnectSse.mockReset()
})

describe('Group detail page', () => {
  it('renders the group name and loads the group + instances on mount', async () => {
    setupGET()
    const wrapper = await mountSuspended(GroupDetail)
    await flush()
    expect(wrapper.text()).toContain('lobby')
    expect(apiGET).toHaveBeenCalledWith('/api/v1/groups/{name}', { params: { path: { name: 'lobby' } } })
    expect(instancesStore.fetchInstances).toHaveBeenCalledTimes(1)
    expect(instancesStore.connectSse).toHaveBeenCalledTimes(1)
  })

  it('renders the configuration and scaling panels from the loaded group', async () => {
    setupGET({ group: group({ memoryMb: 8192 }) })
    const wrapper = await mountSuspended(GroupDetail)
    await flush()
    expect(wrapper.text()).toContain('Configuration')
    expect(wrapper.text()).toContain('Scaling')
    expect(wrapper.text()).toContain('8192 MB')
    expect(wrapper.text()).toContain('paper 1.21.1')
  })

  it('redirects to /groups and toasts an error when the group cannot be loaded', async () => {
    apiGET.mockRejectedValue(new Error('boom'))
    await mountSuspended(GroupDetail)
    await flush()
    expect(toastError).toHaveBeenCalledWith("Can't load group", expect.any(Object))
    expect(routerPush).toHaveBeenCalledWith('/groups')
  })

  it('shows the no-instances message when the group has none running', async () => {
    setupGET()
    const wrapper = await mountSuspended(GroupDetail)
    await flush()
    expect(wrapper.text()).toContain('No instances running')
  })

  it('lists instances belonging to the group from the instances store', async () => {
    setupGET()
    instancesStore.instances = [
      { id: 'lobby-1', group: 'lobby', node: 'node-a', state: 'RUNNING', playerCount: 5, uptimeMs: 1000 },
      { id: 'other-1', group: 'survival', node: 'node-b', state: 'RUNNING', playerCount: 0, uptimeMs: 1000 },
    ]
    const wrapper = await mountSuspended(GroupDetail)
    await flush()
    expect(wrapper.text()).toContain('lobby-1')
    expect(wrapper.text()).not.toContain('other-1')
  })

  it('triggers a deploy and toasts success when the Deploy button is clicked', async () => {
    setupGET()
    const wrapper = await mountSuspended(GroupDetail)
    await flush()
    const deploy = wrapper.findAll('button').find(b => b.text().includes('Deploy'))!
    await deploy.trigger('click')
    await flush()
    expect(apiPOST).toHaveBeenCalledWith('/api/v1/groups/{name}/deploy', { params: { path: { name: 'lobby' } } })
    expect(toastSuccess).toHaveBeenCalledWith('Deployment triggered', expect.any(Object))
  })

  it('opens the start dialog and POSTs to the start endpoint on confirm', async () => {
    setupGET()
    const wrapper = await mountSuspended(GroupDetail)
    await flush()
    await wrapper.findAll('button').find(b => b.text().includes('Start'))!.trigger('click')
    await flush()
    bodyButton(t => t === 'Start instance').click()
    await flush()
    expect(apiPOST).toHaveBeenCalledWith('/api/v1/groups/{name}/start', { params: { path: { name: 'lobby' } }, body: {} })
    expect(toastSuccess).toHaveBeenCalled()
  })

  it('toasts an error when scheduling a start fails', async () => {
    setupGET()
    apiPOST.mockRejectedValueOnce(new Error('no capacity'))
    const wrapper = await mountSuspended(GroupDetail)
    await flush()
    await wrapper.findAll('button').find(b => b.text().includes('Start'))!.trigger('click')
    await flush()
    bodyButton(t => t === 'Start instance').click()
    await flush()
    expect(toastError).toHaveBeenCalledWith('Start failed', expect.any(Object))
  })

  it('deletes the group and navigates back to /groups on confirm', async () => {
    setupGET()
    const wrapper = await mountSuspended(GroupDetail)
    await flush()
    await wrapper.findAll('button').find(b => b.text().includes('Delete'))!.trigger('click')
    await flush()
    bodyButton(t => t === 'Delete').click()
    await flush()
    expect(apiDELETE).toHaveBeenCalledWith('/api/v1/groups/{name}', { params: { path: { name: 'lobby' } } })
    expect(toastSuccess).toHaveBeenCalledWith('Group deleted', expect.any(Object))
    expect(routerPush).toHaveBeenCalledWith('/groups')
  })
})
