import { describe, it, expect } from 'vitest'
import { resolveIcon } from '../icons'

describe('resolveIcon', () => {
  it('returns the Lucide component for a known icon name', () => {
    const icon = resolveIcon('Settings')
    expect(icon).toBeDefined()
    // Lucide components register as objects/functions.
    expect(['object', 'function']).toContain(typeof icon)
  })

  it('also resolves a non-Settings example', () => {
    expect(resolveIcon('Box')).toBeDefined()
    expect(resolveIcon('Users')).toBeDefined()
  })

  it('returns undefined for an unknown name', () => {
    expect(resolveIcon('NotAnIconName_____')).toBeUndefined()
  })

  it('returns undefined for null / undefined / empty string', () => {
    expect(resolveIcon(null)).toBeUndefined()
    expect(resolveIcon(undefined)).toBeUndefined()
    expect(resolveIcon('')).toBeUndefined()
  })
})
