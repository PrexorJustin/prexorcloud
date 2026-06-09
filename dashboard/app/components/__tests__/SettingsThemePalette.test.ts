import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import SettingsThemePalette from '../settings/SettingsThemePalette.vue'
import { lightPresets, darkPresets } from '~/lib/theme-data'

const { colorMode, appearance } = vi.hoisted(() => ({
  colorMode: { value: 'light' } as { value: string },
  appearance: {
    lightPalettePreset: null as string | null,
    darkPalettePreset: null as string | null,
    customLightPalette: null as unknown,
    customDarkPalette: null as unknown,
    applyPalette: vi.fn(),
    resetPalette: vi.fn(),
    setCustomPalette: vi.fn(),
  },
}))

mockNuxtImport('useColorMode', () => () => reactive(colorMode))
mockNuxtImport('useAppearanceStore', () => () => reactive(appearance))

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
})

beforeEach(() => {
  colorMode.value = 'light'
  appearance.lightPalettePreset = null
  appearance.darkPalettePreset = null
  appearance.customLightPalette = null
  appearance.customDarkPalette = null
  appearance.applyPalette.mockReset()
  appearance.resetPalette.mockReset()
  appearance.setCustomPalette.mockReset()
})

async function mount() {
  const wrapper = await mountSuspended(SettingsThemePalette)
  active = wrapper
  return wrapper
}
function buttonWith(wrapper: Awaited<ReturnType<typeof mount>>, text: string) {
  return wrapper.findAll('button').find((b) => b.text().includes(text))
}

describe('SettingsThemePalette', () => {
  it('renders the header and both mode toggles', async () => {
    const wrapper = await mount()
    expect(wrapper.find('h3').text()).toBe('Theme Palette')
    expect(wrapper.text()).toContain('Light Palettes')
    expect(wrapper.text()).toContain('Dark Palettes')
  })

  it('renders one preset button per light preset in light mode', async () => {
    const wrapper = await mount()
    for (const preset of lightPresets) {
      expect(buttonWith(wrapper, preset.name)).toBeDefined()
    }
  })

  it('switching to the Dark toggle renders the dark presets', async () => {
    const wrapper = await mount()
    await buttonWith(wrapper, 'Dark Palettes')!.trigger('click')
    for (const preset of darkPresets) {
      expect(buttonWith(wrapper, preset.name)).toBeDefined()
    }
  })

  it('clicking a preset stores it and applies it when the mode is active', async () => {
    const wrapper = await mount()
    const name = lightPresets[0]!.name
    await buttonWith(wrapper, name)!.trigger('click')
    expect(appearance.lightPalettePreset).toBe(name)
    expect(appearance.applyPalette).toHaveBeenCalledWith(name)
  })

  it('stores a dark preset without applying it while light mode is active', async () => {
    const wrapper = await mount()
    await buttonWith(wrapper, 'Dark Palettes')!.trigger('click')
    const name = darkPresets[0]!.name
    await buttonWith(wrapper, name)!.trigger('click')
    expect(appearance.darkPalettePreset).toBe(name)
    expect(appearance.applyPalette).not.toHaveBeenCalled()
  })

  it('hides Reset until a palette is active, then resets on click', async () => {
    const first = await mount()
    expect(buttonWith(first, 'Reset')).toBeUndefined()
    first.unmount()

    appearance.lightPalettePreset = lightPresets[0]!.name
    const wrapper = await mount()
    const reset = buttonWith(wrapper, 'Reset')!
    expect(reset).toBeDefined()
    await reset.trigger('click')
    expect(appearance.lightPalettePreset).toBeNull()
    expect(appearance.resetPalette).toHaveBeenCalled()
  })
})
