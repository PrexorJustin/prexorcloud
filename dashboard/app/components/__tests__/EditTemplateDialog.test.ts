import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import EditTemplateDialog from '../templates/EditTemplateDialog.vue'

const { updateTemplate, toastError } = vi.hoisted(() => ({
  updateTemplate: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/templates', () => ({ useTemplatesStore: () => ({ updateTemplate }) }))

const template = { name: 'survival', platform: 'paper', description: 'main world' }

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
  document.body.innerHTML = ''
})

beforeEach(() => {
  updateTemplate.mockReset().mockResolvedValue({ ...template, platform: 'folia' })
  toastError.mockReset()
})

async function mountOpen() {
  const wrapper = await mountSuspended(EditTemplateDialog, {
    props: { open: false, template },
  })
  active = wrapper
  await wrapper.setProps({ open: true })
  await nextTick()
  return wrapper
}

function bodyInput(id: string) {
  return document.body.querySelector(`#${id}`) as HTMLInputElement
}
function saveButton() {
  return Array.from(document.body.querySelectorAll('button'))
    .find((b) => b.textContent?.includes('Save Changes'))!
}

describe('EditTemplateDialog', () => {
  it('renders the title and seeds the fields from the template on open', async () => {
    await mountOpen()
    expect(document.body.textContent).toContain('Edit survival')
    expect(bodyInput('edit-platform').value).toBe('paper')
    expect(bodyInput('edit-description').value).toBe('main world')
  })

  it('disables Save while the form is unchanged', async () => {
    await mountOpen()
    expect(saveButton().getAttribute('disabled')).not.toBeNull()
  })

  it('submits trimmed changes, emits updated and closes', async () => {
    const wrapper = await mountOpen()
    const platform = bodyInput('edit-platform')
    platform.value = 'folia'
    platform.dispatchEvent(new Event('input'))
    await nextTick()
    saveButton().click()
    await new Promise((r) => setTimeout(r))
    expect(updateTemplate).toHaveBeenCalledWith('survival', { description: 'main world', platform: 'folia' })
    expect(wrapper.emitted('updated')?.[0]).toEqual([{ ...template, platform: 'folia' }])
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
  })

  it('toasts an error when the update fails', async () => {
    updateTemplate.mockRejectedValue(new Error('boom'))
    await mountOpen()
    const platform = bodyInput('edit-platform')
    platform.value = 'folia'
    platform.dispatchEvent(new Event('input'))
    await nextTick()
    saveButton().click()
    await new Promise((r) => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Update failed', expect.anything())
  })

  it('Cancel emits update:open false', async () => {
    const wrapper = await mountOpen()
    const cancel = Array.from(document.body.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Cancel')!
    cancel.click()
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
  })
})
