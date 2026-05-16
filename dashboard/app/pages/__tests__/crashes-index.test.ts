import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import Crashes from '../crashes/index.vue'
import type { CrashRecord } from '~/types/api'

const { store, fetchCrashes, fetchCrash, fetchTrend } = vi.hoisted(() => ({
  store: {
    crashes: [] as CrashRecord[],
    loading: false,
    offset: 0,
    pageSize: 50,
    hasMore: false,
    trend: null as { buckets: { count: number }[]; total: number } | null,
    trendLoading: false,
  },
  fetchCrashes: vi.fn(),
  fetchCrash: vi.fn(),
  fetchTrend: vi.fn(),
}))

vi.mock('~/stores/crashes', () => ({
  useCrashesStore: () => reactive(Object.assign(store, { fetchCrashes, fetchCrash, fetchTrend })),
}))

function crash(overrides: Partial<CrashRecord> = {}): CrashRecord {
  return {
    id: 'c1', instanceId: 'lobby-1', group: 'lobby', node: 'node-a',
    classification: 'OOM', exitCode: 137, uptimeMs: 60000,
    crashedAt: '2026-05-14T00:00:00Z', causeSummary: 'heap exhausted',
    signature: 'sig-abc', logTail: null,
    ...overrides,
  } as CrashRecord
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  store.crashes = []
  store.loading = false
  store.offset = 0
  store.hasMore = false
  store.trend = null
  fetchCrashes.mockReset()
  fetchCrash.mockReset().mockResolvedValue(null)
  fetchTrend.mockReset()
})

describe('Crashes page', () => {
  it('renders the page header title and description', async () => {
    const wrapper = await mountSuspended(Crashes)
    expect(wrapper.text()).toContain('Crashes')
    expect(wrapper.text()).toContain('Non-graceful instance exits')
  })

  it('fetches crashes and the trend on mount', async () => {
    await mountSuspended(Crashes)
    expect(fetchCrashes).toHaveBeenCalledTimes(1)
    expect(fetchTrend).toHaveBeenCalledWith('24h', 24)
  })

  it('shows the loading skeleton while the store is loading', async () => {
    store.loading = true
    const wrapper = await mountSuspended(Crashes)
    expect(wrapper.findAll('.animate-pulse').length).toBeGreaterThan(0)
  })

  it('shows the empty state when there are no crashes', async () => {
    const wrapper = await mountSuspended(Crashes)
    expect(wrapper.text()).toContain('No crashes')
    expect(wrapper.text()).toContain('Healthy is good')
  })

  it('renders a row per crash with instance, group and classification', async () => {
    store.crashes = [crash(), crash({ id: 'c2', instanceId: 'lobby-2', classification: 'SIGKILL' })]
    const wrapper = await mountSuspended(Crashes)
    expect(wrapper.text()).toContain('lobby-1')
    expect(wrapper.text()).toContain('lobby-2')
    expect(wrapper.text()).toContain('Out of memory')
    expect(wrapper.text()).toContain('Killed')
  })

  it('renders the 24h trend summary when the store has trend data', async () => {
    store.crashes = [crash()]
    store.trend = { buckets: [{ count: 1 }, { count: 2 }], total: 3 }
    const wrapper = await mountSuspended(Crashes)
    expect(wrapper.text()).toContain('Last 24h')
    expect(wrapper.text()).toContain('3')
  })

  it('opens the detail sheet and fetches the full crash when a row is clicked', async () => {
    store.crashes = [crash()]
    fetchCrash.mockResolvedValue(crash({ logTail: 'OutOfMemoryError\n  at ...' }))
    const wrapper = await mountSuspended(Crashes)
    const row = wrapper.findAll('div.cursor-pointer').find(d => d.text().includes('lobby-1'))!
    await row.trigger('click')
    await flush()
    expect(fetchCrash).toHaveBeenCalledWith('c1')
    // DetailSheet (reka-ui Sheet) teleports its content to document.body.
    expect(document.body.textContent).toContain('Crash report')
    expect(document.body.textContent).toContain('OutOfMemoryError')
  })

  it('filters the table down to crashes matching the search term', async () => {
    store.crashes = [crash(), crash({ id: 'c2', instanceId: 'survival-9', group: 'survival' })]
    const wrapper = await mountSuspended(Crashes)
    await wrapper.find('input').setValue('survival')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('survival-9')
    expect(wrapper.text()).not.toContain('lobby-1')
  })

  it('paginates forward when there are more pages', async () => {
    store.crashes = [crash()]
    store.hasMore = true
    const wrapper = await mountSuspended(Crashes)
    const next = wrapper.findAll('button').find(b => b.text() === 'Next')!
    expect(next.attributes('disabled')).toBeUndefined()
    await next.trigger('click')
    expect(fetchCrashes).toHaveBeenCalledWith(store.pageSize)
  })
})
