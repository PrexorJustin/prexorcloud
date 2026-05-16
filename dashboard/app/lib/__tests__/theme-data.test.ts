import { describe, it, expect } from 'vitest'
import {
  accentColors,
  radii,
  lightPresets,
  darkPresets,
  defaultGlowBlobs,
  glowPresets,
  paletteVarKeys,
} from '../theme-data'

const HEX = /^#[0-9a-fA-F]{3,8}$/

describe('lib/theme-data', () => {
  it('every accent color carries a name + dark/light hex pair', () => {
    expect(accentColors.length).toBeGreaterThan(0)
    for (const c of accentColors) {
      expect(c.name.length, c.name).toBeGreaterThan(0)
      expect(c.value, c.name).toMatch(HEX)
      expect(c.light, c.name).toMatch(HEX)
    }
  })

  it('accent color names are unique', () => {
    const names = accentColors.map((c) => c.name)
    expect(new Set(names).size).toBe(names.length)
  })

  it('radii is an ascending list starting at 0', () => {
    expect(radii[0]).toBe(0)
    for (let i = 1; i < radii.length; i++) {
      expect(radii[i]!).toBeGreaterThan(radii[i - 1]!)
    }
  })

  it('paletteVarKeys has no duplicates', () => {
    expect(new Set(paletteVarKeys).size).toBe(paletteVarKeys.length)
  })

  it('every light/dark preset defines exactly the paletteVarKeys set', () => {
    for (const preset of [...lightPresets, ...darkPresets]) {
      const keys = Object.keys(preset.vars).sort()
      expect(keys, preset.name).toEqual([...paletteVarKeys].sort())
    }
  })

  it('every preset has a name, description, and a 4-swatch preview', () => {
    for (const preset of [...lightPresets, ...darkPresets]) {
      expect(preset.name.length).toBeGreaterThan(0)
      expect(preset.description.length, preset.name).toBeGreaterThan(0)
      expect(preset.preview, preset.name).toHaveLength(4)
    }
  })

  it('preset names are unique within and across light/dark sets', () => {
    const names = [...lightPresets, ...darkPresets].map((p) => p.name)
    expect(new Set(names).size).toBe(names.length)
  })

  it('defaultGlowBlobs are within the documented coordinate/percentage ranges', () => {
    expect(defaultGlowBlobs.length).toBeGreaterThan(0)
    for (const b of defaultGlowBlobs) {
      expect(b.x).toBeGreaterThanOrEqual(0)
      expect(b.x).toBeLessThanOrEqual(100)
      expect(b.y).toBeGreaterThanOrEqual(0)
      expect(b.y).toBeLessThanOrEqual(100)
      expect(['primary', 'secondary']).toContain(b.color)
    }
  })

  it('glowPresets include an Off preset and all carry an intensity', () => {
    const off = glowPresets.find((p) => p.name === 'Off')
    expect(off).toBeDefined()
    expect(off!.enabled).toBe(false)
    expect(off!.intensity).toBe(0)
    for (const p of glowPresets) {
      expect(typeof p.intensity, p.name).toBe('number')
    }
  })
})
