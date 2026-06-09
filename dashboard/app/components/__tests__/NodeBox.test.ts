import { describe, it, expect, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import NodeBox from '../topology/NodeBox.vue'
import type { NodeBoxData } from '../topology/NodeBox.vue'

// vue-flow's <Handle> needs a flow provider that isn't present in a bare
// component mount — stub it (and Position) so NodeBox renders standalone.
vi.mock('@vue-flow/core', () => ({
  Handle: { name: 'Handle', template: '<div />' },
  Position: { Left: 'left', Right: 'right' },
}))

function data(overrides: Partial<NodeBoxData> = {}): NodeBoxData {
  return {
    nodeId: 'node-a',
    status: 'ONLINE',
    address: '10.0.0.1:8080',
    instances: [
      { id: 'lobby-1', group: 'lobby', state: 'RUNNING', playerCount: 12 },
      { id: 'lobby-2', group: 'lobby', state: 'CRASHED', playerCount: 0 },
    ],
    ...overrides,
  }
}

describe('NodeBox', () => {
  it('renders the node id and address', async () => {
    const wrapper = await mountSuspended(NodeBox, { props: { data: data() } })
    expect(wrapper.text()).toContain('node-a')
    expect(wrapper.text()).toContain('10.0.0.1:8080')
  })

  it('omits the address line when none is given', async () => {
    const wrapper = await mountSuspended(NodeBox, { props: { data: data({ address: undefined }) } })
    expect(wrapper.text()).not.toContain('10.0.0.1')
  })

  it('shows the node status label', async () => {
    const wrapper = await mountSuspended(NodeBox, { props: { data: data({ status: 'UNREACHABLE' }) } })
    expect(wrapper.text()).toContain('Unreachable')
  })

  it('renders the CPU sparkline only when cpuHistory has points', async () => {
    const without = await mountSuspended(NodeBox, { props: { data: data() } })
    expect(without.text()).not.toContain('CPU')
    const withHistory = await mountSuspended(NodeBox, {
      props: { data: data({ cpuHistory: [1, 2, 3] }) },
    })
    expect(withHistory.text()).toContain('CPU')
  })

  it('renders one pill per instance with id and player count', async () => {
    const wrapper = await mountSuspended(NodeBox, { props: { data: data() } })
    const pills = wrapper.findAll('button')
    expect(pills).toHaveLength(2)
    expect(wrapper.text()).toContain('lobby-1')
    expect(wrapper.text()).toContain('lobby-2')
    expect(wrapper.text()).toContain('12')
  })

  it('shows "No instances" when the node is empty', async () => {
    const wrapper = await mountSuspended(NodeBox, { props: { data: data({ instances: [] }) } })
    expect(wrapper.text()).toContain('No instances')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('emits instance-click with the instance id when a pill is clicked', async () => {
    const wrapper = await mountSuspended(NodeBox, { props: { data: data() } })
    await wrapper.findAll('button')[0]!.trigger('click')
    expect(wrapper.emitted('instance-click')?.[0]).toEqual(['lobby-1'])
  })
})
