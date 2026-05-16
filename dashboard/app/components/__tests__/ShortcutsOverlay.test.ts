import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ShortcutsOverlay from '../layout/ShortcutsOverlay.vue'
import { shortcuts } from '~/lib/shortcuts'

// Reka UI Dialog teleports content to <body>.
function bodyText() {
  return document.body.textContent ?? ''
}

describe('ShortcutsOverlay', () => {
  it('renders every visible shortcut label when open', async () => {
    await mountSuspended(ShortcutsOverlay, { props: { open: true } })
    for (const s of shortcuts.filter(s => !s.hidden)) {
      expect(bodyText()).toContain(s.label)
    }
  })

  it('omits hidden shortcuts from the overlay', async () => {
    await mountSuspended(ShortcutsOverlay, { props: { open: true } })
    for (const s of shortcuts.filter(s => s.hidden)) {
      expect(bodyText()).not.toContain(s.label)
    }
  })

  it('renders a section heading for every visible section', async () => {
    await mountSuspended(ShortcutsOverlay, { props: { open: true } })
    const sections = new Set(shortcuts.filter(s => !s.hidden).map(s => s.section))
    for (const section of sections) {
      expect(bodyText()).toContain(section)
    }
  })

  it('splits a two-key chord into one kbd hint per token', async () => {
    await mountSuspended(ShortcutsOverlay, { props: { open: true } })
    // "g d" → the row for "Go to dashboard" should carry two kbd tokens.
    const label = Array.from(document.body.querySelectorAll('span'))
      .find(s => s.textContent === 'Go to dashboard')
    expect(label).toBeDefined()
    const row = label!.parentElement!
    expect(row.querySelectorAll('kbd').length).toBe(2)
  })

  it('renders nothing when closed', async () => {
    const wrapper = await mountSuspended(ShortcutsOverlay, { props: { open: false } })
    expect(wrapper.text()).toBe('')
  })
})
