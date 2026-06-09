import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import FilterToolbar from '../FilterToolbar.vue'

describe('FilterToolbar', () => {
  it('renders search input with default placeholder', async () => {
    const wrapper = await mountSuspended(FilterToolbar)
    const input = wrapper.find('input[type="text"]')
    expect(input.exists()).toBe(true)
    expect(input.attributes('placeholder')).toBe('Search...')
  })

  it('renders custom search placeholder', async () => {
    const wrapper = await mountSuspended(FilterToolbar, {
      props: { searchPlaceholder: 'Find nodes...' },
    })
    expect(wrapper.find('input').attributes('placeholder')).toBe('Find nodes...')
  })

  it('renders filter buttons when filters provided', async () => {
    const filters = [
      { key: 'RUNNING', label: 'Running' },
      { key: 'STOPPED', label: 'Stopped' },
    ]
    const wrapper = await mountSuspended(FilterToolbar, {
      props: { filters, activeFilters: new Set(['ALL']) },
    })
    const buttons = wrapper.findAll('button').filter(b =>
      b.text() === 'Running' || b.text() === 'Stopped',
    )
    expect(buttons).toHaveLength(2)
  })

  it('does not render filter buttons when no filters', async () => {
    const wrapper = await mountSuspended(FilterToolbar)
    // Only view toggle buttons should exist
    const filterSection = wrapper.findAll('.flex.flex-wrap.gap-2')
    expect(filterSection).toHaveLength(0)
  })

  it('emits toggle-filter when filter button clicked', async () => {
    const filters = [{ key: 'RUNNING', label: 'Running' }]
    const wrapper = await mountSuspended(FilterToolbar, {
      props: { filters, activeFilters: new Set(['ALL']) },
    })
    const filterBtn = wrapper.findAll('button').find(b => b.text().includes('Running'))
    await filterBtn!.trigger('click')
    expect(wrapper.emitted('toggle-filter')).toBeTruthy()
    expect(wrapper.emitted('toggle-filter')![0]).toEqual(['RUNNING'])
  })

  it('emits update:viewMode when view toggle clicked', async () => {
    const wrapper = await mountSuspended(FilterToolbar, {
      props: { viewMode: 'grid', showViewToggle: true },
    })
    const tableBtn = wrapper.find('button[aria-label="Table view"]')
    await tableBtn.trigger('click')
    expect(wrapper.emitted('update:viewMode')).toBeTruthy()
    expect(wrapper.emitted('update:viewMode')![0]).toEqual(['table'])
  })

  it('hides view toggle when showViewToggle is false', async () => {
    const wrapper = await mountSuspended(FilterToolbar, {
      props: { showViewToggle: false },
    })
    expect(wrapper.find('button[aria-label="Grid view"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="Table view"]').exists()).toBe(false)
  })

  it('shows count badge when count provided', async () => {
    const wrapper = await mountSuspended(FilterToolbar, {
      props: { count: 42, countLabel: 'nodes' },
    })
    expect(wrapper.text()).toContain('42')
    expect(wrapper.text()).toContain('nodes')
  })

  it('does not show count badge when count is undefined', async () => {
    const wrapper = await mountSuspended(FilterToolbar)
    expect(wrapper.text()).not.toContain('items')
  })

  it('applies active class to selected filter', async () => {
    const filters = [{ key: 'RUNNING', label: 'Running' }]
    const wrapper = await mountSuspended(FilterToolbar, {
      props: { filters, activeFilters: new Set(['RUNNING']) },
    })
    const filterBtn = wrapper.findAll('button').find(b => b.text().includes('Running'))
    expect(filterBtn!.classes()).toContain('bg-primary')
  })
})
