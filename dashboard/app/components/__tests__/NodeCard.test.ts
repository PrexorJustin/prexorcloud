import { describe, it, expect, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import NodeCard from '../nodes/NodeCard.vue'
import type { ConnectedNode, DisconnectedNode, PendingNode } from '~/types/api'

// `navigateTo` is a Nuxt auto-import resolved at compile time — vi.stubGlobal
// can't intercept it, so the card-click → navigate wiring isn't asserted here.
vi.stubGlobal('navigateTo', vi.fn())

const connected: ConnectedNode = {
  id: 'node-a', address: '10.0.0.1:8443', type: 'CONNECTED', status: 'ONLINE',
  cpuUsage: 42.6, totalMemoryMb: 16384, usedMemoryMb: 8192, freeDiskMb: 100000,
  totalDiskMb: 200000, instanceCount: 5,
  connectedSince: '2026-05-01T00:00:00Z', lastHeartbeat: '2026-05-14T00:00:00Z',
}

const pending: PendingNode = {
  id: 'node-pending', type: 'PENDING', status: 'PENDING',
  tokenId: 'tok-1', joinToken: 'jt-1', expiresAt: '2026-06-01T12:00:00Z',
}

const disconnected: DisconnectedNode = {
  id: 'node-gone', type: 'DISCONNECTED', status: 'OFFLINE',
  firstSeen: '2026-04-01T00:00:00Z', lastSeen: '2026-05-10T00:00:00Z',
}

describe('NodeCard', () => {
  it('renders a connected node with its address and metrics', async () => {
    const wrapper = await mountSuspended(NodeCard, { props: { node: connected } })
    expect(wrapper.text()).toContain('node-a')
    expect(wrapper.text()).toContain('10.0.0.1:8443')
    // cpuUsage is rendered rounded to a whole percent.
    expect(wrapper.text()).toContain('43%')
    // instanceCount shows in the metrics row.
    expect(wrapper.text()).toContain('5')
  })

  it('shows "Awaiting connection" and an expiry for a pending node', async () => {
    const wrapper = await mountSuspended(NodeCard, { props: { node: pending } })
    expect(wrapper.text()).toContain('Awaiting connection')
    expect(wrapper.text()).toContain('Expires')
    // No CPU metric row for a non-connected node.
    expect(wrapper.text()).not.toContain('%')
  })

  it('shows "Disconnected" and a last-seen timestamp for a disconnected node', async () => {
    const wrapper = await mountSuspended(NodeCard, { props: { node: disconnected } })
    expect(wrapper.text()).toContain('Disconnected')
    expect(wrapper.text()).toContain('Last seen')
  })

  it('applies the poof animation class while exploding', async () => {
    const wrapper = await mountSuspended(NodeCard, { props: { node: connected, exploding: true } })
    expect(wrapper.find('.animate-card-poof').exists()).toBe(true)
  })
})
