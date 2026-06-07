import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive, nextTick } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import CreateTemplateDialog from '../templates/CreateTemplateDialog.vue'

const { createTemplate, fetchCatalog, toastError, templatesStore, catalogStore } = vi.hoisted(() => ({
  createTemplate: vi.fn(),
  fetchCatalog: vi.fn(),
  toastError: vi.fn(),
  templatesStore: { templates: [] as { name: string }[] },
  catalogStore: { entries: [] as { platform: string; category: string; versions: unknown[] }[] },
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/templates', () => ({
  useTemplatesStore: () => reactive(Object.assign(templatesStore, { createTemplate })),
}))
vi.mock('~/stores/catalog', () => ({
  useCatalogStore: () => reactive(Object.assign(catalogStore, { fetchCatalog })),
}))
vi.mock('~/components/catalog/AddVersionDialog.vue', () => ({
  default: { name: 'AddVersionDialog', template: '<div />' },
}))

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
  document.body.innerHTML = ''
})

beforeEach(() => {
  createTemplate.mockReset().mockResolvedValue(undefined)
  fetchCatalog.mockReset()
  toastError.mockReset()
  templatesStore.templates = []
  catalogStore.entries = [
    { platform: 'paper', category: 'SERVER', versions: ['1.21.1'] },
    { platform: 'velocity', category: 'PROXY', versions: [] },
  ]
})

async function mountOpen() {
  const wrapper = await mountSuspended(CreateTemplateDialog)
  active = wrapper
  await wrapper.find('button').trigger('click')
  await nextTick()
  return wrapper
}

function bodyButtons() {
  return Array.from(document.body.querySelectorAll('button'))
}
function findButton(text: string) {
  return bodyButtons().find((b) => b.textContent?.trim() === text)
}
async function gotoStep2() {
  ;(bodyButtons().find((b) => b.textContent?.trim().toLowerCase().startsWith('paper')) as HTMLElement).click()
  await nextTick()
  findButton('Continue')!.click()
  await nextTick()
}

describe('CreateTemplateDialog', () => {
  it('renders the New Template trigger', async () => {
    const wrapper = await mountSuspended(CreateTemplateDialog)
    active = wrapper
    expect(wrapper.text()).toContain('New Template')
  })

  it('opening the dialog fetches the catalog and shows step 1', async () => {
    await mountOpen()
    expect(fetchCatalog).toHaveBeenCalled()
    expect(document.body.textContent).toContain('Choose platform')
  })

  it('shows the empty-catalog state when there are no entries', async () => {
    catalogStore.entries = []
    await mountOpen()
    expect(document.body.textContent).toContain('No platforms in catalog yet')
  })

  it('keeps Continue disabled until a platform is selected', async () => {
    await mountOpen()
    expect(findButton('Continue')!.getAttribute('disabled')).not.toBeNull()
    ;(bodyButtons().find((b) => b.textContent?.trim().toLowerCase().startsWith('paper')) as HTMLElement).click()
    await nextTick()
    expect(findButton('Continue')!.getAttribute('disabled')).toBeNull()
  })

  it('Continue advances to step 2 with the selected platform pill', async () => {
    await mountOpen()
    await gotoStep2()
    expect(document.body.textContent).toContain('Template details')
    expect(document.body.querySelector('#template-name')).not.toBeNull()
  })

  it('shows a name validation error for an invalid name', async () => {
    await mountOpen()
    await gotoStep2()
    const name = document.body.querySelector('#template-name') as HTMLInputElement
    name.value = 'Bad Name'
    name.dispatchEvent(new Event('input'))
    await nextTick()
    expect(document.body.textContent).toContain('Lowercase letters, numbers, underscore, hyphen only')
  })

  it('submits trimmed values to createTemplate and closes on success', async () => {
    await mountOpen()
    await gotoStep2()
    const name = document.body.querySelector('#template-name') as HTMLInputElement
    name.value = 'lobby'
    name.dispatchEvent(new Event('input'))
    await nextTick()
    findButton('Create Template')!.click()
    await new Promise((r) => setTimeout(r))
    expect(createTemplate).toHaveBeenCalledWith({ name: 'lobby', description: '', platform: 'paper' })
  })

  it('toasts an error when template creation fails', async () => {
    createTemplate.mockRejectedValue(new Error('boom'))
    await mountOpen()
    await gotoStep2()
    const name = document.body.querySelector('#template-name') as HTMLInputElement
    name.value = 'lobby'
    name.dispatchEvent(new Event('input'))
    await nextTick()
    findButton('Create Template')!.click()
    await new Promise((r) => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Failed to create template', expect.anything())
  })

  it('Back returns to step 1', async () => {
    await mountOpen()
    await gotoStep2()
    findButton('Back')!.click()
    await nextTick()
    expect(document.body.textContent).toContain('Choose platform')
  })
})
