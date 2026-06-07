import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TopologyCanvas from '../topology/TopologyCanvas.vue'

const { VueFlowStub, Stub } = vi.hoisted(() => ({
  VueFlowStub: { name: 'VueFlow', props: ['nodes', 'nodeTypes'], template: '<div class="vf"><slot /></div>' },
  Stub: { template: '<div />' },
}))

vi.mock('@vue-flow/core', () => ({
  VueFlow: VueFlowStub,
  useVueFlow: () => ({ onNodeDragStop: vi.fn(), fitView: vi.fn() }),
}))
vi.mock('@vue-flow/background', () => ({ Background: Stub }))
vi.mock('@vue-flow/controls', () => ({ Controls: Stub }))
vi.mock('@vue-flow/minimap', () => ({ MiniMap: Stub }))

const STORAGE_KEY = 'prexor:topology:positions'

function node(id: string, overrides: Record<string, unknown> = {}) {
  return { id, type: 'CONNECTED', status: 'CONNECTED', address: `${id}:8080`, ...overrides }
}
function instance(id: string, nodeId: string, group = 'survival') {
  return { id, node: nodeId, group, state: 'RUNNING', playerCount: 3 }
}

function mount(props: Record<string, unknown> = {}) {
  return mountSuspended(TopologyCanvas, {
    props: { nodes: [node('n1')], instances: [], ...props } as never,
  })
}

beforeEach(() => {
  localStorage.clear()
})

describe('TopologyCanvas', () => {
  it('maps one flow node per cluster node', async () => {
    const wrapper = await mount({ nodes: [node('n1'), node('n2')] })
    const flowNodes = wrapper.findComponent(VueFlowStub).props('nodes')
    expect(flowNodes).toHaveLength(2)
    expect(flowNodes.map((n: { id: string }) => n.id)).toEqual(['n1', 'n2'])
    expect(flowNodes[0].type).toBe('node-box')
  })

  it('carries node status, address and matching instances into flow-node data', async () => {
    const wrapper = await mount({
      nodes: [node('n1')],
      instances: [instance('i1', 'n1'), instance('i2', 'other')],
    })
    const data = wrapper.findComponent(VueFlowStub).props('nodes')[0].data
    expect(data.status).toBe('CONNECTED')
    expect(data.address).toBe('n1:8080')
    expect(data.instances).toHaveLength(1)
    expect(data.instances[0].id).toBe('i1')
  })

  it('narrows the node set by the group filter', async () => {
    const wrapper = await mount({
      nodes: [node('n1'), node('n2')],
      instances: [instance('i1', 'n1', 'survival'), instance('i2', 'n2', 'creative')],
      groupFilter: new Set(['survival']),
    })
    const flowNodes = wrapper.findComponent(VueFlowStub).props('nodes')
    expect(flowNodes.map((n: { id: string }) => n.id)).toEqual(['n1'])
  })

  it('prefers a stored position over the default layout', async () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ n1: { x: 999, y: 777 } }))
    const wrapper = await mount({ nodes: [node('n1')] })
    expect(wrapper.findComponent(VueFlowStub).props('nodes')[0].position).toEqual({ x: 999, y: 777 })
  })

  it('wraps the default layout to a new row every four nodes', async () => {
    const nodes = Array.from({ length: 5 }, (_, i) => node(`n${i}`))
    const wrapper = await mount({ nodes })
    const flowNodes = wrapper.findComponent(VueFlowStub).props('nodes')
    expect(flowNodes[0].position).toEqual({ x: 80, y: 80 })
    // index 4 → col 0, row 1
    expect(flowNodes[4].position).toEqual({ x: 80, y: 80 + 380 })
  })

  it('resetLayout clears the persisted positions', async () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ n1: { x: 1, y: 2 } }))
    const wrapper = await mount({ nodes: [node('n1')] })
    ;(wrapper.vm as unknown as { resetLayout: () => void }).resetLayout()
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toEqual({})
  })
})
