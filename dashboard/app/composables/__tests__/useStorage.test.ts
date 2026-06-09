import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useSafeStorage } from '../useStorage'

describe('useSafeStorage', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('getItem returns stored value', () => {
    localStorage.setItem('key', 'value')
    const { getItem } = useSafeStorage()
    expect(getItem('key')).toBe('value')
  })

  it('getItem returns null for missing key', () => {
    const { getItem } = useSafeStorage()
    expect(getItem('missing')).toBeNull()
  })

  it('setItem writes to localStorage', () => {
    const { setItem } = useSafeStorage()
    setItem('key', 'value')
    expect(localStorage.getItem('key')).toBe('value')
  })

  it('removeItem deletes from localStorage', () => {
    localStorage.setItem('key', 'value')
    const { removeItem } = useSafeStorage()
    removeItem('key')
    expect(localStorage.getItem('key')).toBeNull()
  })

  it('getItem returns null when localStorage throws', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    const { getItem } = useSafeStorage()
    expect(getItem('key')).toBeNull()
    spy.mockRestore()
  })

  it('setItem does not throw when localStorage throws', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })
    const { setItem } = useSafeStorage()
    expect(() => setItem('key', 'value')).not.toThrow()
    spy.mockRestore()
  })

  it('removeItem does not throw when localStorage throws', () => {
    const spy = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    const { removeItem } = useSafeStorage()
    expect(() => removeItem('key')).not.toThrow()
    spy.mockRestore()
  })
})
