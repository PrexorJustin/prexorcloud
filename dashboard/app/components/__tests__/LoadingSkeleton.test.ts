import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import LoadingSkeleton from '../LoadingSkeleton.vue'

describe('LoadingSkeleton', () => {
  it('defaults to grid mode with 6 placeholder cards', async () => {
    const wrapper = await mountSuspended(LoadingSkeleton)
    const grid = wrapper.find('.grid')
    expect(grid.exists()).toBe(true)
    // Six animated card placeholders, one per `count`.
    expect(grid.findAll('.animate-pulse')).toHaveLength(6)
  })

  it('honours a custom card count in grid mode', async () => {
    const wrapper = await mountSuspended(LoadingSkeleton, { props: { count: 3 } })
    expect(wrapper.find('.grid').findAll('.animate-pulse')).toHaveLength(3)
  })

  it('renders the table skeleton when mode is "table"', async () => {
    const wrapper = await mountSuspended(LoadingSkeleton, { props: { mode: 'table', count: 4 } })
    // Table mode is a single pulsing container, no grid.
    expect(wrapper.find('.grid').exists()).toBe(false)
    const container = wrapper.find('.animate-pulse')
    expect(container.exists()).toBe(true)
    // One header bar (.h-10) + `count` row bars (.h-12).
    expect(container.findAll('.h-12')).toHaveLength(4)
    expect(container.findAll('.h-10')).toHaveLength(1)
  })
})
