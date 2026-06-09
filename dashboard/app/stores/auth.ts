import { defineStore } from 'pinia'
import { toast } from 'vue-sonner'
import type { Schema } from '@prexorcloud/api-sdk'
import { t } from '~/lib/translate'

type AuthUser = Schema<'UserDto'>

// Cross-tab logout broadcast. Tab A logs out → posts on this channel →
// every other open tab clears local state immediately, instead of
// waiting up to ~92 s for the SSE bus heartbeat to time out and
// re-detect the missing token.
const LOGOUT_CHANNEL = 'prexor-auth'
const LOGOUT_EVENT = 'logout'

export const useAuthStore = defineStore('auth', () => {
  const storage = useSafeStorage()
  const token = ref<string | null>(import.meta.client ? storage.getItem('auth_token') : null)
  const user = ref<AuthUser | null>(null)

  const isAuthenticated = computed(() => !!token.value)

  /** Resolved permission set for fast lookups */
  const permissionSet = computed(() => new Set(user.value?.permissions ?? []))

  /** Check if the current user has a specific permission */
  function can(permission: string): boolean {
    return permissionSet.value.has(permission)
  }

  /** Check if the current user has any of the given permissions */
  function canAny(...permissions: string[]): boolean {
    return permissions.some(p => permissionSet.value.has(p))
  }

  /**
   * Hydration promise — resolves once the user has been fetched (or fetch failed).
   * Middleware awaits this before allowing navigation.
   */
  let _readyResolve: (() => void) | null = null
  const ready = ref(
    new Promise<void>((resolve) => {
      _readyResolve = resolve
    }),
  )

  /** Fetch current user from /auth/me using the stored token */
  async function fetchUser() {
    if (!token.value) {
      _readyResolve?.()
      return
    }
    try {
      const client = useApiClient()
      const { data } = await client.GET('/api/v1/auth/me')
      user.value = data ?? null
    } catch {
      // Token is invalid or expired — clear it
      token.value = null
      user.value = null
      storage.removeItem('auth_token')
    } finally {
      _readyResolve?.()
    }
  }

  async function login(credentials: { username: string; password: string }) {
    const client = useApiClient()
    try {
      const { data } = await client.POST('/api/v1/auth/login', { body: credentials })
      if (!data?.token) throw new Error('Login failed')
      token.value = data.token
      user.value = data.user ?? null
      storage.setItem('auth_token', data.token)
      toast.success(t('store.auth.welcomeBack', { username: data.user?.username }))
    }
    catch {
      toast.error(t('store.auth.invalidCredentials'))
      throw new Error('Login failed')
    }
  }

  /**
   * Clear local auth state. Used both for proactive logout (user clicked
   * "Sign out") and reactive logout (cross-tab broadcast, refresh failure).
   * Idempotent — safe to call when already logged out.
   */
  function clearLocal() {
    token.value = null
    user.value = null
    storage.removeItem('auth_token')
  }

  /**
   * Logout flow. Calls the server-side revocation endpoint first so the
   * stolen-token threat model is closed (the JWT is added to the Redis
   * revocation list and any subsequent request with it gets a 401).
   * Always clears local state and broadcasts to other tabs, even if the
   * server call fails — local state must come clean regardless.
   */
  async function logout() {
    const currentToken = token.value
    // Best-effort server revocation. If the controller is offline, the
    // token still has its natural TTL — but the typical case is online
    // and the revocation lands.
    if (currentToken) {
      try {
        const client = useApiClient()
        // The auth/logout path is in the OpenAPI spec but the SDK type bundle
        // can lag; cast the path string so this compiles in either direction.
        await (client.POST as (path: string, init?: unknown) => Promise<unknown>)('/api/v1/auth/logout', {})
      } catch {
        // Swallow — local logout always wins.
      }
    }
    clearLocal()
    broadcastLogout()
    // SSE bus auto-disconnects when token is removed (interval check in useSseEventBus)
  }

  // ── Cross-tab logout sync ─────────────────────────────────────────
  let logoutChannel: BroadcastChannel | null = null

  function broadcastLogout() {
    logoutChannel?.postMessage({ type: LOGOUT_EVENT })
  }

  if (import.meta.client && typeof BroadcastChannel !== 'undefined') {
    logoutChannel = new BroadcastChannel(LOGOUT_CHANNEL)
    logoutChannel.onmessage = (e) => {
      if (e.data?.type === LOGOUT_EVENT && token.value) {
        clearLocal()
      }
    }
  }

  async function changePassword(currentPassword: string, newPassword: string) {
    const client = useApiClient()
    await client.POST('/api/v1/auth/change-password', { body: { currentPassword, newPassword } })
    toast.success(t('store.auth.passwordChanged'))
  }

  // Auto-hydrate on store creation when token exists but user is missing
  if (import.meta.client && token.value && !user.value) {
    fetchUser()
  } else {
    _readyResolve!()
  }

  return { token, user, isAuthenticated, ready, can, canAny, login, logout, changePassword, fetchUser }
})
