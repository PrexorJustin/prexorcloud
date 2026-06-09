import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import AmbientGlows from '../layout/AmbientGlows.vue'
import type { GlowBlob } from '~/lib/theme-data'

const { store } = vi.hoisted(() => ({
  store: { glowEnabled: true, glowIntensity: 100, glowBlobs: [] as GlowBlob[] },
}))

mockNuxtImport('useAppearanceStore', () => () => reactive(store))

function blob(overrides: Partial<GlowBlob> = {}): GlowBlob {
  return {
    x: 25, y: 40, size: 500, blur: 120, opacity: 8, intensity: 100,
    color: 'primary', scaleX: 100, scaleY: 100, rotate: 0,
    ...overrides,
  }
}

beforeEach(() => {
  store.glowEnabled = true
  store.glowIntensity = 100
  store.glowBlobs = [blob()]
})

describe('AmbientGlows', () => {
  it('renders nothing when glows are disabled', async () => {
    store.glowEnabled = false
    const wrapper = await mountSuspended(AmbientGlows)
    expect(wrapper.find('div').exists()).toBe(false)
  })

  it('renders one blob element per glow blob', async () => {
    store.glowBlobs = [blob(), blob({ x: 75 }), blob({ x: 50 })]
    const wrapper = await mountSuspended(AmbientGlows)
    expect(wrapper.findAll('.absolute')).toHaveLength(3)
  })

  it('positions a blob from its x/y as percentages', async () => {
    store.glowBlobs = [blob({ x: 25, y: 40 })]
    const wrapper = await mountSuspended(AmbientGlows)
    const style = wrapper.find('.absolute').attributes('style') ?? ''
    expect(style).toContain('left: 25%')
    expect(style).toContain('top: 40%')
  })

  it('sizes and blurs a blob from its size/blur fields', async () => {
    // happy-dom drops the unsupported color-mix() backgroundColor, so the
    // colour wrapping is left unasserted — size/blur/filter still apply.
    store.glowBlobs = [blob({ size: 320, blur: 80 })]
    const wrapper = await mountSuspended(AmbientGlows)
    const style = wrapper.find('.absolute').attributes('style') ?? ''
    expect(style).toContain('width: 320px')
    expect(style).toContain('height: 320px')
    expect(style).toContain('blur(80px)')
  })
})
