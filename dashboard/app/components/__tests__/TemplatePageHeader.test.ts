import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TemplatePageHeader from '../templates/TemplatePageHeader.vue'
import type { Template } from '~/types/api'

function template(overrides: Partial<Template> = {}): Template {
  return {
    name: 'survival', platform: 'paper', sizeBytes: 2048,
    ...overrides,
  } as Template
}

function props(overrides: Record<string, unknown> = {}) {
  return {
    templateName: 'survival',
    template: template(),
    hasChanges: false,
    stagedCount: 0,
    saving: false,
    ...overrides,
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function findButton(wrapper: any, text: string) {
  return wrapper.findAll('button').find((b: { text: () => string }) => b.text().includes(text))
}

describe('TemplatePageHeader', () => {
  it('renders the template name in the heading', async () => {
    const wrapper = await mountSuspended(TemplatePageHeader, { props: props() })
    expect(wrapper.find('h1').text()).toBe('survival')
  })

  it('renders the platform and size when a template is loaded', async () => {
    const wrapper = await mountSuspended(TemplatePageHeader, { props: props() })
    expect(wrapper.text()).toContain('paper')
  })

  it('omits the platform line when the template is null', async () => {
    const wrapper = await mountSuspended(TemplatePageHeader, { props: props({ template: null }) })
    expect(wrapper.text()).not.toContain('paper')
  })

  it('shows the staged badge only when there are changes', async () => {
    const without = await mountSuspended(TemplatePageHeader, { props: props() })
    expect(without.text()).not.toContain('staged')
    const withChanges = await mountSuspended(TemplatePageHeader, {
      props: props({ hasChanges: true, stagedCount: 3 }),
    })
    expect(withChanges.text()).toContain('3 staged')
  })

  it('shows the Discard button only when there are changes', async () => {
    const without = await mountSuspended(TemplatePageHeader, { props: props() })
    expect(without.findAll('button').some(b => b.text().includes('Discard'))).toBe(false)
    const withChanges = await mountSuspended(TemplatePageHeader, { props: props({ hasChanges: true }) })
    expect(withChanges.findAll('button').some(b => b.text().includes('Discard'))).toBe(true)
  })

  it('disables Save when there are no changes', async () => {
    const wrapper = await mountSuspended(TemplatePageHeader, { props: props() })
    expect(findButton(wrapper, 'Save & Rehash')!.attributes('disabled')).toBeDefined()
  })

  it('enables Save when there are changes and shows the saving label while saving', async () => {
    const ready = await mountSuspended(TemplatePageHeader, { props: props({ hasChanges: true }) })
    expect(findButton(ready, 'Save & Rehash')!.attributes('disabled')).toBeUndefined()
    const saving = await mountSuspended(TemplatePageHeader, {
      props: props({ hasChanges: true, saving: true }),
    })
    const btn = saving.findAll('button').find(b => b.text().includes('Saving...'))!
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('emits back, export, discard and save from their buttons', async () => {
    const wrapper = await mountSuspended(TemplatePageHeader, { props: props({ hasChanges: true }) })
    await wrapper.findAll('button')[0]!.trigger('click') // back (icon-only, first button)
    await findButton(wrapper, 'Export')!.trigger('click')
    await findButton(wrapper, 'Discard')!.trigger('click')
    await findButton(wrapper, 'Save & Rehash')!.trigger('click')
    expect(wrapper.emitted('back')).toHaveLength(1)
    expect(wrapper.emitted('export')).toHaveLength(1)
    expect(wrapper.emitted('discard')).toHaveLength(1)
    expect(wrapper.emitted('save')).toHaveLength(1)
  })
})
