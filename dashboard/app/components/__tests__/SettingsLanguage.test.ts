import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import SettingsLanguage from '../settings/SettingsLanguage.vue'

describe('SettingsLanguage', () => {
  it('renders the section heading and description from i18n', async () => {
    const wrapper = await mountSuspended(SettingsLanguage)
    expect(wrapper.text()).toContain('Language')
    expect(wrapper.text()).toContain('Choose the language used across the dashboard.')
  })

  it('lists every available locale', async () => {
    const wrapper = await mountSuspended(SettingsLanguage)
    const buttons = wrapper.findAll('button')
    expect(buttons).toHaveLength(2)
    expect(wrapper.text()).toContain('English')
    expect(wrapper.text()).toContain('Deutsch')
  })

  it('switches locale when a language is selected', async () => {
    const wrapper = await mountSuspended(SettingsLanguage)
    const deButton = wrapper.findAll('button').find(b => b.text().includes('Deutsch'))
    expect(deButton).toBeDefined()
    await deButton!.trigger('click')
    await nextTick()
    // Heading re-renders with the German catalogue entry once the locale flips.
    expect(wrapper.text()).toContain('Sprache')
  })
})
