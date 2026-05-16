import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useWorkloadCredentialsStore } from '../workloadCredentials'

const mockGET = vi.fn()
const mockDELETE = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, DELETE: mockDELETE, POST: vi.fn(), PUT: vi.fn(), PATCH: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

beforeEach(() => {
  setActivePinia(createPinia())
  mockGET.mockReset()
  mockDELETE.mockReset()
  vi.mocked(toast.success).mockReset()
  vi.mocked(toast.error).mockReset()
})

describe('useWorkloadCredentialsStore', () => {
  it('starts empty', () => {
    const store = useWorkloadCredentialsStore()
    expect(store.credentials).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchCredentials populates the list', async () => {
    mockGET.mockResolvedValue({
      data: { data: [{ tokenId: 't1', instanceId: 'i1', issuedAt: '2026' }] },
    })
    const store = useWorkloadCredentialsStore()
    await store.fetchCredentials()
    expect(store.credentials).toHaveLength(1)
    expect(mockGET).toHaveBeenCalledWith('/api/v1/workloads/credentials')
  })

  it('fetchCredentials toasts on failure but clears loading', async () => {
    mockGET.mockRejectedValue(new Error('boom'))
    const store = useWorkloadCredentialsStore()
    await store.fetchCredentials()
    expect(store.loading).toBe(false)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('revokeCredential removes from list optimistically and URL-encodes the id', async () => {
    mockDELETE.mockResolvedValue(undefined)
    const store = useWorkloadCredentialsStore()
    store.credentials = [
      { tokenId: 'tok 1', instanceId: 'i1', issuedAt: '2026' },
      { tokenId: 't2', instanceId: 'i2', issuedAt: '2026' },
    ]
    await store.revokeCredential('tok 1')
    expect(mockDELETE).toHaveBeenCalledWith('/api/v1/workloads/credentials/tok%201')
    expect(store.credentials.map(c => c.tokenId)).toEqual(['t2'])
  })

  it('revokeCredential rethrows a typed error on failure', async () => {
    mockDELETE.mockRejectedValue(new Error('boom'))
    const store = useWorkloadCredentialsStore()
    store.credentials = [{ tokenId: 't1', instanceId: 'i1', issuedAt: '2026' }]
    await expect(store.revokeCredential('t1')).rejects.toThrow('revoke-credential')
    expect(store.credentials).toHaveLength(1)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('revokeAllForInstance drops every credential for the encoded instance', async () => {
    mockDELETE.mockResolvedValue(undefined)
    const store = useWorkloadCredentialsStore()
    store.credentials = [
      { tokenId: 't1', instanceId: 'inst/a', issuedAt: '2026' },
      { tokenId: 't2', instanceId: 'inst/a', issuedAt: '2026' },
      { tokenId: 't3', instanceId: 'other', issuedAt: '2026' },
    ]
    await store.revokeAllForInstance('inst/a')
    expect(mockDELETE).toHaveBeenCalledWith('/api/v1/workloads/credentials/instances/inst%2Fa')
    expect(store.credentials.map(c => c.tokenId)).toEqual(['t3'])
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it('revokeAllForInstance rethrows a typed error on failure', async () => {
    mockDELETE.mockRejectedValue(new Error('boom'))
    const store = useWorkloadCredentialsStore()
    await expect(store.revokeAllForInstance('i1')).rejects.toThrow('revoke-credentials-instance')
  })
})
