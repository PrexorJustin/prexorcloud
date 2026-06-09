import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { useSelection } from '../useSelection'

interface Item {
  id: string
  name: string
}

const items: Item[] = [
  { id: 'a', name: 'Alpha' },
  { id: 'b', name: 'Bravo' },
  { id: 'c', name: 'Charlie' },
]

function setup(initial: Item[] = items) {
  const source = ref<Item[]>([...initial])
  const sel = useSelection(source, (i) => i.id)
  return { source, ...sel }
}

describe('useSelection', () => {
  it('starts empty', () => {
    const { selected, count, isAll, isSome, selectedItems } = setup()
    expect(selected.value.size).toBe(0)
    expect(count.value).toBe(0)
    expect(isAll.value).toBe(false)
    expect(isSome.value).toBe(false)
    expect(selectedItems.value).toEqual([])
  })

  it('toggle() adds and removes keys', () => {
    const { toggle, has, count } = setup()
    toggle('a')
    expect(has('a')).toBe(true)
    expect(count.value).toBe(1)
    toggle('a')
    expect(has('a')).toBe(false)
    expect(count.value).toBe(0)
  })

  it('isAll only true when every key is selected', () => {
    const { toggle, isAll, isSome } = setup()
    toggle('a')
    toggle('b')
    expect(isAll.value).toBe(false)
    expect(isSome.value).toBe(true)
    toggle('c')
    expect(isAll.value).toBe(true)
    expect(isSome.value).toBe(false)
  })

  it('isAll is false when item list is empty even with no selection', () => {
    const { isAll } = setup([])
    expect(isAll.value).toBe(false)
  })

  it('toggleAll() selects everything when nothing/some selected', () => {
    const { toggle, toggleAll, selected, isAll } = setup()
    toggle('a')
    toggleAll()
    expect(selected.value.size).toBe(3)
    expect(isAll.value).toBe(true)
  })

  it('toggleAll() clears when everything is already selected', () => {
    const { toggleAll, selected } = setup()
    toggleAll()
    toggleAll()
    expect(selected.value.size).toBe(0)
  })

  it('clear() empties the set', () => {
    const { toggle, clear, count } = setup()
    toggle('a')
    toggle('b')
    clear()
    expect(count.value).toBe(0)
  })

  it('selectedItems mirrors the source filter', () => {
    const { toggle, selectedItems } = setup()
    toggle('a')
    toggle('c')
    expect(selectedItems.value.map((i) => i.id)).toEqual(['a', 'c'])
  })

  it('survives source changes — keys not in source no longer count toward isAll', () => {
    const { source, toggle, isAll, selected } = setup()
    toggle('a')
    toggle('b')
    toggle('c')
    expect(isAll.value).toBe(true)
    source.value = [{ id: 'a', name: 'Alpha' }]
    expect(isAll.value).toBe(true) // only 'a' remains and is selected
    expect(selected.value.size).toBe(3) // stale keys are preserved
  })
})
