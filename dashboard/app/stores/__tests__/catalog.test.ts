import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useCatalogStore } from '../catalog'

const mockGET = vi.fn()
const mockPOST = vi.fn()
const mockPATCH = vi.fn()
const mockPUT = vi.fn()
const mockDELETE = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: mockPOST, PATCH: mockPATCH, PUT: mockPUT, DELETE: mockDELETE }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

beforeEach(() => {
  setActivePinia(createPinia())
  mockGET.mockReset()
  mockPOST.mockReset()
  mockPATCH.mockReset()
  mockPUT.mockReset()
  mockDELETE.mockReset()
  vi.mocked(toast.success).mockReset()
  vi.mocked(toast.error).mockReset()
})

describe('useCatalogStore', () => {
  it('starts empty', () => {
    const store = useCatalogStore()
    expect(store.entries).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchCatalog groups raw rows by platform and gathers versions', async () => {
    mockGET.mockResolvedValue({
      data: {
        data: [
          { platform: 'paper', category: 'server', version: '1.20.4', downloadUrl: 'u1', recommended: false },
          { platform: 'paper', category: 'server', version: '1.21.0', downloadUrl: 'u2', recommended: true },
          { platform: 'velocity', category: 'proxy', version: '3.3.0', downloadUrl: 'u3', recommended: true, configFormat: 'TOML' },
        ],
      },
    })

    const store = useCatalogStore()
    await store.fetchCatalog()

    expect(store.entries).toHaveLength(2)
    const paper = store.entries.find(e => e.platform === 'paper')!
    expect(paper.versions.map(v => v.version)).toEqual(['1.20.4', '1.21.0'])
    expect(paper.versions[1]!.recommended).toBe(true)

    const velocity = store.entries.find(e => e.platform === 'velocity')!
    expect(velocity.configFormat).toBe('TOML')
    expect(velocity.versions).toHaveLength(1)
  })

  it('fetchCatalog tolerates a missing data field and toasts on failure', async () => {
    mockGET.mockResolvedValueOnce({ data: {} })
    const store = useCatalogStore()
    await store.fetchCatalog()
    expect(store.entries).toEqual([])

    mockGET.mockRejectedValueOnce(new Error('boom'))
    await store.fetchCatalog()
    expect(store.loading).toBe(false)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('addVersion posts the body and re-fetches', async () => {
    mockPOST.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { data: [] } })

    const store = useCatalogStore()
    await store.addVersion('paper', { version: '1.21.1', downloadUrl: 'u9' })

    expect(mockPOST).toHaveBeenCalledWith('/api/v1/catalog/{platform}/versions', {
      params: { path: { platform: 'paper' } },
      body: { version: '1.21.1', downloadUrl: 'u9' },
    })
    expect(mockGET).toHaveBeenCalledWith('/api/v1/catalog')
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it('addVersion rethrows on failure and toasts error', async () => {
    mockPOST.mockRejectedValue(new Error('boom'))
    const store = useCatalogStore()
    await expect(store.addVersion('paper', { version: 'x', downloadUrl: 'y' })).rejects.toThrow()
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('updateVersion patches the path tuple and re-fetches', async () => {
    mockPATCH.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { data: [] } })

    const store = useCatalogStore()
    await store.updateVersion('paper', '1.20.4', { downloadUrl: 'u9' })

    expect(mockPATCH).toHaveBeenCalledWith('/api/v1/catalog/{platform}/versions/{version}', {
      params: { path: { platform: 'paper', version: '1.20.4' } },
      body: { downloadUrl: 'u9' },
    })
  })

  it('markRecommended puts to the recommended path and re-fetches', async () => {
    mockPUT.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { data: [] } })

    const store = useCatalogStore()
    await store.markRecommended('velocity', '3.3.0')

    expect(mockPUT).toHaveBeenCalledWith('/api/v1/catalog/{platform}/versions/{version}/recommended', {
      params: { path: { platform: 'velocity', version: '3.3.0' } },
    })
    expect(mockGET).toHaveBeenCalledWith('/api/v1/catalog')
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it('deleteVersion deletes by platform/version path tuple', async () => {
    mockDELETE.mockResolvedValue(undefined)
    mockGET.mockResolvedValue({ data: { data: [] } })

    const store = useCatalogStore()
    await store.deleteVersion('paper', '1.20.4')

    expect(mockDELETE).toHaveBeenCalledWith('/api/v1/catalog/{platform}/versions/{version}', {
      params: { path: { platform: 'paper', version: '1.20.4' } },
    })
  })

  it('deleteVersion rethrows on failure', async () => {
    mockDELETE.mockRejectedValue(new Error('boom'))
    const store = useCatalogStore()
    await expect(store.deleteVersion('paper', '1.20.4')).rejects.toThrow()
    expect(toast.error).toHaveBeenCalledTimes(1)
  })
})
