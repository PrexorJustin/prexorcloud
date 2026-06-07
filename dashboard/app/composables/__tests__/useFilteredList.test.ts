import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { useFilteredList } from '../useFilteredList'

interface Item {
  name: string
  status: string
}

const items: Item[] = [
  { name: 'Lobby', status: 'RUNNING' },
  { name: 'Survival', status: 'STOPPED' },
  { name: 'Creative', status: 'RUNNING' },
  { name: 'BedWars', status: 'STARTING' },
]

function setup(defaultView?: 'grid' | 'table') {
  const source = ref(items)
  return useFilteredList(source, {
    searchFields: (item: Item) => [item.name],
    filterField: (item: Item) => item.status,
    defaultView,
  })
}

describe('useFilteredList', () => {
  it('returns all items when no filters applied', () => {
    const { filteredItems } = setup()
    expect(filteredItems.value).toHaveLength(4)
  })

  it('filters by search query (case-insensitive)', () => {
    const { search, filteredItems } = setup()
    search.value = 'lobby'
    expect(filteredItems.value).toHaveLength(1)
    expect(filteredItems.value[0]?.name).toBe('Lobby')
  })

  it('filters by search with partial match', () => {
    const { search, filteredItems } = setup()
    search.value = 'sur'
    expect(filteredItems.value).toHaveLength(1)
    expect(filteredItems.value[0]?.name).toBe('Survival')
  })

  it('returns empty when search matches nothing', () => {
    const { search, filteredItems } = setup()
    search.value = 'nonexistent'
    expect(filteredItems.value).toHaveLength(0)
  })

  it('filters by status via toggleFilter', () => {
    const { toggleFilter, filteredItems } = setup()
    toggleFilter('RUNNING')
    expect(filteredItems.value).toHaveLength(2)
    expect(filteredItems.value.every(i => i.status === 'RUNNING')).toBe(true)
  })

  it('combines search and filter', () => {
    const { search, toggleFilter, filteredItems } = setup()
    toggleFilter('RUNNING')
    search.value = 'cre'
    expect(filteredItems.value).toHaveLength(1)
    expect(filteredItems.value[0]?.name).toBe('Creative')
  })

  it('toggling same filter twice removes it and resets to ALL', () => {
    const { toggleFilter, activeFilters } = setup()
    toggleFilter('RUNNING')
    expect(activeFilters.value.has('RUNNING')).toBe(true)
    toggleFilter('RUNNING')
    expect(activeFilters.value.has('ALL')).toBe(true)
  })

  it('toggling ALL resets all filters', () => {
    const { toggleFilter, activeFilters } = setup()
    toggleFilter('RUNNING')
    toggleFilter('STOPPED')
    toggleFilter('ALL')
    expect(activeFilters.value).toEqual(new Set(['ALL']))
  })

  it('supports multiple active filters', () => {
    const { toggleFilter, filteredItems } = setup()
    toggleFilter('RUNNING')
    toggleFilter('STOPPED')
    expect(filteredItems.value).toHaveLength(3)
  })

  it('defaults viewMode to grid', () => {
    const { viewMode } = setup()
    expect(viewMode.value).toBe('grid')
  })

  it('respects defaultView option', () => {
    const { viewMode } = setup('table')
    expect(viewMode.value).toBe('table')
  })

  it('viewMode can be toggled', () => {
    const { viewMode } = setup()
    viewMode.value = 'table'
    expect(viewMode.value).toBe('table')
  })
})
