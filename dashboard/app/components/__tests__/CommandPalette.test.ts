import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CommandPalette from '../layout/CommandPalette.vue'

const { auth, colorMode, routerPush } = vi.hoisted(() => ({
  auth: { can: vi.fn(() => true) },
  colorMode: { preference: 'system' } as { preference: string },
  routerPush: vi.fn(),
}))

mockNuxtImport('useAuthStore', () => () => reactive(auth))
mockNuxtImport('useColorMode', () => () => reactive(colorMode))
mockNuxtImport('useRouter', () => () => ({
  push: routerPush,
  replace: vi.fn(),
  afterEach: vi.fn(),
  beforeEach: vi.fn(),
  beforeResolve: vi.fn(),
  isReady: () => Promise.resolve(),
}))
vi.mock('~/composables/useResourceSearchIndex', () => ({
  useResourceSearchIndex: () => ({ search: () => [] }),
}))

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
  document.body.innerHTML = ''
})

beforeEach(() => {
  auth.can.mockReset()
  auth.can.mockReturnValue(true)
  colorMode.preference = 'system'
  routerPush.mockReset()
})

async function mount() {
  const wrapper = await mountSuspended(CommandPalette)
  active = wrapper
  return wrapper
}

function bodyText() {
  return document.body.textContent ?? ''
}

describe('CommandPalette', () => {
  it('renders a search trigger with a Ctrl+K hint', async () => {
    const wrapper = await mount()
    expect(wrapper.text()).toContain('Search…')
    expect(wrapper.text()).toContain('Ctrl+K')
  })

  it('clicking the trigger opens the dialog with the nav groups', async () => {
    const wrapper = await mount()
    await wrapper.find('button').trigger('click')
    expect(bodyText()).toContain('Workloads')
    expect(bodyText()).toContain('Theme')
  })

  it('Cmd+K toggles the dialog open', async () => {
    await mount()
    expect(bodyText()).not.toContain('Workloads')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true }))
    await new Promise((r) => setTimeout(r))
    expect(bodyText()).toContain('Workloads')
  })

  it('hides permission-gated groups when access is denied', async () => {
    auth.can.mockReturnValue(false)
    const wrapper = await mount()
    await wrapper.find('button').trigger('click')
    // Identity's items are all permission-gated → group dropped
    expect(bodyText()).not.toContain('Identity')
    expect(bodyText()).toContain('Workloads')
  })

  it('marks the active theme matching colorMode.preference', async () => {
    colorMode.preference = 'dark'
    const wrapper = await mount()
    await wrapper.find('button').trigger('click')
    const darkItem = Array.from(document.body.querySelectorAll('[role="option"]'))
      .find((el) => el.textContent?.includes('Dark'))
    expect(darkItem?.textContent).toContain('Active')
  })

  it('selecting a theme item updates colorMode.preference', async () => {
    const wrapper = await mount()
    await wrapper.find('button').trigger('click')
    const lightItem = Array.from(document.body.querySelectorAll('[role="option"]'))
      .find((el) => el.textContent?.includes('Light')) as HTMLElement
    lightItem.click()
    await new Promise((r) => setTimeout(r))
    expect(colorMode.preference).toBe('light')
  })

  it('selecting a nav item routes to its url', async () => {
    const wrapper = await mount()
    await wrapper.find('button').trigger('click')
    const groupsItem = Array.from(document.body.querySelectorAll('[role="option"]'))
      .find((el) => el.textContent?.trim() === 'Groups') as HTMLElement
    groupsItem.click()
    await new Promise((r) => setTimeout(r))
    expect(routerPush).toHaveBeenCalledWith('/groups')
  })
})
