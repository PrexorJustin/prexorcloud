import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

import { useShortcuts } from '../useShortcuts'

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

// Nuxt's own test setup also calls useRouter() (afterEach/beforeEach guards),
// so the stub has to satisfy those too — not just push().
mockNuxtImport('useRouter', () => () => ({
  push: pushMock,
  replace: vi.fn(),
  afterEach: vi.fn(),
  beforeEach: vi.fn(),
  beforeResolve: vi.fn(),
  isReady: () => Promise.resolve(),
}))

function keydown(init: KeyboardEventInit, target?: EventTarget) {
  const ev = new KeyboardEvent('keydown', { ...init, bubbles: true, cancelable: true })
  ;(target ?? window).dispatchEvent(ev)
  return ev
}

beforeEach(() => {
  pushMock.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useShortcuts', () => {
  it('meta+k fires onShowPalette and preventsDefault', () => {
    const onShowPalette = vi.fn()
    useShortcuts({ onShowPalette })
    const ev = keydown({ key: 'k', metaKey: true })
    expect(onShowPalette).toHaveBeenCalledTimes(1)
    expect(ev.defaultPrevented).toBe(true)
  })

  it('ctrl+k also fires onShowPalette', () => {
    const onShowPalette = vi.fn()
    useShortcuts({ onShowPalette })
    keydown({ key: 'K', ctrlKey: true })
    expect(onShowPalette).toHaveBeenCalledTimes(1)
  })

  it('? fires onShowOverlay', () => {
    const onShowOverlay = vi.fn()
    useShortcuts({ onShowOverlay })
    const ev = keydown({ key: '?' })
    expect(onShowOverlay).toHaveBeenCalledTimes(1)
    expect(ev.defaultPrevented).toBe(true)
  })

  it('a two-key chord `g i` routes to /instances', () => {
    const { buffer } = useShortcuts()
    keydown({ key: 'g' })
    expect(buffer.value).toEqual(['g'])
    keydown({ key: 'i' })
    expect(pushMock).toHaveBeenCalledWith('/instances')
    expect(buffer.value).toEqual([])
  })

  it('ignores keystrokes while focus is in an input', () => {
    const onShowOverlay = vi.fn()
    useShortcuts({ onShowOverlay })
    const input = document.createElement('input')
    document.body.appendChild(input)
    keydown({ key: '?' }, input)
    expect(onShowOverlay).not.toHaveBeenCalled()
    input.remove()
  })

  it('Escape clears a pending chord buffer', () => {
    const { buffer } = useShortcuts()
    keydown({ key: 'g' })
    expect(buffer.value).toEqual(['g'])
    keydown({ key: 'Escape' })
    expect(buffer.value).toEqual([])
  })

  it('a non-prefix letter clears the buffer instead of accumulating', () => {
    const { buffer } = useShortcuts()
    keydown({ key: 'g' })
    keydown({ key: 'z' }) // `g z` is not a real chord
    expect(buffer.value).toEqual([])
    expect(pushMock).not.toHaveBeenCalled()
  })

  it('a non-letter key clears the buffer', () => {
    const { buffer } = useShortcuts()
    keydown({ key: 'g' })
    keydown({ key: '1' })
    expect(buffer.value).toEqual([])
  })

  it('the chord buffer resets after the 1.2s timeout', () => {
    vi.useFakeTimers()
    const { buffer } = useShortcuts()
    keydown({ key: 'g' })
    expect(buffer.value).toEqual(['g'])
    vi.advanceTimersByTime(1200)
    expect(buffer.value).toEqual([])
  })
})
