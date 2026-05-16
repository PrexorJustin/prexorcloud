import { computed, ref, type ComputedRef, type Ref } from "vue"

/**
 * Generic multi-select primitive for list pages. Decouples the "what is
 * selected" state from the items themselves so it survives store refreshes.
 *
 *   const { selected, toggle, toggleAll, clear, has, count, isAll } =
 *     useSelection(filteredInstances, i => i.id)
 *
 *   <Checkbox :model-value="has(item.id)" @update:model-value="toggle(item.id)" />
 *   <Checkbox :model-value="isAll" @update:model-value="toggleAll()" />
 *
 * Pairs with `<BulkActionBar>` which animates in when `count > 0`.
 */
export function useSelection<T>(
  items: Ref<T[]> | ComputedRef<T[]> | Ref<readonly T[]>,
  keyFn: (item: T) => string,
) {
  const selected = ref(new Set<string>()) as Ref<Set<string>>

  const count = computed(() => selected.value.size)
  const isAll = computed(() =>
    items.value.length > 0 && items.value.every(i => selected.value.has(keyFn(i))),
  )
  const isSome = computed(() => count.value > 0 && !isAll.value)
  const selectedItems = computed(() =>
    items.value.filter(i => selected.value.has(keyFn(i))),
  )

  function has(key: string): boolean {
    return selected.value.has(key)
  }

  function toggle(key: string) {
    const next = new Set(selected.value)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    selected.value = next
  }

  function toggleAll() {
    if (isAll.value) {
      selected.value = new Set()
    } else {
      selected.value = new Set(items.value.map(keyFn))
    }
  }

  function clear() {
    selected.value = new Set()
  }

  return { selected, selectedItems, count, isAll, isSome, has, toggle, toggleAll, clear }
}
