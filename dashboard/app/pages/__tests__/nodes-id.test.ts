import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import NodeDetail from '../nodes/[id].vue'
import type { ConnectedNode, PendingNode } from '~/types/api'

const { getMock, postMock, deleteMock, routerPush, route, nodesStore, instancesStore, toastSuccess, toastError } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  deleteMock: vi.fn(),
  routerPush: vi.fn(),
  route: { params: { id: 'node-a' } } as { params: { id: string } },
  nodesStore: {
    nodes: [] as unknown[],
    staleNodeIds: new Set<string>(),
    cacheUpdatedNodeId: null as string | null,
    fetchNodes: vi.fn(),
    connectSse: vi.fn(),
    disconnectSse: vi.fn(),
  },
  instancesStore: {
    instances: [] as unknown[],
    fetchInstances: vi.fn(),
  },
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: getMock, POST: postMock, DELETE: deleteMock }),
}))

mockNuxtImport('useRoute', () => () => reactive(route))
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
  currentRoute: reactive(route),
}))
mockNuxtImport('useNodesStore', () => () => reactive(nodesStore))
mockNuxtImport('useInstancesStore', () => () => reactive(instancesStore))

function connectedNode(overrides: Partial<ConnectedNode> = {}): ConnectedNode {
  return {
    id: 'node-a',
    type: 'CONNECTED',
    status: 'ONLINE',
    address: '10.0.0.5:7000',
    connectedSince: '2026-05-13T00:00:00Z',
    lastHeartbeat: '2026-05-14T00:00:00Z',
    cpuUsage: 0.42,
    usedMemoryMb: 2048,
    totalMemoryMb: 8192,
    freeDiskMb: 50000,
    totalDiskMb: 100000,
    instanceCount: 1,
    labels: {},
    hostInfo: null,
    ...overrides,
  } as ConnectedNode
}

function pendingNode(overrides: Partial<PendingNode> = {}): PendingNode {
  return {
    id: 'node-a',
    type: 'PENDING',
    status: 'PENDING',
    joinToken: 'join-tok-123',
    tokenId: 'tok-1',
    expiresAt: '2026-06-01T00:00:00Z',
    ...overrides,
  } as PendingNode
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  getMock.mockReset()
  postMock.mockReset().mockResolvedValue({})
  deleteMock.mockReset().mockResolvedValue({})
  routerPush.mockReset()
  toastSuccess.mockReset()
  toastError.mockReset()
  route.params = { id: 'node-a' }
  nodesStore.nodes = []
  nodesStore.staleNodeIds = new Set()
  nodesStore.cacheUpdatedNodeId = null
  nodesStore.fetchNodes.mockReset()
  nodesStore.connectSse.mockReset()
  nodesStore.disconnectSse.mockReset()
  instancesStore.instances = []
  instancesStore.fetchInstances.mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('NodeDetail', () => {
  it('renders the connected node header, address and connection info once loaded', async () => {
    getMock.mockResolvedValue({ data: connectedNode() })
    const wrapper = await mountSuspended(NodeDetail)
    await flush()
    expect(wrapper.text()).toContain('node-a')
    expect(wrapper.text()).toContain('10.0.0.5:7000')
    expect(wrapper.text()).toContain('Connection')
    expect(wrapper.text()).toContain('Host')
  })

  it('fetches the node and primes the stores on mount', async () => {
    getMock.mockResolvedValue({ data: connectedNode() })
    await mountSuspended(NodeDetail)
    await flush()
    expect(getMock).toHaveBeenCalledWith('/api/v1/nodes/{id}', { params: { path: { id: 'node-a' } } })
    expect(nodesStore.fetchNodes).toHaveBeenCalled()
    expect(nodesStore.connectSse).toHaveBeenCalled()
    expect(instancesStore.fetchInstances).toHaveBeenCalled()
  })

  it('shows the not-found state when the node fetch returns no data', async () => {
    getMock.mockResolvedValue({ data: null })
    const wrapper = await mountSuspended(NodeDetail)
    await flush()
    expect(wrapper.text()).toContain('Node not found')
  })

  it('toasts an error when the node fetch rejects', async () => {
    getMock.mockRejectedValue(new Error('boom'))
    await mountSuspended(NodeDetail)
    await flush()
    expect(toastError).toHaveBeenCalledWith("Can't load node", expect.any(Object))
  })

  it('switching to the Cache tab fetches the cache status', async () => {
    getMock.mockImplementation((path: string) => {
      if (path === '/api/v1/nodes/{id}/cache') return Promise.resolve({ data: { totalSizeBytes: 0, templates: [], jars: [], bootstraps: [] } })
      return Promise.resolve({ data: connectedNode() })
    })
    const wrapper = await mountSuspended(NodeDetail)
    await flush()
    const cacheTab = wrapper.findAll('button').find(b => b.text().includes('Cache'))!
    await cacheTab.trigger('click')
    await flush()
    expect(getMock).toHaveBeenCalledWith('/api/v1/nodes/{id}/cache', { params: { path: { id: 'node-a' } } })
  })

  it('shows the Drain danger zone for an online connected node', async () => {
    getMock.mockResolvedValue({ data: connectedNode({ status: 'ONLINE' }) })
    const wrapper = await mountSuspended(NodeDetail)
    await flush()
    expect(wrapper.text()).toContain('Drain node')
    expect(wrapper.text()).toContain('Cordon node')
  })

  it('renders the pending node join token and a Revoke action', async () => {
    getMock.mockResolvedValue({ data: pendingNode() })
    const wrapper = await mountSuspended(NodeDetail)
    await flush()
    expect(wrapper.text()).toContain('Pending node')
    expect(wrapper.text()).toContain('join-tok-123')
    expect(wrapper.text()).toContain('Revoke join token')
  })

  it('lists instances scheduled on the node', async () => {
    getMock.mockResolvedValue({ data: connectedNode() })
    instancesStore.instances = [
      { id: 'inst-1', node: 'node-a', group: 'lobby', state: 'RUNNING', playerCount: 3, uptimeMs: 60000 },
      { id: 'inst-2', node: 'node-b', group: 'survival', state: 'RUNNING', playerCount: 0, uptimeMs: 0 },
    ]
    const wrapper = await mountSuspended(NodeDetail)
    await flush()
    expect(wrapper.text()).toContain('inst-1')
    expect(wrapper.text()).not.toContain('inst-2')
  })
})
