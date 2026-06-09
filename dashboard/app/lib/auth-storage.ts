/**
 * Single source of truth for the auth-token storage key and SSR-safe access.
 * Replaces ad-hoc `localStorage.getItem('auth_token')` calls scattered across
 * composables, stores, and components.
 *
 * Use `getAuthToken()` from anywhere; on the server it returns null so
 * universal code does not crash. For reactive access inside a Pinia store or
 * setup block, prefer `useAuthStore().token`.
 */
export const AUTH_TOKEN_KEY = 'auth_token'

export function getAuthToken(): string | null {
  if (!import.meta.client) return null
  try {
    return localStorage.getItem(AUTH_TOKEN_KEY)
  } catch {
    return null
  }
}

export function setAuthToken(token: string): void {
  if (!import.meta.client) return
  try {
    localStorage.setItem(AUTH_TOKEN_KEY, token)
  } catch {
    // ignore — quota or private mode
  }
}

export function clearAuthToken(): void {
  if (!import.meta.client) return
  try {
    localStorage.removeItem(AUTH_TOKEN_KEY)
  } catch {
    // ignore
  }
}
