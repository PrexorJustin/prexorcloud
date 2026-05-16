import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'

import NodesIndex from '../nodes/index.vue'

const { storeState, fetchNodes, connectSse, disconnectSse, post, toastSuccess, toastError } = vi.hoisted(() => ({
  storeState: {
    nodes: [] as unknown[],
    loading: false,
    explodingNodeId: null as string | null,
  },
  fetchNodes: vi.fn(),
  connectSse: vi.fn(),
  disconnectSse: vi.fn(),
  post: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))
vi.mock('~/composables/useApiClient', () => ({ useApiClient: () => ({ POST: post }) }))
vi.mock('~/stores/nodes', () => ({
  useNodesStore: () => ({
    get nodes() { return storeState.nodes },
    get loading() { return storeState.loading },
    get explodingNodeId() { return storeState.explodingNodeId },
    fetchNodes,
    connectSse,
    disconnectSse,
  }),
}))

const connectedNode = {
  id: 'node-a', address: '10.0.0.1:8443', type: 'CONNECTED', status: 'ONLINE',
  cpuUsage: 42, totalMemoryMb: 16384, usedMemoryMb: 8192, freeDiskMb: 100000,
  totalDiskMb: 200000, instanceCount: 5,
  connectedSince: '2026-05-01T00:00:00Z', lastHeartbeat: '2026-05-14T00:00:00Z',
}

beforeEach(() => {
  storeState.nodes = []
  storeState.loading = false
  storeState.explodingNodeId = null
  fetchNodes.mockReset().mockResolvedValue(undefined)
  connectSse.mockReset()
  disconnectSse.mockReset()
  post.mockReset().mockResolvedValue({ data: {} })
  toastSuccess.mockReset()
  toastError.mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

describe('NodesIndex', () => {
  it('renders the page header and fetches nodes plus connects SSE on mount', async () => {
    const wrapper = await mountSuspended(NodesIndex)
    expect(wrapper.text()).toContain('Nodes')
    expect(wrapper.text()).toContain('Hosts connected to the cluster')
    expect(fetchNodes).toHaveBeenCalled()
    expect(connectSse).toHaveBeenCalled()
  })

  it('shows the loading skeleton while the store is loading', async () => {
    storeState.loading = true
    const wrapper = await mountSuspended(NodesIndex)
    expect(wrapper.findComponent({ name: 'LoadingSkeleton' }).exists()).toBe(true)
    expect(wrapper.text()).not.toContain('No nodes found')
  })

  it('shows the empty state with an add hint when there are no nodes', async () => {
    const wrapper = await mountSuspended(NodesIndex)
    expect(wrapper.text()).toContain('No nodes found')
    expect(wrapper.text()).toContain('Add a node to get started')
  })

  it('renders a node card for each node once loaded', async () => {
    storeState.nodes = [connectedNode]
    const wrapper = await mountSuspended(NodesIndex)
    expect(wrapper.text()).toContain('node-a')
    expect(wrapper.text()).toContain('10.0.0.1:8443')
  })

  it('shows the filter-hint empty state when a search matches nothing', async () => {
    storeState.nodes = [connectedNode]
    const wrapper = await mountSuspended(NodesIndex)
    await wrapper.find('input').setValue('does-not-exist')
    await flush()
    expect(wrapper.text()).toContain('No nodes found')
    expect(wrapper.text()).toContain('Try adjusting your filters')
  })

  it('bulk-drains selected nodes via the API and refetches', async () => {
    storeState.nodes = [connectedNode]
    const wrapper = await mountSuspended(NodesIndex)
    // switch to table view to expose the row checkboxes
    wrapper.findComponent({ name: 'FilterToolbar' }).vm.$emit('update:view-mode', 'table')
    await flush()
    // select every node via the header "select all" checkbox
    const checkboxes = wrapper.findAll('[role="checkbox"]')
    expect(checkboxes.length).toBeGreaterThan(1)
    await checkboxes[0]!.trigger('click')
    await flush()
    const drainBtn = wrapper.findAll('button').find(b => b.text().trim() === 'Drain')
    expect(drainBtn).toBeTruthy()
    await drainBtn!.trigger('click')
    await flush()
    expect(post).toHaveBeenCalledWith('/api/v1/nodes/{id}/drain', { params: { path: { id: 'node-a' } } })
    expect(toastSuccess).toHaveBeenCalled()
    expect(fetchNodes).toHaveBeenCalledTimes(2)
  })
})
