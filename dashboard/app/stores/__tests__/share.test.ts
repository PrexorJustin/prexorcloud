import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useShareStore } from '../share'

const mockPOST = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ POST: mockPOST }),
}))

vi.mock('vue-sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

describe('useShareStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockPOST.mockReset()
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  it('shareControllerLogs returns result and toasts success', async () => {
    mockPOST.mockResolvedValueOnce({ data: { url: 'https://pste.dev/x', rawUrl: 'r', isPrivate: true, burnAfterRead: false } })

    const store = useShareStore()
    const result = await store.shareControllerLogs({})

    expect(mockPOST).toHaveBeenCalledWith('/api/v1/system/logs/share', { body: {} })
    expect(result?.url).toBe('https://pste.dev/x')
    expect(toast.success).toHaveBeenCalled()
  })

  it('shareDaemonLogs targets the per-node endpoint', async () => {
    mockPOST.mockResolvedValueOnce({ data: { url: 'https://pste.dev/y', rawUrl: 'r', isPrivate: true, burnAfterRead: false } })

    const store = useShareStore()
    await store.shareDaemonLogs('node-a', { level: 'WARN' })

    expect(mockPOST).toHaveBeenCalledWith('/api/v1/nodes/node-a/logs/share', { body: { level: 'WARN' } })
  })

  it('shareDiagnostics surfaces 502 as a friendly toast', async () => {
    mockPOST.mockRejectedValueOnce({ response: { status: 502 } })

    const store = useShareStore()
    const result = await store.shareDiagnostics({})

    expect(result).toBeNull()
    expect(toast.error).toHaveBeenCalled()
  })

  it('renders the revoke URL in the success toast when one is returned', async () => {
    mockPOST.mockResolvedValueOnce({ data: {
      url: 'https://pste.dev/x',
      rawUrl: 'r',
      isPrivate: true,
      burnAfterRead: true,
      deleteToken: 'deltok',
      deleteUrl: 'https://pste.dev/api/v1/paste/deltok',
    } })

    const store = useShareStore()
    await store.shareControllerLogs({})

    expect(toast.success).toHaveBeenCalled()
    const args = vi.mocked(toast.success).mock.calls[0]
    expect(args[1]).toMatchObject({ description: expect.stringContaining('pste.dev/api/v1/paste/deltok') })
  })

  it('omits the revoke description when no deleteUrl is returned', async () => {
    mockPOST.mockResolvedValueOnce({ data: {
      url: 'https://pste.dev/x',
      rawUrl: 'r',
      isPrivate: true,
      burnAfterRead: false,
    } })

    const store = useShareStore()
    await store.shareDiagnostics({})

    expect(toast.success).toHaveBeenCalled()
    const args = vi.mocked(toast.success).mock.calls[0]
    // Either no second arg or no description field.
    if (args.length > 1 && args[1]) {
      expect((args[1] as { description?: string }).description).toBeUndefined()
    }
  })
})
