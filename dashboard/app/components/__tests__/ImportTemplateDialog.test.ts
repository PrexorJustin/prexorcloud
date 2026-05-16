import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ImportTemplateDialog from '../templates/ImportTemplateDialog.vue'

const { importTemplate, toastError, templates } = vi.hoisted(() => ({
  importTemplate: vi.fn(),
  toastError: vi.fn(),
  templates: [] as { name: string }[],
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/templates', () => ({
  useTemplatesStore: () => ({ importTemplate, templates }),
}))

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
  document.body.innerHTML = ''
})

beforeEach(() => {
  importTemplate.mockReset().mockResolvedValue(undefined)
  toastError.mockReset()
  templates.length = 0
})

async function mountOpen() {
  const wrapper = await mountSuspended(ImportTemplateDialog)
  active = wrapper
  await wrapper.find('button').trigger('click')
  await nextTick()
  return wrapper
}

function bodyInput(id: string) {
  return document.body.querySelector(`#${id}`) as HTMLInputElement
}
function importButton() {
  return Array.from(document.body.querySelectorAll('button'))
    .find((b) => b.textContent?.trim() === 'Import' && b.closest('[role="dialog"]'))!
}
async function setName(value: string) {
  const input = bodyInput('import-template-name')
  input.value = value
  input.dispatchEvent(new Event('input'))
  await nextTick()
}
async function setPlatform(value: string) {
  const input = bodyInput('import-template-platform')
  input.value = value
  input.dispatchEvent(new Event('input'))
  await nextTick()
}
async function attachFile() {
  const fileInput = document.body.querySelector('input[type="file"]') as HTMLInputElement
  Object.defineProperty(fileInput, 'files', {
    value: [new File(['x'], 'tpl.tar.gz')],
    configurable: true,
  })
  fileInput.dispatchEvent(new Event('change'))
  await nextTick()
}

describe('ImportTemplateDialog', () => {
  it('renders the Import trigger', async () => {
    const wrapper = await mountSuspended(ImportTemplateDialog)
    active = wrapper
    expect(wrapper.text()).toContain('Import')
  })

  it('clicking the trigger opens the dialog', async () => {
    await mountOpen()
    expect(document.body.textContent).toContain('Import Template')
  })

  it('shows a validation error for an invalid name', async () => {
    await mountOpen()
    await setName('Bad Name!')
    expect(document.body.textContent).toContain('Lowercase letters, numbers, underscore, hyphen only')
  })

  it('flags a name that collides with an existing template', async () => {
    templates.push({ name: 'lobby' })
    await mountOpen()
    await setName('lobby')
    expect(document.body.textContent).toContain('Template already exists')
  })

  it('shows the picked file name and clears it on click', async () => {
    await mountOpen()
    await attachFile()
    expect(document.body.textContent).toContain('tpl.tar.gz')
    const clear = Array.from(document.body.querySelectorAll('button')).find(
      (b) => b.querySelector('svg') && b.classList.contains('size-7'),
    )!
    clear.click()
    await nextTick()
    expect(document.body.textContent).not.toContain('tpl.tar.gz')
  })

  it('keeps Import disabled until name, platform and file are all set', async () => {
    await mountOpen()
    expect(importButton().getAttribute('disabled')).not.toBeNull()
    await setName('lobby')
    await setPlatform('paper')
    // still no file → disabled
    expect(importButton().getAttribute('disabled')).not.toBeNull()
    await attachFile()
    expect(importButton().getAttribute('disabled')).toBeNull()
  })

  it('submits a FormData payload and closes on success', async () => {
    await mountOpen()
    await setName('lobby')
    await setPlatform('paper')
    await attachFile()
    importButton().click()
    await new Promise((r) => setTimeout(r))
    expect(importTemplate).toHaveBeenCalledTimes(1)
    const fd = importTemplate.mock.calls[0]![0] as FormData
    expect(fd.get('name')).toBe('lobby')
    expect(fd.get('platform')).toBe('paper')
    expect(fd.get('file')).toBeInstanceOf(File)
  })

  it('toasts an error when the import fails', async () => {
    importTemplate.mockRejectedValue(new Error('boom'))
    await mountOpen()
    await setName('lobby')
    await setPlatform('paper')
    await attachFile()
    importButton().click()
    await new Promise((r) => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Failed to import template', expect.anything())
  })
})
