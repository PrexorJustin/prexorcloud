import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { usePaginatedData } from '../usePaginatedData'

interface Row { id: string }

function makeApi() {
  return {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    del: vi.fn(),
  }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('usePaginatedData', () => {
  it('starts with sensible defaults', () => {
    const api = makeApi()
    const p = usePaginatedData<Row>(api as never, '/items', { immediate: false })
    expect(p.items.value).toEqual([])
    expect(p.page.value).toBe(1)
    expect(p.pageSize.value).toBe(20)
    expect(p.total.value).toBe(0)
    expect(p.totalPages.value).toBe(1)
    expect(p.hasNextPage.value).toBe(false)
    expect(p.hasPrevPage.value).toBe(false)
    expect(p.loading.value).toBe(false)
    expect(p.error.value).toBeNull()
  })

  it('honours a custom pageSize', () => {
    const api = makeApi()
    const p = usePaginatedData<Row>(api as never, '/items', { immediate: false, pageSize: 50 })
    expect(p.pageSize.value).toBe(50)
  })

  it('fetch() requests page+pageSize, populates items, total, and clears loading', async () => {
    const api = makeApi()
    api.get.mockResolvedValue({ data: [{ id: 'a' }, { id: 'b' }], total: 42, page: 1, pageSize: 20 })

    const p = usePaginatedData<Row>(api as never, '/items', { immediate: false })
    await p.fetch()

    expect(api.get).toHaveBeenCalledWith('/items?page=1&pageSize=20')
    expect(p.items.value).toEqual([{ id: 'a' }, { id: 'b' }])
    expect(p.total.value).toBe(42)
    expect(p.totalPages.value).toBe(3)
    expect(p.hasNextPage.value).toBe(true)
    expect(p.hasPrevPage.value).toBe(false)
    expect(p.loading.value).toBe(false)
    expect(p.error.value).toBeNull()
  })

  it('appends search + filter query params when present', async () => {
    const api = makeApi()
    api.get.mockResolvedValue({ data: [], total: 0 })

    const p = usePaginatedData<Row>(api as never, '/items', { immediate: false })
    p.filters.value = { state: 'RUNNING', empty: '' }
    await nextTick() // filter watcher resets page → 1 + fires fetch
    await vi.advanceTimersByTimeAsync(50)

    const calls = api.get.mock.calls.map((c) => c[0])
    expect(calls.some((u) => /state=RUNNING/.test(u))).toBe(true)
    // empty filter values are skipped
    expect(calls.some((u) => /empty=/.test(u))).toBe(false)
  })

  it('captures an error message on rejection and keeps prior items in place', async () => {
    const api = makeApi()
    api.get.mockResolvedValueOnce({ data: [{ id: 'a' }], total: 1 })
    const p = usePaginatedData<Row>(api as never, '/items', { immediate: false })
    await p.fetch()
    expect(p.items.value).toHaveLength(1)

    api.get.mockRejectedValueOnce(new Error('network down'))
    await p.fetch()
    expect(p.error.value).toBe('network down')
    expect(p.items.value).toHaveLength(1)
    expect(p.loading.value).toBe(false)
  })

  it('nextPage / prevPage / goToPage advance the page when there is room', async () => {
    const api = makeApi()
    api.get.mockResolvedValue({ data: [], total: 100, page: 1, pageSize: 20 })

    const p = usePaginatedData<Row>(api as never, '/items', { immediate: false })
    await p.fetch()

    p.nextPage()
    await vi.advanceTimersByTimeAsync(50)
    expect(p.page.value).toBe(2)
    expect(p.hasPrevPage.value).toBe(true)

    p.prevPage()
    await vi.advanceTimersByTimeAsync(50)
    expect(p.page.value).toBe(1)

    p.goToPage(99)
    await vi.advanceTimersByTimeAsync(50)
    expect(p.page.value).toBe(5) // clamped to totalPages
  })

  it('nextPage is a no-op when there are no further pages', async () => {
    const api = makeApi()
    api.get.mockResolvedValue({ data: [], total: 5, page: 1, pageSize: 20 })

    const p = usePaginatedData<Row>(api as never, '/items', { immediate: false })
    await p.fetch()
    expect(p.hasNextPage.value).toBe(false)

    const before = api.get.mock.calls.length
    p.nextPage()
    await vi.advanceTimersByTimeAsync(50)
    expect(api.get.mock.calls.length).toBe(before)
  })

  it('search is debounced and resets to page 1 before refetching', async () => {
    const api = makeApi()
    api.get.mockResolvedValue({ data: [], total: 200, page: 1, pageSize: 20 })

    const p = usePaginatedData<Row>(api as never, '/items', { immediate: false, searchDebounce: 300 })
    await p.fetch()
    // Move to a later page first
    p.goToPage(4)
    await vi.advanceTimersByTimeAsync(50)
    api.get.mockClear()

    p.search.value = 'hello'
    await nextTick()
    // Before debounce elapses → no call
    await vi.advanceTimersByTimeAsync(200)
    expect(api.get).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(200)
    expect(api.get).toHaveBeenCalledTimes(1)
    const url = api.get.mock.calls[0]![0] as string
    expect(url).toMatch(/page=1/)
    expect(url).toMatch(/search=hello/)
  })

  it('filter mutation always resets page back to 1 and refetches immediately', async () => {
    const api = makeApi()
    api.get.mockResolvedValue({ data: [], total: 200, page: 1, pageSize: 20 })

    const p = usePaginatedData<Row>(api as never, '/items', { immediate: false })
    await p.fetch()
    p.goToPage(3)
    await vi.advanceTimersByTimeAsync(50)
    api.get.mockClear()

    p.filters.value = { state: 'STOPPED' }
    await nextTick()
    await vi.advanceTimersByTimeAsync(50)

    expect(p.page.value).toBe(1)
    expect(api.get).toHaveBeenCalled()
    expect(api.get.mock.calls[0]![0]).toMatch(/page=1/)
    expect(api.get.mock.calls[0]![0]).toMatch(/state=STOPPED/)
  })
})
