import { describe, it, expect, vi, beforeEach } from 'vitest'
import { AUTH_TOKEN_KEY, getAuthToken, setAuthToken, clearAuthToken } from '../auth-storage'

beforeEach(() => {
  localStorage.clear()
})

describe('lib/auth-storage', () => {
  it('AUTH_TOKEN_KEY is the canonical storage key', () => {
    expect(AUTH_TOKEN_KEY).toBe('auth_token')
  })

  it('round-trips a token through localStorage', () => {
    setAuthToken('abc')
    expect(getAuthToken()).toBe('abc')
    expect(localStorage.getItem(AUTH_TOKEN_KEY)).toBe('abc')
  })

  it('clearAuthToken removes the entry', () => {
    setAuthToken('abc')
    clearAuthToken()
    expect(getAuthToken()).toBeNull()
  })

  it('getAuthToken returns null when nothing has been stored', () => {
    expect(getAuthToken()).toBeNull()
  })

  it('getAuthToken swallows a localStorage throw and returns null', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    expect(getAuthToken()).toBeNull()
    spy.mockRestore()
  })

  it('setAuthToken swallows a localStorage throw', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })
    expect(() => setAuthToken('x')).not.toThrow()
    spy.mockRestore()
  })

  it('clearAuthToken swallows a localStorage throw', () => {
    const spy = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    expect(() => clearAuthToken()).not.toThrow()
    spy.mockRestore()
  })
})
