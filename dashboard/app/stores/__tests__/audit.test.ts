import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useAuditStore } from '../audit'

const mockGET = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn(), PATCH: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

describe('useAuditStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGET.mockReset()
    vi.mocked(toast.error).mockReset()
  })

  it('preserves before/after snapshots on returned entries', async () => {
    const entries = [
      {
        id: 1,
        username: 'admin',
        action: 'group.update',
        resourceType: 'group',
        resourceId: 'lobby',
        details: '{}',
        before: { name: 'lobby', minInstances: 1 },
        after: { name: 'lobby', minInstances: 3 },
        ipAddress: '127.0.0.1',
        createdAt: '2026-05-11T12:00:00Z',
      },
      {
        id: 2,
        username: 'admin',
        action: 'group.delete',
        resourceType: 'group',
        resourceId: 'old-lobby',
        details: '{}',
        before: { name: 'old-lobby' },
        after: null,
        ipAddress: '127.0.0.1',
        createdAt: '2026-05-11T12:01:00Z',
      },
    ]
    mockGET.mockResolvedValueOnce({ data: { data: entries, total: entries.length } })

    const store = useAuditStore()
    await store.fetchEntries()

    expect(store.entries).toHaveLength(2)
    expect(store.entries[0]!.before).toEqual({ name: 'lobby', minInstances: 1 })
    expect(store.entries[0]!.after).toEqual({ name: 'lobby', minInstances: 3 })
    expect(store.entries[1]!.before).toEqual({ name: 'old-lobby' })
    expect(store.entries[1]!.after).toBeNull()
  })

  it('treats entries without before/after as legacy rows', async () => {
    const entry = {
      id: 7,
      username: 'admin',
      action: 'user.login',
      resourceType: 'user',
      resourceId: 'admin',
      details: '{}',
      ipAddress: '127.0.0.1',
      createdAt: '2026-05-11T12:02:00Z',
    }
    mockGET.mockResolvedValueOnce({ data: { data: [entry], total: 1 } })

    const store = useAuditStore()
    await store.fetchEntries()

    expect(store.entries[0]!.before).toBeUndefined()
    expect(store.entries[0]!.after).toBeUndefined()
  })

  it('toasts error on failure', async () => {
    mockGET.mockRejectedValueOnce(new Error('500'))
    const store = useAuditStore()
    await store.fetchEntries()
    expect(toast.error).toHaveBeenCalledWith('Failed to load audit log')
  })

  it('sends an empty cursor and exposes nextCursor as hasMore on the first page', async () => {
    mockGET.mockResolvedValueOnce({ data: { data: [{ id: 1 }], total: 3, nextCursor: 'c1' } })
    const store = useAuditStore()
    await store.fetchEntries()

    expect(mockGET).toHaveBeenCalledWith('/api/v1/audit', { params: { query: { cursor: '', pageSize: 50 } } })
    expect(store.hasMore).toBe(true)
    expect(store.canPrev).toBe(false)
    expect(store.offset).toBe(0)
  })

  it('walks forward with nextCursor and back via the cursor stack', async () => {
    const store = useAuditStore()
    mockGET.mockResolvedValueOnce({ data: { data: [{ id: 1 }], total: 3, nextCursor: 'c1' } })
    await store.fetchEntries()

    // Next page passes the live nextCursor and advances the display offset.
    mockGET.mockResolvedValueOnce({ data: { data: [{ id: 2 }], total: 3, nextCursor: null } })
    await store.nextPage()
    expect(mockGET).toHaveBeenLastCalledWith('/api/v1/audit', { params: { query: { cursor: 'c1', pageSize: 50 } } })
    expect(store.entries[0]!.id).toBe(2)
    expect(store.offset).toBe(50)
    expect(store.canPrev).toBe(true)
    expect(store.hasMore).toBe(false) // nextCursor was null → last page

    // nextPage is a no-op on the last page (no nextCursor).
    mockGET.mockClear()
    await store.nextPage()
    expect(mockGET).not.toHaveBeenCalled()

    // Previous page pops back to the newest page (empty cursor).
    mockGET.mockResolvedValueOnce({ data: { data: [{ id: 1 }], total: 3, nextCursor: 'c1' } })
    await store.prevPage()
    expect(mockGET).toHaveBeenLastCalledWith('/api/v1/audit', { params: { query: { cursor: '', pageSize: 50 } } })
    expect(store.offset).toBe(0)
    expect(store.canPrev).toBe(false)
  })
})
