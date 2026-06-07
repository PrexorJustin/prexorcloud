import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'

import ActivityPage from '../observability/activity.vue'

const { activityState, fetchEvents } = vi.hoisted(() => ({
  activityState: { events: [] as unknown[], loading: false },
  fetchEvents: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
vi.mock('~/stores/activity', () => ({
  useActivityStore: () => ({
    get events() { return activityState.events },
    get loading() { return activityState.loading },
    fetchEvents,
  }),
}))

const now = new Date().toISOString()

const sampleEvents = [
  { id: 'e1', type: 'INSTANCE_STARTED', actor: 'alice', message: 'Instance lobby-1 started', route: '/instances/lobby-1', timestamp: now },
  { id: 'e2', type: 'NODE_DRAIN_REQUESTED', actor: 'system', message: 'Node node-a draining', timestamp: now },
]

beforeEach(() => {
  activityState.events = []
  activityState.loading = false
  fetchEvents.mockReset().mockResolvedValue(undefined)
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

describe('ActivityPage', () => {
  it('renders the page header and fetches events on mount', async () => {
    const wrapper = await mountSuspended(ActivityPage)
    expect(wrapper.text()).toContain('Activity')
    expect(wrapper.text()).toContain('Every action, deployment, and state change')
    expect(fetchEvents).toHaveBeenCalled()
  })

  it('shows the loading skeleton while the store is loading', async () => {
    activityState.loading = true
    const wrapper = await mountSuspended(ActivityPage)
    expect(wrapper.text()).not.toContain('No activity yet')
  })

  it('shows the empty state when there are no events', async () => {
    const wrapper = await mountSuspended(ActivityPage)
    expect(wrapper.text()).toContain('No activity yet')
    expect(wrapper.text()).toContain('Cluster events will appear here as they happen')
  })

  it('renders the event timeline once events are loaded', async () => {
    activityState.events = sampleEvents
    const wrapper = await mountSuspended(ActivityPage)
    expect(wrapper.text()).toContain('Instance lobby-1 started')
    expect(wrapper.text()).toContain('INSTANCE_STARTED')
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('Today')
  })

  it('filters the timeline by the search box', async () => {
    activityState.events = sampleEvents
    const wrapper = await mountSuspended(ActivityPage)
    await wrapper.find('input').setValue('draining')
    await flush()
    expect(wrapper.text()).toContain('Node node-a draining')
    expect(wrapper.text()).not.toContain('Instance lobby-1 started')
  })

  it('shows the no-matches empty state for a search that hits nothing', async () => {
    activityState.events = sampleEvents
    const wrapper = await mountSuspended(ActivityPage)
    await wrapper.find('input').setValue('nothing-matches-this')
    await flush()
    expect(wrapper.text()).toContain('No matches')
  })
})
