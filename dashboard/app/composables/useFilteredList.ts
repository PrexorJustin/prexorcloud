import type { MaybeRefOrGetter } from 'vue'

export interface FilterOption {
  key: string
  label: string
  icon?: Component
}

export function useFilteredList<T>(
  items: MaybeRefOrGetter<T[]>,
  options: {
    searchFields: (item: T) => (string | undefined | null)[]
    filterField?: (item: T) => string
    defaultView?: 'grid' | 'table'
  },
) {
  const search = ref('')
  const activeFilters = ref<Set<string>>(new Set(['ALL']))
  const viewMode = ref<'grid' | 'table'>(options.defaultView ?? 'grid')

  function toggleFilter(key: string) {
    if (key === 'ALL') {
      activeFilters.value = new Set(['ALL'])
      return
    }
    const next = new Set(activeFilters.value)
    next.delete('ALL')
    if (next.has(key)) next.delete(key)
    else next.add(key)
    activeFilters.value = next.size === 0 ? new Set(['ALL']) : next
  }

  const filteredItems = computed(() => {
    let result = toValue(items)

    if (!activeFilters.value.has('ALL') && options.filterField) {
      result = result.filter(item => activeFilters.value.has(options.filterField!(item)))
    }

    const q = search.value.toLowerCase().trim()
    if (q) {
      result = result.filter(item =>
        options.searchFields(item).some(field => field?.toLowerCase().includes(q)),
      )
    }

    return result
  })

  return { search, activeFilters, viewMode, filteredItems, toggleFilter }
}
