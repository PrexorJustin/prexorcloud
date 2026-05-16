import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import VersionHistoryDialog from '../templates/VersionHistoryDialog.vue'

const { fetchVersions, rollbackToVersion, toastError } = vi.hoisted(() => ({
  fetchVersions: vi.fn(),
  rollbackToVersion: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/templates', () => ({
  useTemplatesStore: () => ({ fetchVersions, rollbackToVersion }),
}))

const template = { name: 'survival', hash: 'hash-current' }
const versions = [
  { hash: 'hash-latest', sizeBytes: 2048, createdAt: '2026-05-14T00:00:00Z' },
  { hash: 'hash-current', sizeBytes: 1024, createdAt: '2026-05-13T00:00:00Z' },
]

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
  document.body.innerHTML = ''
})

beforeEach(() => {
  fetchVersions.mockReset().mockResolvedValue(versions)
  rollbackToVersion.mockReset().mockResolvedValue(undefined)
  toastError.mockReset()
})

async function mountOpen() {
  const wrapper = await mountSuspended(VersionHistoryDialog, {
    props: { open: false, template },
  })
  active = wrapper
  await wrapper.setProps({ open: true })
  await nextTick()
  await nextTick()
  return wrapper
}

describe('VersionHistoryDialog', () => {
  it('fetches versions on open and renders one row each', async () => {
    await mountOpen()
    expect(fetchVersions).toHaveBeenCalledWith('survival')
    expect(document.body.textContent).toContain('hash-latest'.slice(0, 12))
    expect(document.body.textContent).toContain('current')
    expect(document.body.textContent).toContain('latest')
  })

  it('shows the empty state when there are no versions', async () => {
    fetchVersions.mockResolvedValue([])
    await mountOpen()
    expect(document.body.textContent).toContain('No version history yet')
  })

  it('toasts an error when version loading fails', async () => {
    fetchVersions.mockRejectedValue(new Error('boom'))
    await mountOpen()
    expect(toastError).toHaveBeenCalledWith("Can't load version history", expect.anything())
  })

  it('Restore reveals a confirm step that rolls back, emits restored and closes', async () => {
    const wrapper = await mountOpen()
    const restore = Array.from(document.body.querySelectorAll('button'))
      .find((b) => b.textContent?.includes('Restore'))!
    restore.click()
    await nextTick()
    const confirm = Array.from(document.body.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Confirm')!
    confirm.click()
    await new Promise((r) => setTimeout(r))
    expect(rollbackToVersion).toHaveBeenCalledWith('survival', 'hash-latest')
    expect(wrapper.emitted('restored')).toHaveLength(1)
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
  })

  it('toasts an error when the rollback fails', async () => {
    rollbackToVersion.mockRejectedValue(new Error('boom'))
    await mountOpen()
    const restore = Array.from(document.body.querySelectorAll('button'))
      .find((b) => b.textContent?.includes('Restore'))!
    restore.click()
    await nextTick()
    const confirm = Array.from(document.body.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Confirm')!
    confirm.click()
    await new Promise((r) => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Restore failed', expect.anything())
  })

  it('Close emits update:open false', async () => {
    const wrapper = await mountOpen()
    const close = Array.from(document.body.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Close')!
    close.click()
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false])
  })
})
