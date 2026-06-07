import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import SettingsAmbientGlows from '../settings/SettingsAmbientGlows.vue'

function blob(overrides: Record<string, unknown> = {}) {
  return {
    x: 30, y: 40, size: 600, blur: 120, opacity: 12, intensity: 100,
    rotate: 0, scaleX: 100, scaleY: 100, color: 'primary',
    ...overrides,
  }
}

const { appearance, setGlowEnabled, updateGlowBlob, removeGlowBlob, addGlowBlob, resetGlows } = vi.hoisted(() => ({
  appearance: { glowEnabled: true, glowIntensity: 100, glowBlobs: [] as Record<string, unknown>[] },
  setGlowEnabled: vi.fn(),
  updateGlowBlob: vi.fn(),
  removeGlowBlob: vi.fn(),
  addGlowBlob: vi.fn(),
  resetGlows: vi.fn(),
}))

vi.mock('~/stores/appearance', () => ({
  useAppearanceStore: () =>
    reactive(Object.assign(appearance, { setGlowEnabled, updateGlowBlob, removeGlowBlob, addGlowBlob, resetGlows })),
}))

beforeEach(() => {
  appearance.glowEnabled = true
  appearance.glowIntensity = 100
  appearance.glowBlobs = [blob(), blob({ x: 70, y: 60, color: '#abcdef' })]
  setGlowEnabled.mockReset()
  updateGlowBlob.mockReset()
  removeGlowBlob.mockReset()
  addGlowBlob.mockReset()
  resetGlows.mockReset()
})

describe('SettingsAmbientGlows', () => {
  it('renders the header and hides the blob list when glows are disabled', async () => {
    appearance.glowEnabled = false
    const wrapper = await mountSuspended(SettingsAmbientGlows)
    expect(wrapper.find('h3').text()).toBe('Ambient Glows')
    expect(wrapper.text()).not.toContain('Blob 1')
    expect(wrapper.findAll('input[type="range"]')).toHaveLength(0)
  })

  it('renders one card per glow blob when enabled', async () => {
    const wrapper = await mountSuspended(SettingsAmbientGlows)
    expect(wrapper.text()).toContain('Blob 1')
    expect(wrapper.text()).toContain('Blob 2')
  })

  it('toggling the switch delegates to setGlowEnabled', async () => {
    const wrapper = await mountSuspended(SettingsAmbientGlows)
    await wrapper.find('[role="switch"]').trigger('click')
    expect(setGlowEnabled).toHaveBeenCalledWith(false)
  })

  it('clicking a blob header expands it with the primary border highlight', async () => {
    const wrapper = await mountSuspended(SettingsAmbientGlows)
    const card = wrapper.findAll('.rounded-xl.border')[0]!
    expect(card.classes()).not.toContain('border-primary/30')
    await card.find('.cursor-pointer').trigger('click')
    expect(card.classes()).toContain('border-primary/30')
  })

  it('shows a delete button per blob and removes by index', async () => {
    const wrapper = await mountSuspended(SettingsAmbientGlows)
    const del = wrapper.findAll('button.hover\\:text-destructive')
    expect(del).toHaveLength(2)
    await del[1]!.trigger('click')
    expect(removeGlowBlob).toHaveBeenCalledWith(1)
  })

  it('hides the delete button when only one blob remains', async () => {
    appearance.glowBlobs = [blob()]
    const wrapper = await mountSuspended(SettingsAmbientGlows)
    expect(wrapper.findAll('button.hover\\:text-destructive')).toHaveLength(0)
  })

  it('clicking a color preset updates the blob color', async () => {
    const wrapper = await mountSuspended(SettingsAmbientGlows)
    const secondary = wrapper.findAll('button').find((b) => b.text() === 'Secondary')!
    await secondary.trigger('click')
    expect(updateGlowBlob).toHaveBeenCalledWith(0, { color: 'secondary' })
  })

  it('a slider input updates the blob with a numeric value', async () => {
    const wrapper = await mountSuspended(SettingsAmbientGlows)
    const range = wrapper.find('input[type="range"]')
    ;(range.element as HTMLInputElement).value = '800'
    await range.trigger('input')
    expect(updateGlowBlob).toHaveBeenCalledWith(0, { size: 800 })
  })

  it('Add blob and Reset delegate to the store', async () => {
    const wrapper = await mountSuspended(SettingsAmbientGlows)
    const add = wrapper.findAll('button').find((b) => b.text().includes('Add blob'))!
    await add.trigger('click')
    expect(addGlowBlob).toHaveBeenCalled()
    const reset = wrapper.findAll('button').find((b) => b.text().includes('Reset'))!
    await reset.trigger('click')
    expect(resetGlows).toHaveBeenCalled()
  })
})
