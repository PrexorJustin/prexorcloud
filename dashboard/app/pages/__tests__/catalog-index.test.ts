import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import Catalog from '../catalog/index.vue'
import type { CatalogEntry } from '~/types/api'

const { store, fetchCatalog, addVersion } = vi.hoisted(() => ({
  store: { entries: [] as CatalogEntry[], loading: false },
  fetchCatalog: vi.fn(),
  addVersion: vi.fn(),
}))

vi.mock('~/stores/catalog', () => ({
  useCatalogStore: () => reactive(Object.assign(store, { fetchCatalog, addVersion })),
}))
vi.stubGlobal('navigateTo', vi.fn())

function entry(overrides: Partial<CatalogEntry> = {}): CatalogEntry {
  return {
    platform: 'paper', category: 'SERVER', configFormat: 'YAML',
    versions: [
      { version: '1.21.1', downloadUrl: 'u1', recommended: true },
      { version: '1.20.4', downloadUrl: 'u2', recommended: false },
    ],
    ...overrides,
  }
}

beforeEach(() => {
  store.entries = []
  store.loading = false
  fetchCatalog.mockReset()
  addVersion.mockReset()
})

describe('Catalog page', () => {
  it('renders the page header title and description', async () => {
    const wrapper = await mountSuspended(Catalog)
    expect(wrapper.text()).toContain('Catalog')
    expect(wrapper.text()).toContain('Server platforms and the versions available to instances.')
  })

  it('fetches the catalog on mount', async () => {
    await mountSuspended(Catalog)
    expect(fetchCatalog).toHaveBeenCalledTimes(1)
  })

  it('shows loading skeletons while the store is loading', async () => {
    store.loading = true
    const wrapper = await mountSuspended(Catalog)
    expect(wrapper.findAll('.animate-pulse')).toHaveLength(6)
  })

  it('shows the empty state when the catalog has no entries', async () => {
    const wrapper = await mountSuspended(Catalog)
    expect(wrapper.text()).toContain('No platforms found')
    expect(wrapper.text()).toContain('Add a platform to get started')
  })

  it('renders a platform card per entry in grid view', async () => {
    store.entries = [entry(), entry({ platform: 'velocity', category: 'PROXY' })]
    const wrapper = await mountSuspended(Catalog)
    expect(wrapper.text()).toContain('paper')
    expect(wrapper.text()).toContain('velocity')
  })

  it('filters entries by the search query', async () => {
    store.entries = [entry(), entry({ platform: 'velocity', category: 'PROXY' })]
    const wrapper = await mountSuspended(Catalog)
    await wrapper.find('input').setValue('velocity')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('velocity')
    expect(wrapper.text()).not.toContain('No platforms found')
    const cards = wrapper.findAll('p.uppercase').map(p => p.text())
    expect(cards).toEqual(['velocity'])
  })

  it('shows the filter-adjust hint when a search matches nothing', async () => {
    store.entries = [entry()]
    const wrapper = await mountSuspended(Catalog)
    await wrapper.find('input').setValue('nonexistent-platform')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('No platforms found')
    expect(wrapper.text()).toContain('Try adjusting your filters')
  })

  it('switches to the table view and renders rows with column headers', async () => {
    store.entries = [entry()]
    const wrapper = await mountSuspended(Catalog)
    const tableToggle = wrapper.find('button[aria-label="Table view"]')
    await tableToggle.trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Recommended')
    expect(wrapper.text()).toContain('1.21.1')
  })
})
