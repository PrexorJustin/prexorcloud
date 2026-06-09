import { describe, it, expect } from 'vitest'
import { ref, computed } from 'vue'
import type { ColumnDef } from '@tanstack/vue-table'
import { useDataTable } from '../useDataTable'

interface Row { id: string; name: string; score: number }

const columns: ColumnDef<Row, unknown>[] = [
  { accessorKey: 'id' },
  { accessorKey: 'name' },
  { accessorKey: 'score' },
]

const fixture: Row[] = [
  { id: 'a', name: 'Alpha', score: 30 },
  { id: 'b', name: 'Bravo', score: 10 },
  { id: 'c', name: 'Charlie', score: 20 },
]

function setup(opts?: Partial<Parameters<typeof useDataTable<Row>>[0]>) {
  const data = ref<Row[]>([...fixture])
  return {
    data,
    ...useDataTable<Row>({
      columns,
      data,
      pageSize: 25,
      ...opts,
    }),
  }
}

describe('useDataTable', () => {
  it('exposes the underlying tanstack table', () => {
    const { table } = setup()
    expect(table.getRowModel().rows).toHaveLength(3)
  })

  it('defaults pagination to pageIndex=0 and the configured pageSize', () => {
    const { pagination } = setup({ pageSize: 5 })
    expect(pagination.value.pageIndex).toBe(0)
    expect(pagination.value.pageSize).toBe(5)
  })

  it('falls back to pageSize=25 when none is given', () => {
    const data = ref<Row[]>([...fixture])
    const { pagination } = useDataTable<Row>({ columns, data })
    expect(pagination.value.pageSize).toBe(25)
  })

  it('mutating sorting through the table updates the ref state', () => {
    const { table, sorting } = setup()
    table.setSorting([{ id: 'score', desc: false }])
    expect(sorting.value).toEqual([{ id: 'score', desc: false }])
    // Rows actually sort: lowest score first
    expect(table.getRowModel().rows[0]!.original.score).toBe(10)
  })

  it('global filter narrows the row model', () => {
    const { table, globalFilter } = setup()
    table.setGlobalFilter('alp')
    expect(globalFilter.value).toBe('alp')
    expect(table.getRowModel().rows.map(r => r.original.id)).toEqual(['a'])
  })

  it('row selection feeds selectedRows and hasSelection (when enabled)', () => {
    const { table, rowSelection, selectedRows, hasSelection, clearSelection } =
      setup({ enableSelection: true })
    table.getRowModel().rows[0]!.toggleSelected(true)
    table.getRowModel().rows[2]!.toggleSelected(true)
    expect(hasSelection.value).toBe(true)
    expect(selectedRows.value.map(r => r.id).sort()).toEqual(['a', 'c'])
    expect(Object.keys(rowSelection.value).length).toBe(2)
    clearSelection()
    expect(hasSelection.value).toBe(false)
    expect(selectedRows.value).toEqual([])
  })

  it('accepts a computed data source', () => {
    const src = ref<Row[]>([...fixture])
    const filtered = computed(() => src.value.filter(r => r.score >= 20))
    const { table } = useDataTable<Row>({ columns, data: filtered })
    expect(table.getRowModel().rows).toHaveLength(2)
    src.value = [{ id: 'x', name: 'Xray', score: 99 }, { id: 'y', name: 'Yankee', score: 5 }]
    expect(table.getRowModel().rows.map(r => r.original.id)).toEqual(['x'])
  })

  it('column visibility state is mutable via the table API', () => {
    const { table, columnVisibility } = setup()
    table.setColumnVisibility({ score: false })
    expect(columnVisibility.value.score).toBe(false)
    expect(table.getVisibleLeafColumns().map(c => c.id)).toEqual(['id', 'name'])
  })
})
