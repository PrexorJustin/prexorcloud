import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import SettingsBorderRadius from '../settings/SettingsBorderRadius.vue'
import { radii } from '~/lib/theme-data'

const { appearance, setRadius } = vi.hoisted(() => ({
  appearance: { radius: 0.5 } as { radius: number },
  setRadius: vi.fn(),
}))

vi.mock('~/stores/appearance', () => ({
  useAppearanceStore: () => reactive(Object.assign(appearance, { setRadius })),
}))

beforeEach(() => {
  appearance.radius = 0.5
  setRadius.mockReset()
})

describe('SettingsBorderRadius', () => {
  it('renders one swatch button per radius option', async () => {
    const wrapper = await mountSuspended(SettingsBorderRadius)
    expect(wrapper.findAll('button')).toHaveLength(radii.length)
  })

  it('marks the active radius with the primary border class', async () => {
    appearance.radius = 1
    const wrapper = await mountSuspended(SettingsBorderRadius)
    const active = wrapper.findAll('button').find((b) => b.text() === '1')
    expect(active!.classes()).toContain('border-primary')
  })

  it('clicking a swatch calls setRadius with that value', async () => {
    const wrapper = await mountSuspended(SettingsBorderRadius)
    const buttons = wrapper.findAll('button')
    await buttons[buttons.length - 1]!.trigger('click')
    expect(setRadius).toHaveBeenCalledWith(radii[radii.length - 1])
  })
})
