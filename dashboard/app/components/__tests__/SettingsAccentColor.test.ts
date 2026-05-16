import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import SettingsAccentColor from '../settings/SettingsAccentColor.vue'
import { accentColors } from '~/lib/theme-data'

const { colorMode, appearance, setAccentColor, setCustomAccentColor } = vi.hoisted(() => ({
  colorMode: { value: 'dark' } as { value: string },
  appearance: { accentColor: 'Cyan', customAccentColor: null } as {
    accentColor: string
    customAccentColor: string | null
  },
  setAccentColor: vi.fn(),
  setCustomAccentColor: vi.fn(),
}))

mockNuxtImport('useColorMode', () => () => reactive(colorMode))
vi.mock('~/stores/appearance', () => ({
  useAppearanceStore: () => reactive(Object.assign(appearance, { setAccentColor, setCustomAccentColor })),
}))

beforeEach(() => {
  colorMode.value = 'dark'
  appearance.accentColor = 'Cyan'
  appearance.customAccentColor = null
  setAccentColor.mockReset()
  setCustomAccentColor.mockReset()
})

describe('SettingsAccentColor', () => {
  it('renders one button per preset accent plus the custom-color label', async () => {
    const wrapper = await mountSuspended(SettingsAccentColor)
    expect(wrapper.findAll('button')).toHaveLength(accentColors.length)
    expect(wrapper.find('label').text()).toContain('Custom')
  })

  it('marks the active accent with the primary border class', async () => {
    appearance.accentColor = 'Violet'
    const wrapper = await mountSuspended(SettingsAccentColor)
    const active = wrapper.findAll('button').find((b) => b.text().includes('Violet'))
    expect(active!.classes()).toContain('border-primary')
  })

  it('clicking a preset accent calls setAccentColor with its name', async () => {
    const wrapper = await mountSuspended(SettingsAccentColor)
    const rose = wrapper.findAll('button').find((b) => b.text().includes('Rose'))
    await rose!.trigger('click')
    expect(setAccentColor).toHaveBeenCalledWith('Rose')
  })

  it('the color input forwards its value to setCustomAccentColor', async () => {
    const wrapper = await mountSuspended(SettingsAccentColor)
    const input = wrapper.find('input[type="color"]')
    ;(input.element as HTMLInputElement).value = '#abcdef'
    await input.trigger('input')
    expect(setCustomAccentColor).toHaveBeenCalledWith('#abcdef')
  })

  it('uses the dark swatch value in dark mode and the light value otherwise', async () => {
    const cyan = accentColors.find((c) => c.name === 'Cyan')!

    const dark = await mountSuspended(SettingsAccentColor)
    const darkSwatch = dark.findAll('button').find((b) => b.text().includes('Cyan'))!.find('span')
    expect(darkSwatch.attributes('style')).toContain(cyan.value)

    colorMode.value = 'light'
    const light = await mountSuspended(SettingsAccentColor)
    const lightSwatch = light.findAll('button').find((b) => b.text().includes('Cyan'))!.find('span')
    expect(lightSwatch.attributes('style')).toContain(cyan.light)
  })
})
