import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import MapPage from '../map.vue'

const { nodesState, instancesState, fetchNodes, fetchInstances, connectNodesSse, disconnectNodesSse, connectInstSse, disconnectInstSse, TopologyStub } = vi.hoisted(() => ({
  nodesState: { nodes: [] as Record<string, unknown>[], loading: false },
  instancesState: { instances: [] as Record<string, unknown>[] },
  fetchNodes: vi.fn(),
  fetchInstances: vi.fn(),
  connectNodesSse: vi.fn(),
  disconnectNodesSse: vi.fn(),
  connectInstSse: vi.fn(),
  disconnectInstSse: vi.fn(),
  TopologyStub: {
    name: 'TopologyCanvas',
    props: ['nodes', 'instances', 'cpuHistory'],
    emits: ['instance-click'],
    template: '<div class="topology-stub" @click="$emit(\'instance-click\', \'lobby-1\')" />',
  },
}))

// TopologyCanvas internally pulls in @vue-flow/* — stub the component surface
// entirely so the page test stays focused on the page wiring.
vi.mock('~/components/topology/TopologyCanvas.vue', () => ({ default: TopologyStub }))

vi.mock('~/stores/nodes', () => ({
  useNodesStore: () => ({
    get nodes() { return nodesState.nodes },
    get loading() { return nodesState.loading },
    fetchNodes,
    connectSse: connectNodesSse,
    disconnectSse: disconnectNodesSse,
  }),
}))
vi.mock('~/stores/instances', () => ({
  useInstancesStore: () => ({
    get instances() { return instancesState.instances },
    fetchInstances,
    connectSse: connectInstSse,
    disconnectSse: disconnectInstSse,
  }),
}))

function node(id: string) {
  return { id, type: 'CONNECTED', status: 'ONLINE', cpuUsage: 0.2 }
}
function instance(id: string, nodeId: string) {
  return {
    id, node: nodeId, group: 'lobby', state: 'RUNNING', port: 25565,
    playerCount: 4, uptimeMs: 60000, deploymentRevision: 3,
  }
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  document.body.innerHTML = ''
  nodesState.nodes = []
  nodesState.loading = false
  instancesState.instances = []
  fetchNodes.mockReset()
  fetchInstances.mockReset()
  connectNodesSse.mockReset()
  disconnectNodesSse.mockReset()
  connectInstSse.mockReset()
  disconnectInstSse.mockReset()
})

describe('map page', () => {
  it('renders the header and fetches + subscribes to both stores on mount', async () => {
    const wrapper = await mountSuspended(MapPage)
    expect(wrapper.text()).toContain('Map')
    expect(wrapper.text()).toContain('Cluster topology')
    expect(fetchNodes).toHaveBeenCalled()
    expect(fetchInstances).toHaveBeenCalled()
    expect(connectNodesSse).toHaveBeenCalled()
    expect(connectInstSse).toHaveBeenCalled()
  })

  it('shows the no-nodes empty state when the cluster is empty', async () => {
    const wrapper = await mountSuspended(MapPage)
    expect(wrapper.text()).toContain('No nodes connected')
    expect(wrapper.find('.topology-stub').exists()).toBe(false)
  })

  it('renders the topology canvas with the cluster nodes once they load', async () => {
    nodesState.nodes = [node('node-a'), node('node-b')]
    instancesState.instances = [instance('lobby-1', 'node-a')]
    const wrapper = await mountSuspended(MapPage)
    const canvas = wrapper.findComponent(TopologyStub)
    expect(canvas.exists()).toBe(true)
    expect(canvas.props('nodes')).toHaveLength(2)
    expect(canvas.props('instances')).toHaveLength(1)
  })

  it('filters nodes by id through the search input', async () => {
    nodesState.nodes = [node('node-a'), node('node-b')]
    const wrapper = await mountSuspended(MapPage)
    await wrapper.find('input').setValue('node-a')
    await flush()
    expect(wrapper.findComponent(TopologyStub).props('nodes')).toHaveLength(1)
  })

  it('shows the no-matches empty state when the filter excludes every node', async () => {
    nodesState.nodes = [node('node-a')]
    const wrapper = await mountSuspended(MapPage)
    await wrapper.find('input').setValue('zzz-nothing')
    await flush()
    expect(wrapper.text()).toContain('No matches')
  })

  it('opens the instance detail sheet when the canvas emits instance-click', async () => {
    nodesState.nodes = [node('node-a')]
    instancesState.instances = [instance('lobby-1', 'node-a')]
    const wrapper = await mountSuspended(MapPage)
    await wrapper.findComponent(TopologyStub).trigger('click')
    await flush()
    // DetailSheet (reka-ui Sheet) teleports its content to document.body.
    expect(document.body.textContent).toContain('lobby-1')
    expect(document.body.textContent).toContain('Players')
  })

  it('tears down both SSE subscriptions on unmount', async () => {
    nodesState.nodes = [node('node-a')]
    const wrapper = await mountSuspended(MapPage)
    wrapper.unmount()
    expect(disconnectNodesSse).toHaveBeenCalled()
    expect(disconnectInstSse).toHaveBeenCalled()
  })
})
