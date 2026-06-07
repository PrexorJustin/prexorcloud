import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TemplateOverviewPanel from '../templates/TemplateOverviewPanel.vue'
import type { Template } from '~/types/api'

const { updateTemplate, toastError } = vi.hoisted(() => ({
  updateTemplate: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/templates', () => ({
  useTemplatesStore: () => ({ updateTemplate }),
}))

function template(overrides: Partial<Template> = {}): Template {
  return {
    name: 'survival', platform: 'paper', description: 'main world',
    sizeBytes: 4096, hash: 'abcdef0123456789abcdef0123456789',
    ...overrides,
  } as Template
}

function props(overrides: Record<string, unknown> = {}) {
  return {
    template: template(),
    templateName: 'survival',
    isBaseTemplate: false,
    inheritanceChain: [],
    templateVariables: [],
    variablesSaving: false,
    scanningVariables: false,
    usedByGroups: [],
    groupsLoading: false,
    deleteLoading: false,
    ...overrides,
  }
}

beforeEach(() => {
  updateTemplate.mockReset().mockResolvedValue(template({ platform: 'velocity' }))
  toastError.mockReset()
})

describe('TemplateOverviewPanel', () => {
  it('renders platform, size and a truncated hash', async () => {
    const wrapper = await mountSuspended(TemplateOverviewPanel, { props: props() })
    expect(wrapper.text()).toContain('paper')
    expect(wrapper.text()).toContain('main world')
    expect(wrapper.text()).toContain('abcdef0123456789') // first 16 chars of hash
  })

  it('shows the Danger Zone delete button for a non-base template', async () => {
    const wrapper = await mountSuspended(TemplateOverviewPanel, { props: props() })
    const del = wrapper.findAll('button').find(b => b.text().includes('Delete Template'))
    expect(del).toBeDefined()
    await del!.trigger('click')
    expect(wrapper.emitted('requestDelete')).toHaveLength(1)
  })

  it('shows the protected notice instead of Danger Zone for a base template', async () => {
    const wrapper = await mountSuspended(TemplateOverviewPanel, { props: props({ isBaseTemplate: true }) })
    expect(wrapper.text()).toContain('Protected Template')
    expect(wrapper.findAll('button').some(b => b.text().includes('Delete Template'))).toBe(false)
  })

  it('emits scanVariables and saveVariables from their buttons', async () => {
    const wrapper = await mountSuspended(TemplateOverviewPanel, { props: props() })
    await wrapper.findAll('button').find(b => b.text().includes('Scan Files'))!.trigger('click')
    await wrapper.findAll('button').find(b => b.text().trim() === 'Save')!.trigger('click')
    expect(wrapper.emitted('scanVariables')).toHaveLength(1)
    expect(wrapper.emitted('saveVariables')).toHaveLength(1)
  })

  it('reflects the saving / scanning labels and disabled states', async () => {
    const wrapper = await mountSuspended(TemplateOverviewPanel, {
      props: props({ variablesSaving: true, scanningVariables: true }),
    })
    const scan = wrapper.findAll('button').find(b => b.text().includes('Scanning...'))!
    const save = wrapper.findAll('button').find(b => b.text().includes('Saving...'))!
    expect(scan.attributes('disabled')).toBeDefined()
    expect(save.attributes('disabled')).toBeDefined()
  })

  it('renders the used-by-groups states', async () => {
    const loading = await mountSuspended(TemplateOverviewPanel, { props: props({ groupsLoading: true }) })
    expect(loading.text()).toContain('Loading...')
    const empty = await mountSuspended(TemplateOverviewPanel, { props: props() })
    expect(empty.text()).toContain('Not used by any groups')
    const used = await mountSuspended(TemplateOverviewPanel, {
      props: props({ usedByGroups: [{ name: 'lobby' }, { name: 'survival-1' }] }),
    })
    expect(used.text()).toContain('lobby')
    expect(used.text()).toContain('survival-1')
  })

  it('shows the inheritance chain section only when the chain is non-empty', async () => {
    const without = await mountSuspended(TemplateOverviewPanel, { props: props() })
    expect(without.text()).not.toContain('Template Inheritance')
    const withChain = await mountSuspended(TemplateOverviewPanel, {
      props: props({ inheritanceChain: [{ name: 'base', active: false, link: '/templates/base' }] }),
    })
    expect(withChain.text()).toContain('Template Inheritance')
  })

  it('opens an inline editor for platform and persists a changed value via the store', async () => {
    const wrapper = await mountSuspended(TemplateOverviewPanel, { props: props() })
    // Click the platform card (first card in the grid) to start editing.
    const platformCard = wrapper.findAll('.cursor-pointer')[0]!
    await platformCard.trigger('click')
    const input = wrapper.find('#inline-edit-platform')
    expect(input.exists()).toBe(true)
    await input.setValue('velocity')
    await input.trigger('keydown.enter')
    await new Promise(r => setTimeout(r))
    expect(updateTemplate).toHaveBeenCalledWith('survival', { platform: 'velocity' })
    expect(wrapper.emitted('update:template')).toHaveLength(1)
  })

  it('does not call the store when the inline edit is unchanged', async () => {
    const wrapper = await mountSuspended(TemplateOverviewPanel, { props: props() })
    await wrapper.findAll('.cursor-pointer')[0]!.trigger('click')
    const input = wrapper.find('#inline-edit-platform')
    await input.trigger('keydown.enter') // value still 'paper'
    expect(updateTemplate).not.toHaveBeenCalled()
  })

  it('toasts an error when the inline edit save rejects', async () => {
    updateTemplate.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountSuspended(TemplateOverviewPanel, { props: props() })
    await wrapper.findAll('.cursor-pointer')[0]!.trigger('click')
    const input = wrapper.find('#inline-edit-platform')
    await input.setValue('velocity')
    await input.trigger('keydown.enter')
    await new Promise(r => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Failed to update platform')
  })
})
