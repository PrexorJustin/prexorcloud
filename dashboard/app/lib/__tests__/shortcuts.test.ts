import { describe, it, expect } from 'vitest'
import { matchShortcut, isShortcutPrefix, shortcuts } from '../shortcuts'

describe('matchShortcut', () => {
  it('returns the bound shortcut on a complete two-key sequence', () => {
    const hit = matchShortcut(['g'], 'i')
    expect(hit?.keys).toBe('g i')
    expect(hit?.to).toBe('/instances')
  })

  it('returns the bound shortcut for a single-key chord', () => {
    expect(matchShortcut([], '?')?.keys).toBe('?')
  })

  it('returns null when the candidate is only a prefix', () => {
    expect(matchShortcut([], 'g')).toBeNull()
  })

  it('returns null on an unknown chord', () => {
    expect(matchShortcut(['g'], 'x')).toBeNull()
  })
})

describe('isShortcutPrefix', () => {
  it('is true while the buffer + key is the start of a known chord', () => {
    expect(isShortcutPrefix([], 'g')).toBe(true)
  })

  it('is false once the candidate exactly matches (no extension exists)', () => {
    // `g i` is the longest chord starting with `g i ` — no `g i x` follow-up.
    expect(isShortcutPrefix(['g'], 'i')).toBe(false)
  })

  it('is false for unknown leading keys', () => {
    expect(isShortcutPrefix([], 'q')).toBe(false)
  })
})

describe('shortcuts table', () => {
  it('has unique chord strings', () => {
    const seen = new Set<string>()
    for (const s of shortcuts) {
      expect(seen.has(s.keys), `duplicate chord: ${s.keys}`).toBe(false)
      seen.add(s.keys)
    }
  })

  it('every entry binds either `to` or `handler` or is marked hidden', () => {
    for (const s of shortcuts) {
      expect(s.to || s.handler || s.hidden, `shortcut "${s.keys}" must bind to/handler or be hidden`).toBeTruthy()
    }
  })
})
