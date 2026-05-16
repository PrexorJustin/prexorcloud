import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { AggregateDeployment } from '~/stores/deploymentsAggregate'
import Deployments from '../workloads/deployments.vue'

const { storeState, fetchAll } = vi.hoisted(() => ({
  storeState: {
    deployments: [] as AggregateDeployment[],
    loading: false,
  },
  fetchAll: vi.fn(),
}))

vi.mock('~/stores/deploymentsAggregate', () => ({
  useDeploymentsAggregateStore: () => ({
    get deployments() { return storeState.deployments },
    get loading() { return storeState.loading },
    fetchAll,
  }),
}))

function deployment(overrides: Partial<AggregateDeployment> = {}): AggregateDeployment {
  return {
    id: 'dep-1',
    groupName: 'lobby',
    revision: 4,
    strategy: 'ROLLING',
    state: 'COMPLETED',
    updatedInstances: 3,
    totalInstances: 3,
    trigger: 'manual',
    createdAt: '2026-05-14T00:00:00Z',
    ...overrides,
  } as AggregateDeployment
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  storeState.deployments = []
  storeState.loading = false
  fetchAll.mockReset().mockResolvedValue(undefined)
})

describe('workloads/deployments', () => {
  it('renders the page header', async () => {
    const wrapper = await mountSuspended(Deployments)
    expect(wrapper.text()).toContain('Deployments')
    expect(wrapper.text()).toContain('Cluster-wide rollout history')
  })

  it('calls fetchAll on mount', async () => {
    await mountSuspended(Deployments)
    expect(fetchAll).toHaveBeenCalledTimes(1)
  })

  it('shows the loading skeleton while the store is loading', async () => {
    storeState.loading = true
    const wrapper = await mountSuspended(Deployments)
    expect(wrapper.find('.overflow-hidden.rounded-2xl').exists()).toBe(false)
  })

  it('shows the empty state when there are no deployments', async () => {
    const wrapper = await mountSuspended(Deployments)
    expect(wrapper.text()).toContain('No deployments yet')
    expect(wrapper.text()).toContain('Trigger a deploy from a group')
  })

  it('renders a row per deployment with group, strategy and progress', async () => {
    storeState.deployments = [
      deployment({ id: 'a', groupName: 'lobby', strategy: 'ROLLING', updatedInstances: 2, totalInstances: 5 }),
      deployment({ id: 'b', groupName: 'survival', strategy: 'BLUE_GREEN', state: 'IN_PROGRESS' }),
    ]
    const wrapper = await mountSuspended(Deployments)
    const rows = wrapper.findAll('a[href^="/groups/"]')
    expect(rows).toHaveLength(2)
    expect(wrapper.text()).toContain('lobby')
    expect(wrapper.text()).toContain('survival')
    expect(wrapper.text()).toContain('ROLLING')
    expect(wrapper.text()).toContain('2 / 5')
  })

  it('filters deployments by the search field', async () => {
    storeState.deployments = [
      deployment({ id: 'a', groupName: 'lobby' }),
      deployment({ id: 'b', groupName: 'survival' }),
    ]
    const wrapper = await mountSuspended(Deployments)
    await wrapper.find('input').setValue('survival')
    await flush()
    const rows = wrapper.findAll('a[href^="/groups/"]')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.attributes('href')).toBe('/groups/survival')
  })

  it('shows the no-matches empty state when the search excludes everything', async () => {
    storeState.deployments = [deployment({ groupName: 'lobby' })]
    const wrapper = await mountSuspended(Deployments)
    await wrapper.find('input').setValue('zzz-nothing')
    await flush()
    expect(wrapper.text()).toContain('No matches')
    expect(wrapper.text()).toContain('Try clearing the filter')
  })

  it('links each row to its group detail page', async () => {
    storeState.deployments = [deployment({ groupName: 'lobby' })]
    const wrapper = await mountSuspended(Deployments)
    expect(wrapper.find('a[href="/groups/lobby"]').exists()).toBe(true)
  })
})
