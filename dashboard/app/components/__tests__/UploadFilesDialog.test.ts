import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import UploadFilesDialog from '../templates/UploadFilesDialog.vue'

const { uploadFiles, toastError } = vi.hoisted(() => ({
  uploadFiles: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/templates', () => ({ useTemplatesStore: () => ({ uploadFiles }) }))

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
  document.body.innerHTML = ''
})

beforeEach(() => {
  uploadFiles.mockReset().mockResolvedValue(undefined)
  toastError.mockReset()
})

async function mountOpen(props: Record<string, unknown> = {}) {
  const wrapper = await mountSuspended(UploadFilesDialog, {
    props: { open: true, templateName: 'survival', ...props },
  })
  active = wrapper
  await nextTick()
  return wrapper
}

async function attachFiles(names = ['a.yml', 'b.txt']) {
  const fileInput = document.body.querySelector('input[type="file"]') as HTMLInputElement
  Object.defineProperty(fileInput, 'files', {
    value: names.map((n) => new File(['x'], n)),
    configurable: true,
  })
  fileInput.dispatchEvent(new Event('change'))
  await nextTick()
}

function uploadButton() {
  return Array.from(document.body.querySelectorAll('button'))
    .find((b) => b.textContent?.includes('Upload') && b.closest('[role="dialog"]'))!
}

describe('UploadFilesDialog', () => {
  it('shows the target path in the description (root when unset)', async () => {
    await mountOpen()
    expect(document.body.textContent).toContain('Upload to root in survival')
  })

  it('shows the target path when one is given', async () => {
    await mountOpen({ targetPath: 'plugins' })
    expect(document.body.textContent).toContain('Upload to /plugins in survival')
  })

  it('shows the empty drop-zone prompt before any files are picked', async () => {
    await mountOpen()
    expect(document.body.textContent).toContain('Drop files here or click to browse')
    expect(uploadButton().getAttribute('disabled')).not.toBeNull()
  })

  it('lists picked files and enables the upload button with a count', async () => {
    await mountOpen()
    await attachFiles()
    expect(document.body.textContent).toContain('a.yml')
    expect(document.body.textContent).toContain('b.txt')
    expect(uploadButton().textContent).toContain('Upload 2 files')
    expect(uploadButton().getAttribute('disabled')).toBeNull()
  })

  it('clears the selection on the X button', async () => {
    await mountOpen()
    await attachFiles(['a.yml'])
    const clear = Array.from(document.body.querySelectorAll('[role="dialog"] button'))
      .find((b) => b.classList.contains('size-6')) as HTMLElement
    clear.click()
    await nextTick()
    expect(document.body.textContent).toContain('Drop files here or click to browse')
  })

  it('uploads to the store, emits uploaded and closes on success', async () => {
    const wrapper = await mountOpen({ targetPath: 'plugins' })
    await attachFiles(['a.yml'])
    uploadButton().click()
    await new Promise((r) => setTimeout(r))
    expect(uploadFiles).toHaveBeenCalledWith('survival', 'plugins', expect.anything())
    expect(wrapper.emitted('uploaded')).toHaveLength(1)
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
  })

  it('toasts an error when the upload fails', async () => {
    uploadFiles.mockRejectedValue(new Error('boom'))
    await mountOpen()
    await attachFiles(['a.yml'])
    uploadButton().click()
    await new Promise((r) => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Upload failed', expect.anything())
  })

  it('Cancel emits update:open false', async () => {
    const wrapper = await mountOpen()
    const cancel = Array.from(document.body.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Cancel')!
    cancel.click()
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
  })
})
