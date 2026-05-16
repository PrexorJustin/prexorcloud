import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useRolesStore, PERMISSION_GROUPS } from '../roles'

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

describe('useRolesStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGET.mockReset(); mockPOST.mockReset(); mockPATCH.mockReset(); mockDELETE.mockReset()
    vi.mocked(toast.success).mockReset()
    vi.mocked(toast.error).mockReset()
  })

  it('starts empty and not loading', () => {
    const store = useRolesStore()
    expect(store.roles).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchRoles populates from envelope payload', async () => {
    const items = [
      { name: 'ADMIN', permissions: ['*'], builtIn: true },
      { name: 'OPERATOR', permissions: ['instances.view'], builtIn: false },
    ]
    mockGET.mockResolvedValueOnce({ data: { data: items } })

    const store = useRolesStore()
    await store.fetchRoles()
    expect(store.roles).toEqual(items)
    expect(store.loading).toBe(false)
  })

  it('fetchRoles toasts on failure and clears loading', async () => {
    mockGET.mockRejectedValueOnce(new Error('500'))
    const store = useRolesStore()
    await store.fetchRoles()
    expect(toast.error).toHaveBeenCalledWith("Can't load roles", expect.any(Object))
    expect(store.loading).toBe(false)
  })

  it('createRole POSTs and refetches on success', async () => {
    mockPOST.mockResolvedValueOnce({ data: {} })
    mockGET.mockResolvedValueOnce({ data: { data: [{ name: 'AUDITOR', permissions: ['audit.view'], builtIn: false }] } })

    const store = useRolesStore()
    await store.createRole({ name: 'AUDITOR', permissions: ['audit.view'] })

    expect(mockPOST).toHaveBeenCalledWith('/api/v1/roles', { body: { name: 'AUDITOR', permissions: ['audit.view'] } })
    expect(toast.success).toHaveBeenCalledWith('Role "AUDITOR" created')
    expect(store.roles).toHaveLength(1)
  })

  it('createRole rethrows on conflict', async () => {
    mockPOST.mockRejectedValueOnce(new Error('409'))
    const store = useRolesStore()
    await expect(store.createRole({ name: 'lowercase', permissions: [] })).rejects.toThrow('create-role')
    expect(toast.error).toHaveBeenCalledWith('Create failed', expect.any(Object))
  })

  it('updateRole PATCHes by name and refetches', async () => {
    mockPATCH.mockResolvedValueOnce({ data: {} })
    mockGET.mockResolvedValueOnce({ data: { data: [] } })

    const store = useRolesStore()
    await store.updateRole('OPERATOR', { permissions: ['instances.view', 'instances.start'] })

    expect(mockPATCH).toHaveBeenCalledWith('/api/v1/roles/{name}', {
      params: { path: { name: 'OPERATOR' } },
      body: { permissions: ['instances.view', 'instances.start'] },
    })
    expect(toast.success).toHaveBeenCalledWith('Role updated')
  })

  it('updateRole rethrows when targeting a built-in', async () => {
    mockPATCH.mockRejectedValueOnce(new Error('403'))
    const store = useRolesStore()
    await expect(store.updateRole('ADMIN', { permissions: [] })).rejects.toThrow('update-role')
  })

  it('deleteRole removes from local list and toasts', async () => {
    mockGET.mockResolvedValueOnce({ data: { data: [
      { name: 'ADMIN', permissions: ['*'], builtIn: true },
      { name: 'AUDITOR', permissions: ['audit.view'], builtIn: false },
    ] } })
    mockDELETE.mockResolvedValueOnce({ data: {} })

    const store = useRolesStore()
    await store.fetchRoles()
    await store.deleteRole('AUDITOR')

    expect(store.roles.map(r => r.name)).toEqual(['ADMIN'])
    expect(toast.success).toHaveBeenCalledWith('Role "AUDITOR" deleted')
  })

  it('deleteRole rethrows when role is in use', async () => {
    mockGET.mockResolvedValueOnce({ data: { data: [{ name: 'OPS', permissions: [], builtIn: false }] } })
    mockDELETE.mockRejectedValueOnce(new Error('409'))
    const store = useRolesStore()
    await store.fetchRoles()
    await expect(store.deleteRole('OPS')).rejects.toThrow('delete-role')
    expect(store.roles.map(r => r.name)).toEqual(['OPS'])
  })

  it('PERMISSION_GROUPS catalogue covers every dashboard module', () => {
    // Smoke test: the permission catalogue is a hand-maintained UI table
    // and silently dropping a group would lose a column in the role-edit
    // view. Lock the high-level shape so regressions surface in CI.
    const labels = PERMISSION_GROUPS.map(g => g.label)
    expect(labels).toEqual(expect.arrayContaining([
      'Instances', 'Groups', 'Nodes', 'Networks', 'Templates',
      'Modules', 'Crashes', 'Audit', 'Backups', 'Maintenance',
      'Tokens', 'Credentials', 'Users', 'Roles',
    ]))
    // Every group exposes at least one permission key.
    for (const g of PERMISSION_GROUPS) {
      expect(g.permissions.length).toBeGreaterThan(0)
    }
  })
})
