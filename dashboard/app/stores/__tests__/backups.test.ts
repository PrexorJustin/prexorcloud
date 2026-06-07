import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useBackupsStore } from '../backups'

const mockGET = vi.fn()
const mockPOST = vi.fn()
const mockDELETE = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: mockPOST, DELETE: mockDELETE, PUT: vi.fn(), PATCH: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

beforeEach(() => {
  setActivePinia(createPinia())
  mockGET.mockReset()
  mockPOST.mockReset()
  mockDELETE.mockReset()
  vi.mocked(toast.success).mockReset()
  vi.mocked(toast.error).mockReset()
})

describe('useBackupsStore', () => {
  it('starts empty', () => {
    const store = useBackupsStore()
    expect(store.backups).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchBackups populates the list and tolerates a missing data field', async () => {
    mockGET.mockResolvedValueOnce({ data: { data: [{ id: 'b1', createdAt: '2026', sizeBytes: 1 }] } })
    const store = useBackupsStore()
    await store.fetchBackups()
    expect(store.backups[0]!.id).toBe('b1')

    mockGET.mockResolvedValueOnce({ data: {} })
    await store.fetchBackups()
    expect(store.backups).toEqual([])
  })

  it('fetchBackups toasts on failure but clears loading', async () => {
    mockGET.mockRejectedValue(new Error('boom'))
    const store = useBackupsStore()
    await store.fetchBackups()
    expect(store.loading).toBe(false)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('createBackup posts notes, refetches, and returns the new record', async () => {
    mockPOST.mockResolvedValue({ data: { id: 'b2', createdAt: '2026', sizeBytes: 2 } })
    mockGET.mockResolvedValue({ data: { data: [{ id: 'b2', createdAt: '2026', sizeBytes: 2 }] } })

    const store = useBackupsStore()
    const created = await store.createBackup('pre-upgrade')
    expect(created?.id).toBe('b2')
    expect(mockPOST).toHaveBeenCalledWith('/api/v1/backups', { body: { notes: 'pre-upgrade' } })
    expect(mockGET).toHaveBeenCalledWith('/api/v1/backups')
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it('createBackup returns null and toasts on failure', async () => {
    mockPOST.mockRejectedValue(new Error('boom'))
    const store = useBackupsStore()
    const created = await store.createBackup()
    expect(created).toBeNull()
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('verifyBackup POSTs to the encoded id and refetches', async () => {
    mockPOST.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { data: [] } })
    const store = useBackupsStore()
    await store.verifyBackup('back up/1')
    expect(mockPOST).toHaveBeenCalledWith('/api/v1/backups/back%20up%2F1/verify')
    expect(mockGET).toHaveBeenCalledWith('/api/v1/backups')
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it('verifyBackup swallows errors (no throw) but toasts', async () => {
    mockPOST.mockRejectedValue(new Error('boom'))
    const store = useBackupsStore()
    await store.verifyBackup('b1')
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('deleteBackup removes from list optimistically on success', async () => {
    mockDELETE.mockResolvedValue(undefined)
    const store = useBackupsStore()
    store.backups = [
      { id: 'a', createdAt: '', sizeBytes: 0 },
      { id: 'b', createdAt: '', sizeBytes: 0 },
    ]
    await store.deleteBackup('a')
    expect(store.backups.map(b => b.id)).toEqual(['b'])
    expect(mockDELETE).toHaveBeenCalledWith('/api/v1/backups/a')
  })

  it('deleteBackup rethrows a typed error on failure', async () => {
    mockDELETE.mockRejectedValue(new Error('boom'))
    const store = useBackupsStore()
    store.backups = [{ id: 'a', createdAt: '', sizeBytes: 0 }]
    await expect(store.deleteBackup('a')).rejects.toThrow('delete-backup')
    expect(store.backups).toHaveLength(1)
  })

  it('pruneBackups posts keep count and refetches', async () => {
    mockPOST.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { data: [] } })
    const store = useBackupsStore()
    await store.pruneBackups(7)
    expect(mockPOST).toHaveBeenCalledWith('/api/v1/backups/prune', { body: { keep: 7 } })
    expect(mockGET).toHaveBeenCalledWith('/api/v1/backups')
    expect(toast.success).toHaveBeenCalledWith('Pruned to 7 most recent backups')
  })

  it('restoreBackup posts the backupId and rethrows on failure', async () => {
    mockPOST.mockResolvedValueOnce({ data: null })
    const store = useBackupsStore()
    await store.restoreBackup('b1')
    expect(mockPOST).toHaveBeenCalledWith('/api/v1/restore', { body: { backupId: 'b1' } })
    expect(toast.success).toHaveBeenCalledTimes(1)

    mockPOST.mockRejectedValueOnce(new Error('boom'))
    await expect(store.restoreBackup('b1')).rejects.toThrow('restore-backup')
  })
})
