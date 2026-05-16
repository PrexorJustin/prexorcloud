import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useUsersStore } from '../users'

const mockGET = vi.fn()
const mockPOST = vi.fn()
const mockPATCH = vi.fn()
const mockDELETE = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: mockPOST, PATCH: mockPATCH, DELETE: mockDELETE, PUT: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

describe('useUsersStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGET.mockReset(); mockPOST.mockReset(); mockPATCH.mockReset(); mockDELETE.mockReset()
    vi.mocked(toast.success).mockReset()
    vi.mocked(toast.error).mockReset()
  })

  it('starts with empty users and not loading', () => {
    const store = useUsersStore()
    expect(store.users).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchUsers populates from envelope payload', async () => {
    const items = [
      { username: 'admin', role: 'admin' },
      { username: 'ops', role: 'operator' },
    ]
    mockGET.mockResolvedValueOnce({ data: { data: items } })

    const store = useUsersStore()
    await store.fetchUsers()

    expect(store.users).toEqual(items)
    expect(store.loading).toBe(false)
  })

  it('fetchUsers tolerates a missing data envelope', async () => {
    mockGET.mockResolvedValueOnce({ data: undefined })
    const store = useUsersStore()
    await store.fetchUsers()
    expect(store.users).toEqual([])
  })

  it('fetchUsers toasts an error on failure and clears loading', async () => {
    mockGET.mockRejectedValueOnce(new Error('500'))
    const store = useUsersStore()
    await store.fetchUsers()
    expect(toast.error).toHaveBeenCalledWith("Can't load users", expect.any(Object))
    expect(store.loading).toBe(false)
  })

  it('createUser POSTs and refetches on success', async () => {
    mockPOST.mockResolvedValueOnce({ data: {} })
    mockGET.mockResolvedValueOnce({ data: { data: [{ username: 'new', role: 'operator' }] } })

    const store = useUsersStore()
    await store.createUser({ username: 'new', password: 'pw', role: 'operator' })

    expect(mockPOST).toHaveBeenCalledWith('/api/v1/users', { body: { username: 'new', password: 'pw', role: 'operator' } })
    expect(toast.success).toHaveBeenCalledWith('User "new" created')
    expect(store.users).toEqual([{ username: 'new', role: 'operator' }])
  })

  it('createUser rethrows a typed error so callers can react', async () => {
    mockPOST.mockRejectedValueOnce(new Error('409'))
    const store = useUsersStore()
    await expect(
      store.createUser({ username: 'dup', password: 'pw', role: 'operator' }),
    ).rejects.toThrow('create-user')
    expect(toast.error).toHaveBeenCalledWith('Create failed', expect.any(Object))
  })

  it('updateUser PATCHes by username and refetches', async () => {
    mockPATCH.mockResolvedValueOnce({ data: {} })
    mockGET.mockResolvedValueOnce({ data: { data: [] } })
    const store = useUsersStore()
    await store.updateUser('admin', { role: 'operator' })
    expect(mockPATCH).toHaveBeenCalledWith('/api/v1/users/{username}', {
      params: { path: { username: 'admin' } },
      body: { role: 'operator' },
    })
    expect(toast.success).toHaveBeenCalledWith('User updated')
  })

  it('updateUser rethrows on server failure', async () => {
    mockPATCH.mockRejectedValueOnce(new Error('500'))
    const store = useUsersStore()
    await expect(store.updateUser('admin', { role: 'operator' })).rejects.toThrow('update-user')
  })

  it('deleteUser removes the user locally on success without a refetch', async () => {
    mockGET.mockResolvedValueOnce({ data: { data: [
      { username: 'admin', role: 'admin' },
      { username: 'ops', role: 'operator' },
    ] } })
    mockDELETE.mockResolvedValueOnce({ data: {} })

    const store = useUsersStore()
    await store.fetchUsers()
    await store.deleteUser('ops')

    expect(mockDELETE).toHaveBeenCalledWith('/api/v1/users/{username}', { params: { path: { username: 'ops' } } })
    expect(store.users.map(u => u.username)).toEqual(['admin'])
    expect(toast.success).toHaveBeenCalledWith('User "ops" deleted')
  })

  it('deleteUser rethrows on server failure and keeps state intact', async () => {
    mockGET.mockResolvedValueOnce({ data: { data: [{ username: 'admin', role: 'admin' }] } })
    mockDELETE.mockRejectedValueOnce(new Error('409'))
    const store = useUsersStore()
    await store.fetchUsers()
    await expect(store.deleteUser('admin')).rejects.toThrow('delete-user')
    expect(store.users.map(u => u.username)).toEqual(['admin'])
  })
})
