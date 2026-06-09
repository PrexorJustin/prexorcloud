import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useCertificatesStore } from '../certificates'

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

describe('useCertificatesStore', () => {
  it('starts empty', () => {
    const store = useCertificatesStore()
    expect(store.revoked).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchRevoked populates the list', async () => {
    mockGET.mockResolvedValue({
      data: { data: [{ nodeId: 'n1', revokedAt: '2026-05-14', reason: 'leaked' }] },
    })
    const store = useCertificatesStore()
    await store.fetchRevoked()
    expect(store.revoked).toHaveLength(1)
    expect(store.revoked[0]!.nodeId).toBe('n1')
    expect(mockGET).toHaveBeenCalledWith('/api/v1/nodes/revoked-certs')
  })

  it('fetchRevoked toasts and clears loading on failure', async () => {
    mockGET.mockRejectedValue(new Error('boom'))
    const store = useCertificatesStore()
    await store.fetchRevoked()
    expect(store.loading).toBe(false)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('revoke posts the reason, refetches, and toasts success', async () => {
    mockPOST.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { data: [] } })

    const store = useCertificatesStore()
    await store.revoke('node 1', 'leaked key')
    expect(mockPOST).toHaveBeenCalledWith('/api/v1/nodes/node%201/revoke-cert', { body: { reason: 'leaked key' } })
    expect(mockGET).toHaveBeenCalledWith('/api/v1/nodes/revoked-certs')
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it('revoke rethrows a typed error and skips refetch on failure', async () => {
    mockPOST.mockRejectedValue(new Error('boom'))
    const store = useCertificatesStore()
    await expect(store.revoke('n1')).rejects.toThrow('revoke-cert')
    expect(mockGET).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('unrevoke DELETEs and refetches', async () => {
    mockDELETE.mockResolvedValue(undefined)
    mockGET.mockResolvedValue({ data: { data: [] } })

    const store = useCertificatesStore()
    await store.unrevoke('n1')
    expect(mockDELETE).toHaveBeenCalledWith('/api/v1/nodes/n1/revoke-cert')
    expect(mockGET).toHaveBeenCalledWith('/api/v1/nodes/revoked-certs')
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it('unrevoke rethrows a typed error on failure', async () => {
    mockDELETE.mockRejectedValue(new Error('boom'))
    const store = useCertificatesStore()
    await expect(store.unrevoke('n1')).rejects.toThrow('unrevoke-cert')
    expect(mockGET).not.toHaveBeenCalled()
  })
})
