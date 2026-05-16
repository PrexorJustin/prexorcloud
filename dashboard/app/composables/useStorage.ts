/**
 * Safe localStorage wrapper that gracefully handles private browsing,
 * disabled cookies, or storage quota exceeded scenarios.
 */
export function useSafeStorage() {
  function getItem(key: string): string | null {
    try {
      return localStorage.getItem(key)
    } catch {
      return null
    }
  }

  function setItem(key: string, value: string): void {
    try {
      localStorage.setItem(key, value)
    } catch {
      console.warn(`[storage] Failed to write key "${key}" — storage may be unavailable`)
    }
  }

  function removeItem(key: string): void {
    try {
      localStorage.removeItem(key)
    } catch {
      // Ignore — item may not exist or storage unavailable
    }
  }

  return { getItem, setItem, removeItem }
}
