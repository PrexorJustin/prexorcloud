import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import Index from '../index.vue'

const { store, fetchOverview, connectSse, disconnectSse } = vi.hoisted(() => ({
  store: {
    stats: null as Record<string, number> | null,
    loading: false,
    history: {
      nodeCount: [] as number[],
      playerCount: [] as number[],
      instanceCount: [] as number[],
      groupCount: [] as number[],
    },
  },
  fetchOverview: vi.fn(),
  connectSse: vi.fn(),
  disconnectSse: vi.fn(),
}))

vi.mock('~/stores/overview', () => ({
  useOverviewStore: () => reactive(Object.assign(store, { fetchOverview, connectSse, disconnectSse })),
}))

beforeEach(() => {
  store.stats = null
  store.loading = false
  store.history = { nodeCount: [], playerCount: [], instanceCount: [], groupCount: [] }
  fetchOverview.mockReset()
  connectSse.mockReset()
  disconnectSse.mockReset()
})

describe('Overview page', () => {
  it('renders the page header title and breadcrumb', async () => {
    const wrapper = await mountSuspended(Index)
    expect(wrapper.text()).toContain('Overview')
    expect(wrapper.text()).toContain('Production')
    expect(wrapper.text()).toContain('cluster')
  })

  it('renders all four stat cards with their titles', async () => {
    const wrapper = await mountSuspended(Index)
    expect(wrapper.text()).toContain('Nodes')
    expect(wrapper.text()).toContain('Players online')
    expect(wrapper.text()).toContain('Instances')
    expect(wrapper.text()).toContain('Groups')
  })

  it('fetches the overview and connects SSE on mount', async () => {
    await mountSuspended(Index)
    expect(fetchOverview).toHaveBeenCalledTimes(1)
    expect(connectSse).toHaveBeenCalledTimes(1)
  })

  it('shows zero for every stat when the store has no data', async () => {
    const wrapper = await mountSuspended(Index)
    const values = wrapper.findAll('.reef-stat__value')
    expect(values).toHaveLength(4)
    values.forEach(v => expect(v.text()).toBe('0'))
  })

  it('renders the store-provided stat values once loaded', async () => {
    store.stats = { nodeCount: 3, playerCount: 142, instanceCount: 9, groupCount: 5 }
    const wrapper = await mountSuspended(Index)
    const text = wrapper.findAll('.reef-stat__value').map(v => v.text())
    expect(text).toEqual(['9', '142', '3', '5'])
  })

  it('makes linked stat cards navigable and leaves player card as a plain div', async () => {
    store.stats = { nodeCount: 1, playerCount: 0, instanceCount: 0, groupCount: 0 }
    const wrapper = await mountSuspended(Index)
    const targets = wrapper.findAll('.reef-stat--link').map(el => el.attributes('to'))
    expect(targets).toContain('/nodes')
    expect(targets).toContain('/instances')
    expect(targets).toContain('/groups')
    expect(wrapper.findAll('.reef-stat')).toHaveLength(4)
    expect(wrapper.findAll('.reef-stat--link')).toHaveLength(3)
  })
})
