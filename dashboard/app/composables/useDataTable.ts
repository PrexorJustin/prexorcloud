import {
  type ColumnDef,
  type SortingState,
  type ColumnFiltersState,
  type VisibilityState,
  type RowSelectionState,
  type PaginationState,
  getCoreRowModel,
  getSortedRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  useVueTable,
} from '@tanstack/vue-table'

interface UseDataTableOptions<T> {
  columns: ColumnDef<T, any>[]
  data: Ref<T[]> | ComputedRef<T[]>
  pageSize?: number
  enableSorting?: boolean
  enableFiltering?: boolean
  enableSelection?: boolean
  enablePagination?: boolean
}

/**
 * Reusable composable for TanStack Vue Table with sorting, filtering,
 * pagination, column visibility, and row selection.
 */
export function useDataTable<T>(options: UseDataTableOptions<T>) {
  const sorting = ref<SortingState>([])
  const columnFilters = ref<ColumnFiltersState>([])
  const columnVisibility = ref<VisibilityState>({})
  const rowSelection = ref<RowSelectionState>({})
  const pagination = ref<PaginationState>({
    pageIndex: 0,
    pageSize: options.pageSize ?? 25,
  })

  const globalFilter = ref('')

  const table = useVueTable({
    get data() { return unref(options.data) },
    columns: options.columns,
    state: {
      get sorting() { return sorting.value },
      get columnFilters() { return columnFilters.value },
      get columnVisibility() { return columnVisibility.value },
      get rowSelection() { return rowSelection.value },
      get pagination() { return pagination.value },
      get globalFilter() { return globalFilter.value },
    },
    onSortingChange: (updater) => {
      sorting.value = typeof updater === 'function' ? updater(sorting.value) : updater
    },
    onColumnFiltersChange: (updater) => {
      columnFilters.value = typeof updater === 'function' ? updater(columnFilters.value) : updater
    },
    onColumnVisibilityChange: (updater) => {
      columnVisibility.value = typeof updater === 'function' ? updater(columnVisibility.value) : updater
    },
    onRowSelectionChange: (updater) => {
      rowSelection.value = typeof updater === 'function' ? updater(rowSelection.value) : updater
    },
    onPaginationChange: (updater) => {
      pagination.value = typeof updater === 'function' ? updater(pagination.value) : updater
    },
    onGlobalFilterChange: (updater) => {
      globalFilter.value = typeof updater === 'function' ? updater(globalFilter.value) : updater
    },
    getCoreRowModel: getCoreRowModel(),
    ...(options.enableSorting !== false ? { getSortedRowModel: getSortedRowModel() } : {}),
    ...(options.enableFiltering !== false ? { getFilteredRowModel: getFilteredRowModel() } : {}),
    ...(options.enablePagination !== false ? { getPaginationRowModel: getPaginationRowModel() } : {}),
    enableRowSelection: options.enableSelection ?? false,
  })

  const selectedRows = computed(() =>
    table.getSelectedRowModel().rows.map(r => r.original),
  )

  const hasSelection = computed(() => Object.keys(rowSelection.value).length > 0)

  function clearSelection() {
    rowSelection.value = {}
  }

  return {
    table,
    sorting,
    columnFilters,
    columnVisibility,
    rowSelection,
    pagination,
    globalFilter,
    selectedRows,
    hasSelection,
    clearSelection,
  }
}
