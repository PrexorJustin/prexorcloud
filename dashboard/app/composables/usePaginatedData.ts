import { getAuthToken } from '~/lib/auth-storage'

interface PaginatedResponse<T> {
  data: T[]
  page: number
  pageSize: number
  total: number
}

interface PaginatedDataOptions {
  /** Items per page (default: 20) */
  pageSize?: number
  /** Fetch immediately on creation (default: true) */
  immediate?: boolean
  /** SSE event types that should trigger a refetch */
  refreshOn?: string[]
  /** Debounce delay for search in ms (default: 300) */
  searchDebounce?: number
}

/**
 * Composable for server-side paginated data fetching.
 * Handles pagination, search, filters, loading states, and SSE-driven auto-refresh.
 * Designed for module pages that use the controller's paginated REST endpoints.
 */
export function usePaginatedData<T>(
  apiFn: ReturnType<typeof useScopedApi>,
  endpoint: MaybeRefOrGetter<string>,
  options: PaginatedDataOptions = {},
) {
  const {
    pageSize: defaultPageSize = 20,
    immediate = true,
    refreshOn = [],
    searchDebounce = 300,
  } = options

  const items = ref<T[]>([]) as Ref<T[]>
  const page = ref(1)
  const pageSize = ref(defaultPageSize)
  const total = ref(0)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const search = ref('')
  const filters = ref<Record<string, string>>({})

  const totalPages = computed(() => Math.ceil(total.value / pageSize.value) || 1)
  const hasNextPage = computed(() => page.value < totalPages.value)
  const hasPrevPage = computed(() => page.value > 1)

  async function fetchData() {
    loading.value = true
    error.value = null
    try {
      const params = new URLSearchParams()
      params.set('page', String(page.value))
      params.set('pageSize', String(pageSize.value))
      if (search.value.trim()) params.set('search', search.value.trim())
      for (const [k, v] of Object.entries(filters.value)) {
        if (v) params.set(k, v)
      }
      const url = `${toValue(endpoint)}?${params}`
      const res = await apiFn.get<PaginatedResponse<T>>(url)
      items.value = res.data ?? []
      total.value = res.total
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch data'
    }
    finally {
      loading.value = false
    }
  }

  function nextPage() {
    if (hasNextPage.value) { page.value++; fetchData() }
  }

  function prevPage() {
    if (hasPrevPage.value) { page.value--; fetchData() }
  }

  function goToPage(p: number) {
    page.value = Math.max(1, Math.min(p, totalPages.value))
    fetchData()
  }

  // Debounced search watcher — resets to page 1
  let searchTimeout: ReturnType<typeof setTimeout> | null = null
  watch(search, () => {
    if (searchTimeout) clearTimeout(searchTimeout)
    searchTimeout = setTimeout(() => {
      page.value = 1
      fetchData()
    }, searchDebounce)
  })

  // Filter changes reset to page 1 and refetch
  watch(filters, () => {
    page.value = 1
    fetchData()
  }, { deep: true })

  // SSE auto-refresh — listens to the controller's event stream
  if (refreshOn.length > 0) {
    let sseSource: EventSource | null = null
    let mounted = false

    function connectSse() {
      const token = getAuthToken()
      if (!token || !mounted) return

      const config = useRuntimeConfig()
      const apiBase = config.public.apiBase as string
      sseSource = new EventSource(`${apiBase}/api/v1/events/stream?token=${token}`)

      sseSource.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data) as { type: string }
          if (refreshOn.includes(data.type)) fetchData()
        }
        catch { /* ignore */ }
      }

      sseSource.onerror = () => {
        sseSource?.close()
        sseSource = null
        // Reconnect after 5s if still mounted
        setTimeout(() => {
          if (mounted && !sseSource) connectSse()
        }, 5000)
      }
    }

    onMounted(() => {
      mounted = true
      connectSse()
    })

    onUnmounted(() => {
      mounted = false
      sseSource?.close()
      sseSource = null
    })
  }

  // Initial fetch
  if (immediate) {
    onMounted(fetchData)
  }

  return {
    items,
    page,
    pageSize,
    total,
    totalPages,
    hasNextPage,
    hasPrevPage,
    loading,
    error,
    search,
    filters,
    fetch: fetchData,
    refresh: fetchData,
    nextPage,
    prevPage,
    goToPage,
  }
}
