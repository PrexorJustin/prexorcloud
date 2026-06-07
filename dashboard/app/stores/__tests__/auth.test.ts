import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useAuthStore } from '../auth'

const mockGET = vi.fn()
const mockPOST = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: mockPOST }),
}))

vi.mock('vue-sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
}))

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    mockGET.mockReset()
    mockPOST.mockReset()
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  it('starts unauthenticated', () => {
    const store = useAuthStore()
    expect(store.isAuthenticated).toBe(false)
    expect(store.user).toBeNull()
    expect(store.token).toBeNull()
  })

  it('login sets token and user', async () => {
    const mockUser = { username: 'admin', permissions: ['audit.view'] }
    mockPOST.mockResolvedValueOnce({ data: { token: 'jwt-123', user: mockUser } })

    const store = useAuthStore()
    await store.login({ username: 'admin', password: 'pass' })

    expect(store.token).toBe('jwt-123')
    expect(store.user).toEqual(mockUser)
    expect(store.isAuthenticated).toBe(true)
    expect(localStorage.getItem('auth_token')).toBe('jwt-123')
    expect(toast.success).toHaveBeenCalledWith('Welcome back, admin')
  })

  it('login shows error toast on failure', async () => {
    mockPOST.mockRejectedValueOnce(new Error('401'))

    const store = useAuthStore()
    await expect(store.login({ username: 'a', password: 'b' })).rejects.toThrow('Login failed')
    expect(toast.error).toHaveBeenCalledWith('Invalid credentials')
    expect(store.isAuthenticated).toBe(false)
  })

  it('logout clears state and token', async () => {
    mockPOST.mockResolvedValueOnce({ data: { token: 'jwt', user: { username: 'admin', permissions: [] } } })
    // The second POST is the server-side revocation issued during logout.
    // We resolve it so the awaited path completes deterministically.
    mockPOST.mockResolvedValueOnce({ data: {} })
    const store = useAuthStore()
    await store.login({ username: 'admin', password: 'pass' })

    await store.logout()

    expect(store.token).toBeNull()
    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
    expect(localStorage.getItem('auth_token')).toBeNull()
  })

  it('can() checks permissions', async () => {
    mockPOST.mockResolvedValueOnce({
      data: { token: 'jwt', user: { username: 'admin', permissions: ['audit.view', 'nodes.list'] } },
    })
    const store = useAuthStore()
    await store.login({ username: 'admin', password: 'pass' })

    expect(store.can('audit.view')).toBe(true)
    expect(store.can('users.delete')).toBe(false)
  })

  it('canAny() checks multiple permissions', async () => {
    mockPOST.mockResolvedValueOnce({
      data: { token: 'jwt', user: { username: 'admin', permissions: ['audit.view'] } },
    })
    const store = useAuthStore()
    await store.login({ username: 'admin', password: 'pass' })

    expect(store.canAny('audit.view', 'users.delete')).toBe(true)
    expect(store.canAny('users.delete', 'users.create')).toBe(false)
  })

  it('fetchUser clears token on error', async () => {
    localStorage.setItem('auth_token', 'expired')
    mockGET.mockRejectedValueOnce(new Error('401'))

    const store = useAuthStore()
    store.token = 'expired'
    await store.fetchUser()

    expect(store.token).toBeNull()
    expect(store.user).toBeNull()
  })

  it('changePassword calls API and toasts', async () => {
    mockPOST.mockResolvedValueOnce({ data: { token: 'jwt', user: { username: 'admin', permissions: [] } } })
    const store = useAuthStore()
    await store.login({ username: 'admin', password: 'pass' })

    mockPOST.mockResolvedValueOnce({ data: undefined })
    await store.changePassword('old', 'new')
    expect(toast.success).toHaveBeenCalledWith('Password changed')
  })
})
