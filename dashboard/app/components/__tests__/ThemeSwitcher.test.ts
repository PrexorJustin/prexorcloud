import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ThemeSwitcher from '../layout/ThemeSwitcher.vue'
import { accentColors, radii } from '~/lib/theme-data'

const { colorMode, appearance, routerPush } = vi.hoisted(() => ({
  colorMode: { preference: 'system' } as { preference: string },
  appearance: {
    accentColor: 'cyan',
    radius: '0.5',
    setAccentColor: vi.fn(),
    setRadius: vi.fn(),
    navigateToSettings: vi.fn(),
  },
  routerPush: vi.fn(),
}))

mockNuxtImport('useColorMode', () => () => reactive(colorMode))
mockNuxtImport('useAppearanceStore', () => () => reactive(appearance))
// Nuxt's own test setup also calls useRouter() (afterEach/beforeResolve
// guards), so the stub has to satisfy those too — not just push().
mockNuxtImport('useRouter', () => () => ({
  push: routerPush,
  replace: vi.fn(),
  afterEach: vi.fn(),
  beforeEach: vi.fn(),
  beforeResolve: vi.fn(),
  isReady: () => Promise.resolve(),
}))

// Reka UI Popover teleports content to <body>.
function bodyButtons() {
  return Array.from(document.body.querySelectorAll('button'))
}
function findBodyButton(text: string | number) {
  return bodyButtons().find(b => b.textContent?.trim() === String(text))
}

// Popover content teleports to <body> and is not torn down between mounts, so
// each test tracks its wrapper and unmounts it afterwards to avoid stale DOM.
let active: { unmount: () => void } | null = null

async function openPopover() {
  const wrapper = await mountSuspended(ThemeSwitcher)
  active = wrapper
  await wrapper.find('button').trigger('click')
  return wrapper
}

afterEach(() => {
  active?.unmount()
  active = null
})

beforeEach(() => {
  colorMode.preference = 'system'
  appearance.accentColor = 'cyan'
  appearance.radius = '0.5'
  appearance.setAccentColor.mockReset()
  appearance.setRadius.mockReset()
  appearance.navigateToSettings.mockReset()
  routerPush.mockReset()
})

describe('ThemeSwitcher', () => {
  it('renders the trigger button with an sr-only label', async () => {
    const wrapper = await mountSuspended(ThemeSwitcher)
    expect(wrapper.find('.sr-only').text()).toBe('Customize theme')
  })

  it('renders one button per accent color when open', async () => {
    await openPopover()
    for (const color of accentColors) {
      expect(findBodyButton(color.name)).toBeDefined()
    }
  })

  it('highlights the active accent color', async () => {
    appearance.accentColor = accentColors[1]!.name
    await openPopover()
    expect(findBodyButton(accentColors[1]!.name)!.className).toContain('border-primary')
  })

  it('clicking an accent calls setAccentColor', async () => {
    await openPopover()
    findBodyButton(accentColors[0]!.name)!.click()
    expect(appearance.setAccentColor).toHaveBeenCalledWith(accentColors[0]!.name)
  })

  it('renders one button per radius and highlights the active one', async () => {
    appearance.radius = radii[2]!
    await openPopover()
    for (const r of radii) {
      expect(findBodyButton(r)).toBeDefined()
    }
    expect(findBodyButton(radii[2]!)!.className).toContain('border-primary')
  })

  it('clicking a radius calls setRadius', async () => {
    await openPopover()
    findBodyButton(radii[0]!)!.click()
    expect(appearance.setRadius).toHaveBeenCalledWith(radii[0])
  })

  it('highlights the mode matching colorMode.preference and updates it on click', async () => {
    colorMode.preference = 'dark'
    await openPopover()
    const dark = bodyButtons().find(b => b.textContent?.includes('Dark'))
    expect(dark!.className).toContain('border-primary')
    const light = bodyButtons().find(b => b.textContent?.includes('Light'))
    light!.click()
    expect(colorMode.preference).toBe('light')
  })

  it('"Advanced Customization" routes to settings with the appearance hint', async () => {
    await openPopover()
    const advanced = bodyButtons().find(b => b.textContent?.includes('Advanced Customization'))
    advanced!.click()
    expect(appearance.navigateToSettings).toHaveBeenCalledWith('appearance', 'customize')
    expect(routerPush).toHaveBeenCalledWith('/settings')
  })
})
