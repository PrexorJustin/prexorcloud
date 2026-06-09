import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TemplateVersionHistory from '../templates/TemplateVersionHistory.vue'
import type { Template, TemplateVersion } from '~/types/api'

const { fetchSnapshotFiles, deleteVersion, toastError } = vi.hoisted(() => ({
  fetchSnapshotFiles: vi.fn(),
  deleteVersion: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/templates', () => ({
  useTemplatesStore: () => ({
    fetchSnapshotFiles,
    deleteVersion,
    fetchSnapshotFileContent: vi.fn().mockResolvedValue(''),
    fetchFileContent: vi.fn().mockResolvedValue(''),
  }),
}))
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: vi.fn().mockResolvedValue({ data: [] }) }),
}))

function template(overrides: Partial<Template> = {}): Template {
  return { name: 'survival', platform: 'paper', hash: 'current-hash', sizeBytes: 1024, ...overrides } as Template
}

function version(hash: string, overrides: Partial<TemplateVersion> = {}): TemplateVersion {
  return {
    hash, sizeBytes: 2048, createdAt: '2026-05-14T00:00:00Z', ...overrides,
  } as TemplateVersion
}

function props(overrides: Record<string, unknown> = {}) {
  return {
    templateName: 'survival',
    template: template(),
    versions: [version('latest-hash'), version('current-hash'), version('old-hash')],
    versionsLoading: false,
    restoring: false,
    ...overrides,
  }
}

function findButton(wrapper: { findAll: (s: string) => unknown[] }, text: string) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return (wrapper.findAll('button') as any[]).find(b => b.text().includes(text))
}

beforeEach(() => {
  fetchSnapshotFiles.mockReset().mockResolvedValue([])
  deleteVersion.mockReset().mockResolvedValue(undefined)
  toastError.mockReset()
})

describe('TemplateVersionHistory', () => {
  it('shows a spinner while versions are loading', async () => {
    const wrapper = await mountSuspended(TemplateVersionHistory, { props: props({ versionsLoading: true }) })
    expect(wrapper.find('.animate-spin').exists()).toBe(true)
  })

  it('shows the empty state when there are no versions', async () => {
    const wrapper = await mountSuspended(TemplateVersionHistory, { props: props({ versions: [] }) })
    expect(wrapper.text()).toContain('No version history yet')
  })

  it('renders a row per version with a truncated hash', async () => {
    const wrapper = await mountSuspended(TemplateVersionHistory, { props: props() })
    expect(wrapper.text()).toContain('latest-hash'.slice(0, 12))
    expect(wrapper.text()).toContain('old-hash')
  })

  it('marks the current version and the latest version with badges', async () => {
    const wrapper = await mountSuspended(TemplateVersionHistory, { props: props() })
    expect(wrapper.text()).toContain('current')
    expect(wrapper.text()).toContain('latest')
  })

  it('loads snapshot files when View is clicked and toggles the label to Close', async () => {
    const wrapper = await mountSuspended(TemplateVersionHistory, { props: props() })
    await findButton(wrapper, 'View')!.trigger('click')
    expect(fetchSnapshotFiles).toHaveBeenCalledWith('survival', 'latest-hash', '')
    expect(wrapper.text()).toContain('Close')
  })

  it('reveals a confirm step before restoring and emits rollback on confirm', async () => {
    const wrapper = await mountSuspended(TemplateVersionHistory, { props: props() })
    await findButton(wrapper, 'Restore')!.trigger('click')
    const confirm = findButton(wrapper, 'Confirm')!
    expect(confirm).toBeDefined()
    await confirm.trigger('click')
    // first non-current version is 'latest-hash'
    expect(wrapper.emitted('rollback')?.[0]).toEqual(['latest-hash'])
  })

  it('deletes a version through the store and emits deleted on confirm', async () => {
    const wrapper = await mountSuspended(TemplateVersionHistory, { props: props() })
    // the trash button has no text — it's the icon-only ghost button in the row
    const trash = wrapper.findAll('button').find(b => b.text() === '')
    await trash!.trigger('click')
    await findButton(wrapper, 'Delete')!.trigger('click')
    await new Promise(r => setTimeout(r))
    expect(deleteVersion).toHaveBeenCalledWith('survival', 'latest-hash')
    expect(wrapper.emitted('deleted')?.[0]).toEqual(['latest-hash'])
  })

  it('toasts an error when deleting a version rejects', async () => {
    deleteVersion.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountSuspended(TemplateVersionHistory, { props: props() })
    const trash = wrapper.findAll('button').find(b => b.text() === '')
    await trash!.trigger('click')
    await findButton(wrapper, 'Delete')!.trigger('click')
    await new Promise(r => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Failed to delete version')
  })
})
