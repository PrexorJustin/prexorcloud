import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive, nextTick } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import AddVersionDialog from '../catalog/AddVersionDialog.vue'

const { addVersion, toastError, catalogStore } = vi.hoisted(() => ({
  addVersion: vi.fn(),
  toastError: vi.fn(),
  catalogStore: { entries: [] as { platform: string }[] },
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/catalog', () => ({
  useCatalogStore: () => reactive(Object.assign(catalogStore, { addVersion })),
}))

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
  document.body.innerHTML = ''
})

beforeEach(() => {
  addVersion.mockReset().mockResolvedValue(undefined)
  toastError.mockReset()
  catalogStore.entries = [{ platform: 'paper' }]
})

function bodyInput(id: string) {
  return document.body.querySelector(`#${id}`) as HTMLInputElement
}
async function setInput(id: string, value: string) {
  const el = bodyInput(id)
  el.value = value
  el.dispatchEvent(new Event('input'))
  await nextTick()
}
function findButton(text: string) {
  return Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.trim() === text)
}

async function mountOpen(props: Record<string, unknown> = {}) {
  const wrapper = await mountSuspended(AddVersionDialog, { props })
  active = wrapper
  await wrapper.find('button').trigger('click')
  await nextTick()
  return wrapper
}

describe('AddVersionDialog', () => {
  it('renders the Add Platform trigger in the no-platform flow', async () => {
    const wrapper = await mountSuspended(AddVersionDialog)
    active = wrapper
    expect(wrapper.text()).toContain('Add Platform')
  })

  it('opening shows the step-1 platform pane', async () => {
    await mountOpen()
    expect(document.body.textContent).toContain('New platform')
    expect(document.body.querySelector('#platform-name')).not.toBeNull()
  })

  it('keeps Continue disabled until a platform name is entered', async () => {
    await mountOpen()
    expect(findButton('Continue')!.getAttribute('disabled')).not.toBeNull()
    await setInput('platform-name', 'purpur')
    expect(findButton('Continue')!.getAttribute('disabled')).toBeNull()
  })

  it('Continue advances to the version step and Back returns', async () => {
    await mountOpen()
    await setInput('platform-name', 'purpur')
    findButton('Continue')!.click()
    await nextTick()
    expect(document.body.querySelector('#version')).not.toBeNull()
    findButton('Back')!.click()
    await nextTick()
    expect(document.body.querySelector('#platform-name')).not.toBeNull()
  })

  it('submits a new-platform version with category and configFormat', async () => {
    await mountOpen()
    await setInput('platform-name', 'purpur')
    // pick PROXY category then a config format
    ;(Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.includes('Proxy')) as HTMLElement).click()
    await nextTick()
    ;(Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'velocity') as HTMLElement).click()
    await nextTick()
    findButton('Continue')!.click()
    await nextTick()
    await setInput('version', '1.21.4')
    await setInput('download-url', 'https://example.com/p.jar')
    findButton('Add Version')!.click()
    await new Promise((r) => setTimeout(r))
    expect(addVersion).toHaveBeenCalledWith('purpur', {
      version: '1.21.4',
      downloadUrl: 'https://example.com/p.jar',
      category: 'PROXY',
      configFormat: 'velocity',
    })
  })

  it('with a platform prop skips step 1 and omits the new-platform fields', async () => {
    await mountOpen({ platform: 'paper' })
    expect(document.body.textContent).toContain('Add version to paper')
    await setInput('version', '1.21.4')
    await setInput('download-url', 'https://example.com/p.jar')
    findButton('Add Version')!.click()
    await new Promise((r) => setTimeout(r))
    expect(addVersion).toHaveBeenCalledWith('paper', {
      version: '1.21.4',
      downloadUrl: 'https://example.com/p.jar',
    })
  })

  it('toasts an error when adding a version fails', async () => {
    addVersion.mockRejectedValue(new Error('boom'))
    await mountOpen({ platform: 'paper' })
    await setInput('version', '1.21.4')
    await setInput('download-url', 'https://example.com/p.jar')
    findButton('Add Version')!.click()
    await new Promise((r) => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Failed to add version', expect.anything())
  })
})
