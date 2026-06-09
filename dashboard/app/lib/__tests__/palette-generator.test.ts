import { describe, it, expect } from 'vitest'
import { generateLightPalette, generateDarkPalette, getPreviewColors } from '../palette-generator'

const EXPECTED_KEYS = [
  '--background', '--foreground',
  '--card', '--card-foreground',
  '--popover', '--popover-foreground',
  '--muted', '--muted-foreground',
  '--accent', '--accent-foreground',
  '--border', '--input',
  '--glass', '--glass-hover', '--glass-border',
  '--sidebar', '--sidebar-foreground',
  '--sidebar-accent', '--sidebar-accent-foreground', '--sidebar-border',
]

function isHex6(s: string | undefined) {
  return !!s && /^#[0-9a-f]{6}$/i.test(s)
}

function isHexOrAlphaSuffixed(s: string | undefined) {
  return !!s && /^#[0-9a-f]{6,8}$/i.test(s)
}

describe('palette-generator', () => {
  it('generateLightPalette emits every expected CSS var key', () => {
    const p = generateLightPalette('#06b6d4')
    for (const k of EXPECTED_KEYS) {
      expect(p, k).toHaveProperty(k)
    }
  })

  it('generateDarkPalette emits every expected CSS var key', () => {
    const p = generateDarkPalette('#06b6d4')
    for (const k of EXPECTED_KEYS) {
      expect(p, k).toHaveProperty(k)
    }
  })

  it('light palette is HSL-derived solid hex for background/foreground', () => {
    const p = generateLightPalette('#06b6d4')
    expect(isHex6(p['--background'])).toBe(true)
    expect(isHex6(p['--foreground'])).toBe(true)
    expect(isHex6(p['--sidebar'])).toBe(true)
    // popover has an ee alpha suffix
    expect(isHexOrAlphaSuffixed(p['--popover'])).toBe(true)
    expect(p['--popover'].endsWith('ee')).toBe(true)
  })

  it('dark palette uses the white-alpha shorthand for glass + border surfaces', () => {
    const p = generateDarkPalette('#06b6d4')
    expect(p['--card']).toBe('#ffffff08')
    expect(p['--accent']).toBe('#ffffff08')
    expect(p['--glass']).toBe('#ffffff08')
    expect(p['--glass-hover']).toBe('#ffffff12')
    expect(p['--glass-border']).toBe('#ffffff15')
    expect(p['--border']).toBe('#ffffff15')
    expect(p['--input']).toBe('#ffffff10')
  })

  it('light vs. dark backgrounds diverge for the same base color', () => {
    const light = generateLightPalette('#06b6d4')
    const dark = generateDarkPalette('#06b6d4')
    expect(light['--background']).not.toBe(dark['--background'])
    expect(light['--foreground']).not.toBe(dark['--foreground'])
  })

  it('produces deterministic output for the same base color', () => {
    expect(generateLightPalette('#06b6d4')).toEqual(generateLightPalette('#06b6d4'))
    expect(generateDarkPalette('#06b6d4')).toEqual(generateDarkPalette('#06b6d4'))
  })

  it('handles a grayscale base color (hue computed from equal RGB)', () => {
    const p = generateLightPalette('#808080')
    expect(isHex6(p['--background'])).toBe(true)
    expect(isHex6(p['--foreground'])).toBe(true)
  })

  it('different base colors yield different sidebar swatches', () => {
    const cyan = generateLightPalette('#06b6d4')
    const rose = generateLightPalette('#f43f5e')
    expect(cyan['--sidebar']).not.toBe(rose['--sidebar'])
  })

  it('getPreviewColors returns 4 swatches and strips alpha suffix from popover', () => {
    const p = generateLightPalette('#06b6d4')
    const swatches = getPreviewColors(p)
    expect(swatches).toHaveLength(4)
    for (const s of swatches) {
      // Either a 6-hex or an empty string for missing vars; never "…ee".
      expect(s.endsWith('ee')).toBe(false)
    }
  })

  it('getPreviewColors falls back to --border when --sidebar-border is missing', () => {
    const sparse: Record<string, string> = {
      '--background': '#aaaaaa',
      '--card': '#bbbbbb',
      '--border': '#cccccc',
      '--sidebar': '#dddddd',
    }
    const out = getPreviewColors(sparse)
    expect(out[2]).toBe('#cccccc')
  })
})
