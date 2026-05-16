import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import AuditIndex from '../audit/index.vue'

const { fetchEntries, auditStore } = vi.hoisted(() => ({
  fetchEntries: vi.fn(),
  auditStore: {
    entries: [] as Record<string, unknown>[],
    loading: false,
    offset: 0,
    pageSize: 50,
    hasMore: false,
  },
}))

vi.mock('~/stores/audit', () => ({
  useAuditStore: () => reactive(Object.assign(auditStore, { fetchEntries })),
}))

function entry(overrides: Record<string, unknown> = {}) {
  return {
    id: 1, username: 'alice', action: 'INSTANCE_CREATED', resourceType: 'instance',
    resourceId: 'lobby-1', ipAddress: '10.0.0.1', details: 'started lobby-1',
    createdAt: '2026-05-14T00:00:00Z',
    ...overrides,
  }
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  fetchEntries.mockReset()
  auditStore.entries = []
  auditStore.loading = false
  auditStore.offset = 0
  auditStore.hasMore = false
})

describe('AuditIndex', () => {
  it('renders the page header', async () => {
    const wrapper = await mountSuspended(AuditIndex)
    expect(wrapper.text()).toContain('Audit log')
    expect(wrapper.text()).toContain('A record of who did what')
  })

  it('fetches audit entries on mount', async () => {
    await mountSuspended(AuditIndex)
    expect(fetchEntries).toHaveBeenCalled()
  })

  it('shows the empty state when there are no entries', async () => {
    const wrapper = await mountSuspended(AuditIndex)
    expect(wrapper.text()).toContain('No audit entries')
  })

  it('renders entry rows when the store has entries', async () => {
    auditStore.entries = [entry(), entry({ id: 2, username: 'bob', action: 'GROUP_DELETED' })]
    const wrapper = await mountSuspended(AuditIndex)
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('bob')
    expect(wrapper.text()).toContain('INSTANCE_CREATED')
    expect(wrapper.text()).not.toContain('No audit entries')
  })

  it('expands a row with a before/after diff on click', async () => {
    auditStore.entries = [entry({ before: { a: 1 }, after: { a: 2 } })]
    const wrapper = await mountSuspended(AuditIndex)
    // The DiffViewer (and its Before/After labels) only renders once expanded.
    expect(wrapper.html()).not.toContain('+1 addition')
    await wrapper.find('.cursor-pointer').trigger('click')
    await flush()
    expect(wrapper.html()).toContain('Before')
    expect(wrapper.html()).toContain('After')
  })

  it('disables the pagination buttons when there is no prev/next page', async () => {
    auditStore.entries = [entry()]
    const wrapper = await mountSuspended(AuditIndex)
    const buttons = wrapper.findAll('button').filter(b =>
      b.text() === 'Previous' || b.text() === 'Next',
    )
    expect(buttons).toHaveLength(2)
    expect(buttons.every(b => b.attributes('disabled') !== undefined)).toBe(true)
  })

  it('fetches the next page when Next is clicked and hasMore is true', async () => {
    auditStore.entries = [entry()]
    auditStore.hasMore = true
    const wrapper = await mountSuspended(AuditIndex)
    const next = wrapper.findAll('button').find(b => b.text() === 'Next')
    await next!.trigger('click')
    expect(fetchEntries).toHaveBeenCalledWith(50)
  })

  it('filters entries by the search term', async () => {
    auditStore.entries = [
      entry({ id: 1, username: 'alice' }),
      entry({ id: 2, username: 'bob', resourceId: 'node-x', details: 'drained node-x' }),
    ]
    const wrapper = await mountSuspended(AuditIndex)
    await wrapper.find('input').setValue('bob')
    await flush()
    expect(wrapper.text()).toContain('bob')
    expect(wrapper.text()).not.toContain('alice')
  })
})
