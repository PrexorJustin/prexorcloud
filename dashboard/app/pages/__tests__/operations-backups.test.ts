import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import type { BackupRecord } from '~/stores/backups'
import Backups from '../operations/backups.vue'

const { backupsStore, toastSuccess, toastError } = vi.hoisted(() => ({
  backupsStore: {
    backups: [] as BackupRecord[],
    loading: false,
    fetchBackups: vi.fn(),
    createBackup: vi.fn(),
    verifyBackup: vi.fn(),
    deleteBackup: vi.fn(),
    pruneBackups: vi.fn(),
    restoreBackup: vi.fn(),
  },
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

mockNuxtImport('useBackupsStore', () => () => reactive(backupsStore))
vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))

function backup(overrides: Partial<BackupRecord> = {}): BackupRecord {
  return {
    id: 'bk-001',
    createdAt: new Date().toISOString(),
    sizeBytes: 1024 * 1024,
    verifyStatus: 'OK',
    verifiedAt: new Date().toISOString(),
    notes: 'nightly snapshot',
    ...overrides,
  }
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

function bodyButton(text: string) {
  return Array.from(document.body.querySelectorAll('button')).find(b => (b.textContent ?? '').includes(text))
}

beforeEach(() => {
  document.body.innerHTML = ''
  backupsStore.backups = []
  backupsStore.loading = false
  backupsStore.fetchBackups.mockReset().mockResolvedValue(undefined)
  backupsStore.createBackup.mockReset().mockResolvedValue(backup())
  backupsStore.verifyBackup.mockReset().mockResolvedValue(undefined)
  backupsStore.deleteBackup.mockReset().mockResolvedValue(undefined)
  backupsStore.pruneBackups.mockReset().mockResolvedValue(undefined)
  backupsStore.restoreBackup.mockReset().mockResolvedValue(undefined)
  toastSuccess.mockReset()
  toastError.mockReset()
})

describe('operations/backups', () => {
  it('renders the header and fetches backups on mount', async () => {
    const wrapper = await mountSuspended(Backups)
    expect(wrapper.text()).toContain('Backups')
    expect(wrapper.text()).toContain('Cluster-state snapshots')
    expect(backupsStore.fetchBackups).toHaveBeenCalledTimes(1)
  })

  it('shows the empty state when there are no backups', async () => {
    const wrapper = await mountSuspended(Backups)
    expect(wrapper.text()).toContain('No backups yet')
  })

  it('renders a row per backup with its id', async () => {
    backupsStore.backups = [backup({ id: 'bk-alpha' }), backup({ id: 'bk-beta' })]
    const wrapper = await mountSuspended(Backups)
    expect(wrapper.text()).toContain('bk-alpha')
    expect(wrapper.text()).toContain('bk-beta')
  })

  it('filters the rows by the search field', async () => {
    backupsStore.backups = [backup({ id: 'bk-alpha' }), backup({ id: 'bk-beta' })]
    const wrapper = await mountSuspended(Backups)
    await wrapper.find('input').setValue('alpha')
    await flush()
    expect(wrapper.text()).toContain('bk-alpha')
    expect(wrapper.text()).not.toContain('bk-beta')
  })

  it('creates a backup through the create dialog', async () => {
    const wrapper = await mountSuspended(Backups)
    const createBtn = wrapper.findAll('button').find(b => b.text().includes('Create backup'))!
    await createBtn.trigger('click')
    await flush()
    const notes = document.body.querySelector('#bk-notes') as HTMLTextAreaElement
    notes.value = 'pre-migration'
    notes.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    const form = document.body.querySelector('form') as HTMLFormElement
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flush()
    expect(backupsStore.createBackup).toHaveBeenCalledWith('pre-migration')
  })

  it('deletes a backup from its row action', async () => {
    backupsStore.backups = [backup({ id: 'bk-doomed' })]
    const wrapper = await mountSuspended(Backups)
    const delBtn = wrapper.find('button[aria-label="Delete"]')
    await delBtn.trigger('click')
    await flush()
    expect(backupsStore.deleteBackup).toHaveBeenCalledWith('bk-doomed')
  })

  it('opens the restore confirmation dialog and calls restoreBackup', async () => {
    backupsStore.backups = [backup({ id: 'bk-restore' })]
    const wrapper = await mountSuspended(Backups)
    const restoreBtn = wrapper.find('button[title="Restore from this backup"]')
    await restoreBtn.trigger('click')
    await flush()
    expect(document.body.textContent).toContain('Restore from backup?')
    const confirmBtn = Array.from(document.body.querySelectorAll('button')).find(b => (b.textContent ?? '').trim() === 'Restore')!
    confirmBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flush()
    expect(backupsStore.restoreBackup).toHaveBeenCalledWith('bk-restore')
  })

  it('prunes backups through the prune dialog', async () => {
    const wrapper = await mountSuspended(Backups)
    const pruneBtn = wrapper.findAll('button').find(b => b.text().includes('Prune'))!
    await pruneBtn.trigger('click')
    await flush()
    const keepForm = Array.from(document.body.querySelectorAll('form')).find(f => f.querySelector('#bk-keep'))!
    keepForm.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flush()
    expect(backupsStore.pruneBackups).toHaveBeenCalledWith(7)
  })
})
