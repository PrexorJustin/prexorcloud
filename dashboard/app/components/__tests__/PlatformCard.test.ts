import { describe, it, expect, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import PlatformCard from '../catalog/PlatformCard.vue'
import type { CatalogEntry } from '~/types/api'

// `navigateTo` is a Nuxt auto-import resolved at compile time — vi.stubGlobal
// can't intercept it, so the card-click → navigate wiring isn't asserted here.
vi.stubGlobal('navigateTo', vi.fn())

const serverEntry: CatalogEntry = {
  platform: 'paper', category: 'SERVER', configFormat: 'YAML',
  versions: [
    { version: '1.21.1', downloadUrl: 'u1', recommended: false },
    { version: '1.20.4', downloadUrl: 'u2', recommended: true },
  ],
}

describe('PlatformCard', () => {
  it('renders the platform name uppercased with its config format', async () => {
    const wrapper = await mountSuspended(PlatformCard, { props: { entry: serverEntry } })
    const name = wrapper.find('p.uppercase')
    expect(name.text()).toBe('paper')
    expect(name.classes()).toContain('uppercase')
    expect(wrapper.text()).toContain('YAML')
  })

  it('shows the version count and the recommended version', async () => {
    const wrapper = await mountSuspended(PlatformCard, { props: { entry: serverEntry } })
    expect(wrapper.text()).toContain('2')        // versions.length
    expect(wrapper.text()).toContain('1.20.4')   // the recommended one
  })

  it('falls back to "None" when no version is marked recommended', async () => {
    const entry: CatalogEntry = {
      ...serverEntry,
      versions: [{ version: '1.0', downloadUrl: 'u', recommended: false }],
    }
    const wrapper = await mountSuspended(PlatformCard, { props: { entry } })
    expect(wrapper.text()).toContain('None')
  })

  it('labels a SERVER entry "Server" and a PROXY entry "Proxy"', async () => {
    const server = await mountSuspended(PlatformCard, { props: { entry: serverEntry } })
    expect(server.text()).toContain('Server')

    const proxy = await mountSuspended(PlatformCard, {
      props: { entry: { ...serverEntry, platform: 'velocity', category: 'PROXY' } },
    })
    expect(proxy.text()).toContain('Proxy')
  })

  it('tolerates a missing config format', async () => {
    const wrapper = await mountSuspended(PlatformCard, {
      props: { entry: { ...serverEntry, configFormat: null } },
    })
    expect(wrapper.text()).toContain('Unknown format')
    expect(wrapper.text()).toContain('N/A')
  })
})
