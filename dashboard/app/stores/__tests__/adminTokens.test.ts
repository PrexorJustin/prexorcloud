import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useAdminTokensStore } from '../adminTokens'

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

describe('useAdminTokensStore', () => {
  it('starts empty and idle', () => {
    const store = useAdminTokensStore()
    expect(store.tokens).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchTokens populates the list', async () => {
    mockGET.mockResolvedValue({ data: { data: [{ tokenId: 'a', expiresAt: '2030-01-01' }] } })
    const store = useAdminTokensStore()
    await store.fetchTokens()
    expect(store.tokens).toEqual([{ tokenId: 'a', expiresAt: '2030-01-01' }])
    expect(store.loading).toBe(false)
  })

  it('fetchTokens tolerates an empty payload', async () => {
    mockGET.mockResolvedValue({ data: {} })
    const store = useAdminTokensStore()
    await store.fetchTokens()
    expect(store.tokens).toEqual([])
  })

  it('fetchTokens failure toasts and resets loading', async () => {
    mockGET.mockRejectedValue(new Error('boom'))
    const store = useAdminTokensStore()
    await store.fetchTokens()
    expect(store.loading).toBe(false)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('generateToken prepends the new token and returns it', async () => {
    mockPOST.mockResolvedValue({ data: { tokenId: 'new', expiresAt: '2030', joinToken: 'raw' } })
    const store = useAdminTokensStore()
    store.tokens = [{ tokenId: 'old', expiresAt: '2030' }]
    const created = await store.generateToken({ ttlSeconds: 3600 })
    expect(created?.tokenId).toBe('new')
    expect(store.tokens[0]!.tokenId).toBe('new')
    expect(store.tokens[1]!.tokenId).toBe('old')
    expect(toast.success).toHaveBeenCalledTimes(1)
    expect(mockPOST).toHaveBeenCalledWith('/api/v1/admin/tokens', { body: { ttlSeconds: 3600 } })
  })

  it('generateToken returns null and toasts on failure', async () => {
    mockPOST.mockRejectedValue(new Error('boom'))
    const store = useAdminTokensStore()
    const created = await store.generateToken({})
    expect(created).toBeNull()
    expect(store.tokens).toEqual([])
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('revokeToken removes the entry and toasts success', async () => {
    mockDELETE.mockResolvedValue(undefined)
    const store = useAdminTokensStore()
    store.tokens = [
      { tokenId: 'a', expiresAt: '2030' },
      { tokenId: 'b', expiresAt: '2030' },
    ]
    await store.revokeToken('a')
    expect(store.tokens.map(t => t.tokenId)).toEqual(['b'])
    expect(mockDELETE).toHaveBeenCalledWith('/api/v1/admin/tokens/{id}', { params: { path: { id: 'a' } } })
  })

  it('revokeToken rethrows a typed error on failure', async () => {
    mockDELETE.mockRejectedValue(new Error('boom'))
    const store = useAdminTokensStore()
    store.tokens = [{ tokenId: 'a', expiresAt: '2030' }]
    await expect(store.revokeToken('a')).rejects.toThrow('revoke-token')
    expect(store.tokens).toHaveLength(1)
  })
})
