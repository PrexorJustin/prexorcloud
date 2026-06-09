import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import SettingsThemeMode from '../settings/SettingsThemeMode.vue'

const { colorMode } = vi.hoisted(() => ({
  colorMode: { preference: 'system', value: 'dark' } as { preference: string; value: string },
}))

mockNuxtImport('useColorMode', () => () => reactive(colorMode))

beforeEach(() => {
  colorMode.preference = 'system'
})

describe('SettingsThemeMode', () => {
  it('renders a button for light, dark, and system', async () => {
    const wrapper = await mountSuspended(SettingsThemeMode)
    const labels = wrapper.findAll('button').map((b) => b.text())
    expect(labels.some((t) => t.includes('Light'))).toBe(true)
    expect(labels.some((t) => t.includes('Dark'))).toBe(true)
    expect(labels.some((t) => t.includes('System'))).toBe(true)
  })

  it('highlights the button matching the current preference', async () => {
    colorMode.preference = 'dark'
    const wrapper = await mountSuspended(SettingsThemeMode)
    const active = wrapper.findAll('button').find((b) => b.text().includes('Dark'))
    expect(active!.classes()).toContain('border-primary')
  })

  it('clicking a mode button updates the colorMode preference', async () => {
    const wrapper = await mountSuspended(SettingsThemeMode)
    const lightBtn = wrapper.findAll('button').find((b) => b.text().includes('Light'))
    await lightBtn!.trigger('click')
    expect(colorMode.preference).toBe('light')
  })
})
