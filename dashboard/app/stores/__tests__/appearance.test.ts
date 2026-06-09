import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

import { useAppearanceStore } from '../appearance'

const { mockColorMode, mockCookies } = vi.hoisted(() => ({
  mockColorMode: { value: 'dark' as 'dark' | 'light' },
  mockCookies: new Map<string, { value: string | null }>(),
}))

mockNuxtImport('useColorMode', () => {
  return () => mockColorMode
})

mockNuxtImport('useCookie', () => {
  return (name: string) => {
    if (!mockCookies.has(name)) mockCookies.set(name, { value: null })
    return mockCookies.get(name)!
  }
})

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  mockCookies.clear()
  mockColorMode.value = 'dark'
  document.documentElement.style.cssText = ''
})

describe('useAppearanceStore', () => {
  it('starts with the default accent, radius, and glow config', () => {
    const store = useAppearanceStore()
    expect(store.accentColor).toBe('Cyan')
    expect(store.customAccentColor).toBeNull()
    expect(store.radius).toBe(0.75)
    expect(store.glowEnabled).toBe(true)
    expect(store.glowIntensity).toBe(100)
    expect(store.glowBlobs.length).toBeGreaterThan(0)
  })

  it('hydrates from localStorage when present', () => {
    localStorage.setItem(
      'prexor-appearance',
      JSON.stringify({ accentColor: 'Violet', radius: 0.25, glowIntensity: 50 }),
    )
    const store = useAppearanceStore()
    expect(store.accentColor).toBe('Violet')
    expect(store.radius).toBe(0.25)
    expect(store.glowIntensity).toBe(50)
  })

  it('falls back to defaults on malformed JSON in localStorage', () => {
    localStorage.setItem('prexor-appearance', '{not json}')
    const store = useAppearanceStore()
    expect(store.accentColor).toBe('Cyan')
    expect(store.radius).toBe(0.75)
  })

  it('migrates from legacy cookies when localStorage is empty', () => {
    mockCookies.set('prexor-theme-color', { value: 'Rose' })
    mockCookies.set('prexor-theme-radius', { value: '0.5' })
    const store = useAppearanceStore()
    expect(store.accentColor).toBe('Rose')
    expect(store.radius).toBe(0.5)
  })

  it('setAccentColor updates state', () => {
    const store = useAppearanceStore()
    store.setAccentColor('Green')
    expect(store.accentColor).toBe('Green')
  })

  it('setCustomAccentColor switches accent to "Custom" and stores the hex', () => {
    const store = useAppearanceStore()
    store.setCustomAccentColor('#abcdef')
    expect(store.accentColor).toBe('Custom')
    expect(store.customAccentColor).toBe('#abcdef')
  })

  it('setRadius updates state and sets the CSS variable', () => {
    const store = useAppearanceStore()
    store.setRadius(0.25)
    expect(store.radius).toBe(0.25)
    expect(document.documentElement.style.getPropertyValue('--ui-radius')).toBe('0.25rem')
  })

  it('applyPalette writes to the active mode slot', () => {
    const store = useAppearanceStore()
    mockColorMode.value = 'dark'
    store.applyPalette('Soft Scandinavian')
    expect(store.darkPalettePreset).toBe('Soft Scandinavian')
    expect(store.lightPalettePreset).toBeNull()

    mockColorMode.value = 'light'
    store.applyPalette('Clean Modernist')
    expect(store.lightPalettePreset).toBe('Clean Modernist')
  })

  it('resetPalette clears the active mode slot only', () => {
    const store = useAppearanceStore()
    mockColorMode.value = 'dark'
    store.applyPalette('Soft Scandinavian')
    mockColorMode.value = 'light'
    store.applyPalette('Clean Modernist')

    mockColorMode.value = 'dark'
    store.resetPalette()
    expect(store.darkPalettePreset).toBeNull()
    expect(store.lightPalettePreset).toBe('Clean Modernist')
  })

  it('setCustomPalette saves a palette and switches the slot to "Custom"', () => {
    const store = useAppearanceStore()
    store.setCustomPalette('dark', '#000000', { '--background': '#111' }, 'My Theme')
    expect(store.customDarkPalette?.name).toBe('My Theme')
    expect(store.customDarkPalette?.baseColor).toBe('#000000')
    expect(store.customDarkPalette?.vars['--background']).toBe('#111')
    expect(store.darkPalettePreset).toBe('Custom')
  })

  it('setCustomPalette reuses the previous name when none is given', () => {
    const store = useAppearanceStore()
    store.setCustomPalette('light', '#fff', { '--background': '#fff' }, 'Snow')
    store.setCustomPalette('light', '#fff', { '--background': '#eee' })
    expect(store.customLightPalette?.name).toBe('Snow')
    expect(store.customLightPalette?.vars['--background']).toBe('#eee')
  })

  it('activePalettePreset reflects the dark slot in dark mode', () => {
    const store = useAppearanceStore()
    mockColorMode.value = 'dark'
    store.applyPalette('Soft Scandinavian')
    expect(store.activePalettePreset).toBe('Soft Scandinavian')
  })

it('addGlowBlob appends a default blob', () => {
    const store = useAppearanceStore()
    const before = store.glowBlobs.length
    store.addGlowBlob()
    expect(store.glowBlobs.length).toBe(before + 1)
    const last = store.glowBlobs[store.glowBlobs.length - 1]!
    expect(last.size).toBe(400)
    expect(last.color).toBe('primary')
  })

  it('removeGlowBlob deletes by index but refuses to drop the last blob', () => {
    const store = useAppearanceStore()
    // Reset to a known multi-blob state.
    store.addGlowBlob()
    store.addGlowBlob()
    const after = store.glowBlobs.length
    store.removeGlowBlob(0)
    expect(store.glowBlobs.length).toBe(after - 1)

    while (store.glowBlobs.length > 1) store.removeGlowBlob(0)
    store.removeGlowBlob(0) // no-op
    expect(store.glowBlobs.length).toBe(1)
  })

  it('updateGlowBlob mutates only the targeted index', () => {
    const store = useAppearanceStore()
    store.addGlowBlob()
    store.updateGlowBlob(0, { size: 999, color: 'secondary' })
    expect(store.glowBlobs[0]!.size).toBe(999)
    expect(store.glowBlobs[0]!.color).toBe('secondary')
  })

  it('updateGlowBlob with an out-of-range index does nothing', () => {
    const store = useAppearanceStore()
    expect(() => store.updateGlowBlob(999, { size: 1 })).not.toThrow()
  })

  it('setGlowEnabled and setGlowIntensity update state', () => {
    const store = useAppearanceStore()
    store.setGlowEnabled(false)
    store.setGlowIntensity(40)
    expect(store.glowEnabled).toBe(false)
    expect(store.glowIntensity).toBe(40)
  })

  it('resetGlows returns to the defaults', () => {
    const store = useAppearanceStore()
    store.setGlowEnabled(false)
    store.setGlowIntensity(10)
    store.addGlowBlob()
    const defaultCount = store.glowBlobs.length
    store.resetGlows()
    expect(store.glowEnabled).toBe(true)
    expect(store.glowIntensity).toBe(100)
    expect(store.glowBlobs.length).toBeLessThan(defaultCount)
  })

  it('navigateToSettings stores hints and consumeSettingsNav drains them', () => {
    const store = useAppearanceStore()
    store.navigateToSettings('theme', 'palette')
    let r = store.consumeSettingsNav()
    expect(r).toEqual({ tab: 'theme', subTab: 'palette' })
    r = store.consumeSettingsNav()
    expect(r).toEqual({ tab: null, subTab: null })
  })

  it('consumeSettingsNav after navigateToSettings without subTab returns null subTab', () => {
    const store = useAppearanceStore()
    store.navigateToSettings('integrations')
    expect(store.consumeSettingsNav()).toEqual({ tab: 'integrations', subTab: null })
  })

  it('writes to localStorage when persisted state changes', async () => {
    const store = useAppearanceStore()
    store.setAccentColor('Green')
    // Pinia + watch on a computed flushes asynchronously.
    await new Promise((resolve) => setTimeout(resolve, 0))
    const raw = localStorage.getItem('prexor-appearance')
    expect(raw).not.toBeNull()
    const parsed = JSON.parse(raw!) as { accentColor: string }
    expect(parsed.accentColor).toBe('Green')
  })
})
