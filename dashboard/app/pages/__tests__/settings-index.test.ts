import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import SettingsIndex from '../settings/index.vue'

const { appearance } = vi.hoisted(() => ({
  appearance: {
    consumeSettingsNav: vi.fn(() => ({ tab: null as string | null, subTab: null as string | null })),
  },
}))

mockNuxtImport('useAppearanceStore', () => () => reactive(appearance))

// The page's only job is tab orchestration; stub the heavy child panels so
// their own stores/composables aren't dragged into this test.
const stubs = {
  SettingsLanguage: { template: '<div data-stub="language">Language Panel</div>' },
  SettingsThemeMode: { template: '<div data-stub="mode">Mode Panel</div>' },
  SettingsAccentColor: { template: '<div data-stub="accent">Accent Panel</div>' },
  SettingsBorderRadius: { template: '<div data-stub="radius">Radius Panel</div>' },
  SettingsThemePalette: { template: '<div data-stub="palette">Palette Panel</div>' },
  SettingsAmbientGlows: { template: '<div data-stub="glows">Glows Panel</div>' },
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  appearance.consumeSettingsNav.mockReset().mockReturnValue({ tab: null, subTab: null })
})

function mount() {
  return mountSuspended(SettingsIndex, { global: { stubs } })
}

describe('SettingsIndex', () => {
  it('renders the page header from i18n copy', async () => {
    const wrapper = await mount()
    await flush()
    expect(wrapper.text()).toContain('Settings')
    expect(wrapper.text()).toContain('Customize how the dashboard looks and feels')
  })

  it('renders both sub-tabs', async () => {
    const wrapper = await mount()
    await flush()
    const tabLabels = wrapper.findAll('nav button').map(b => b.text())
    expect(tabLabels.some(l => l.includes('Preferences'))).toBe(true)
    expect(tabLabels.some(l => l.includes('Customize'))).toBe(true)
  })

  it('defaults to the preferences tab and shows its panels', async () => {
    const wrapper = await mount()
    await flush()
    expect(wrapper.find('[data-stub="language"]').exists()).toBe(true)
    expect(wrapper.find('[data-stub="mode"]').exists()).toBe(true)
    // Customize panels exist in the DOM (v-show) but the preferences panel is visible.
    const prefDiv = wrapper.find('[data-stub="language"]').element.parentElement as HTMLElement
    expect(prefDiv.style.display).not.toBe('none')
  })

  it('clicking the Customize tab reveals the palette panel', async () => {
    const wrapper = await mount()
    await flush()
    const customizeTab = wrapper.findAll('nav button').find(b => b.text().includes('Customize'))!
    await customizeTab.trigger('click')
    const paletteDiv = wrapper.find('[data-stub="palette"]').element.parentElement as HTMLElement
    expect(paletteDiv.style.display).not.toBe('none')
    // preferences panel is now hidden
    const prefDiv = wrapper.find('[data-stub="language"]').element.parentElement as HTMLElement
    expect(prefDiv.style.display).toBe('none')
  })

  it('the customize view exposes palette and glows sub-groups', async () => {
    const wrapper = await mount()
    await flush()
    const customizeTab = wrapper.findAll('nav button').find(b => b.text().includes('Customize'))!
    await customizeTab.trigger('click')
    const groupButtons = wrapper.findAll('button').map(b => b.text())
    expect(groupButtons.some(l => l.includes('Theme Palette'))).toBe(true)
    expect(groupButtons.some(l => l.includes('Ambient Glows'))).toBe(true)
  })

  it('switching the customize sub-group to glows shows the glows panel', async () => {
    const wrapper = await mount()
    await flush()
    await wrapper.findAll('nav button').find(b => b.text().includes('Customize'))!.trigger('click')
    // v-show on palette is on the stub's own root element.
    const palette = wrapper.find('[data-stub="palette"]').element as HTMLElement
    const glows = wrapper.find('[data-stub="glows"]').element as HTMLElement
    expect(palette.style.display).not.toBe('none')
    expect(glows.style.display).toBe('none')
    const glowsBtn = wrapper.findAll('button').find(b => b.text().includes('Ambient Glows'))!
    await glowsBtn.trigger('click')
    expect(glows.style.display).not.toBe('none')
    expect(palette.style.display).toBe('none')
  })

  it('honours a pending navigation hint that opens the customize tab', async () => {
    appearance.consumeSettingsNav.mockReturnValue({ tab: 'appearance', subTab: 'customize' })
    const wrapper = await mount()
    await flush()
    const paletteDiv = wrapper.find('[data-stub="palette"]').element.parentElement as HTMLElement
    expect(paletteDiv.style.display).not.toBe('none')
  })
})
