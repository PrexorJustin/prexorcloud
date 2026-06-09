import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TemplatesIndex from '../templates/index.vue'

const {
  fetchTemplates, connectSse, disconnectSse, deleteTemplate,
  templatesStore, navigateToMock,
} = vi.hoisted(() => ({
  fetchTemplates: vi.fn(),
  connectSse: vi.fn(),
  disconnectSse: vi.fn(),
  deleteTemplate: vi.fn(),
  templatesStore: { templates: [] as Record<string, unknown>[], loading: false },
  navigateToMock: vi.fn(),
}))

vi.mock('~/stores/templates', () => ({
  useTemplatesStore: () => reactive(Object.assign(templatesStore, {
    fetchTemplates, connectSse, disconnectSse, deleteTemplate,
  })),
}))
// Child dialogs reach for other stores / runtime config — stub to keep the
// page test focused on the list/delete behaviour.
vi.mock('~/components/templates/CreateTemplateDialog.vue', () => ({
  default: { name: 'CreateTemplateDialog', template: '<div />' },
}))
vi.mock('~/components/templates/ImportTemplateDialog.vue', () => ({
  default: { name: 'ImportTemplateDialog', template: '<div />' },
}))
vi.stubGlobal('navigateTo', navigateToMock)

function template(overrides: Record<string, unknown> = {}) {
  return {
    name: 'base-paper', platform: 'paper', description: 'Paper base template',
    hash: 'abcdef1234567890', sizeBytes: 2048,
    ...overrides,
  }
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  fetchTemplates.mockReset()
  connectSse.mockReset()
  disconnectSse.mockReset()
  deleteTemplate.mockReset().mockResolvedValue(undefined)
  navigateToMock.mockReset()
  templatesStore.templates = []
  templatesStore.loading = false
})

describe('TemplatesIndex', () => {
  it('renders the page header', async () => {
    const wrapper = await mountSuspended(TemplatesIndex)
    expect(wrapper.text()).toContain('Templates')
    expect(wrapper.text()).toContain('Versioned filesystem and JVM args')
  })

  it('fetches templates and connects SSE on mount', async () => {
    await mountSuspended(TemplatesIndex)
    expect(fetchTemplates).toHaveBeenCalled()
    expect(connectSse).toHaveBeenCalled()
  })

  it('shows the empty state when there are no templates', async () => {
    const wrapper = await mountSuspended(TemplatesIndex)
    expect(wrapper.text()).toContain('No templates found')
    expect(wrapper.text()).toContain('Create your first template')
  })

  it('renders template cards when the store has templates', async () => {
    templatesStore.templates = [template(), template({ name: 'base-velocity', platform: 'velocity' })]
    const wrapper = await mountSuspended(TemplatesIndex)
    expect(wrapper.text()).toContain('base-paper')
    expect(wrapper.text()).toContain('base-velocity')
    expect(wrapper.text()).not.toContain('No templates found')
  })

  it('filters templates by the search term', async () => {
    templatesStore.templates = [
      template({ name: 'base-paper', platform: 'paper' }),
      template({ name: 'base-velocity', platform: 'velocity' }),
    ]
    const wrapper = await mountSuspended(TemplatesIndex)
    await wrapper.find('input').setValue('velocity')
    await flush()
    expect(wrapper.text()).toContain('base-velocity')
    expect(wrapper.text()).not.toContain('base-paper')
  })

  it('opens the confirm dialog and deletes a template on confirm', async () => {
    templatesStore.templates = [template()]
    const wrapper = await mountSuspended(TemplatesIndex)
    const delBtn = wrapper.find('button[title="Delete template"]')
    await delBtn.trigger('click')
    await flush()
    const confirm = wrapper.findComponent({ name: 'ConfirmDialog' })
    expect(confirm.props('open')).toBe(true)
    confirm.vm.$emit('confirm')
    await flush()
    expect(deleteTemplate).toHaveBeenCalledWith('base-paper')
  })

  it('shows the loading skeleton while the store is loading', async () => {
    templatesStore.loading = true
    const wrapper = await mountSuspended(TemplatesIndex)
    expect(wrapper.text()).not.toContain('No templates found')
  })
})
