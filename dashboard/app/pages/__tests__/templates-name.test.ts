import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TemplateDetail from '../templates/[name].vue'
import type { Template } from '~/types/api'

const {
  routeParams, routerPush, apiGet, apiPost, apiDelete,
  templateStore, groupsState, fetchGroups, toastError, toastSuccess,
} = vi.hoisted(() => ({
  routeParams: { value: { name: 'survival' } as Record<string, string> },
  routerPush: vi.fn(),
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
  templateStore: {
    exportUrl: vi.fn(() => 'http://api.test/export'),
    fetchVersions: vi.fn(),
    rollbackToVersion: vi.fn(),
    deleteTemplate: vi.fn(),
    fetchInheritance: vi.fn(),
    fetchVariables: vi.fn(),
    fetchFileContent: vi.fn(),
    downloadFileUrl: vi.fn(() => 'http://api.test/dl'),
    extractZip: vi.fn(),
    renameFile: vi.fn(),
    uploadFile: vi.fn(),
    saveVariables: vi.fn(),
    scanVariables: vi.fn(),
    searchFiles: vi.fn(),
  },
  groupsState: { groups: [] as Record<string, unknown>[], loading: false },
  fetchGroups: vi.fn(),
  toastError: vi.fn(),
  toastSuccess: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => ({ params: routeParams.value }))
mockNuxtImport('useRouter', () => () => ({
  push: routerPush,
  replace: vi.fn().mockResolvedValue(undefined),
  beforeResolve: vi.fn(),
  beforeEach: vi.fn(),
  afterEach: vi.fn(),
  currentRoute: { value: { params: routeParams.value } },
}))

vi.mock('vue-sonner', () => ({
  toast: { error: toastError, success: toastSuccess, info: vi.fn(), warning: vi.fn() },
}))
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: apiGet, POST: apiPost, DELETE: apiDelete }),
}))
vi.mock('~/stores/templates', () => ({ useTemplatesStore: () => templateStore }))
vi.mock('~/stores/groups', () => ({
  useGroupsStore: () => ({
    get groups() { return groupsState.groups },
    get loading() { return groupsState.loading },
    fetchGroups,
  }),
}))

// Heavy child components — stub their surface so the page test stays focused
// on the page's own data-loading, tab and dialog wiring.
vi.mock('~/components/templates/TemplatePageHeader.vue', () => ({
  default: { name: 'TemplatePageHeader', props: ['template', 'templateName'], emits: ['back', 'export', 'discard', 'save'], template: '<div class="stub-TemplatePageHeader" />' },
}))
vi.mock('~/components/templates/TemplateOverviewPanel.vue', () => ({
  default: { name: 'TemplateOverviewPanel', props: ['template', 'templateName'], emits: ['request-delete', 'update:template'], template: '<div class="stub-TemplateOverviewPanel" />' },
}))
vi.mock('~/components/templates/TemplateVersionHistory.vue', () => ({
  default: { name: 'TemplateVersionHistory', props: ['template', 'templateName', 'versions'], emits: ['rollback', 'deleted'], methods: { reset() {} }, template: '<div class="stub-TemplateVersionHistory" />' },
}))
vi.mock('~/components/templates/TemplateFilesTab.vue', () => ({
  default: { name: 'TemplateFilesTab', props: ['editor', 'meta'], emits: ['upload', 'search-select', 'save'], template: '<div class="stub-TemplateFilesTab" />' },
}))
vi.mock('~/components/templates/UploadFilesDialog.vue', () => ({
  default: { name: 'UploadFilesDialog', props: ['open', 'templateName'], emits: ['update:open', 'uploaded'], template: '<div class="stub-UploadFilesDialog" />' },
}))

function template(overrides: Partial<Template> = {}): Template {
  return {
    name: 'survival', platform: 'paper', description: 'main world',
    sizeBytes: 4096, hash: 'abc123',
    ...overrides,
  } as Template
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  routeParams.value = { name: 'survival' }
  routerPush.mockReset()
  apiGet.mockReset().mockImplementation((path: string) => {
    // useTemplateEditor.loadRoot() lists files — must resolve to an array.
    if (path.includes('/files')) return Promise.resolve({ data: [] })
    return Promise.resolve({ data: template() })
  })
  apiPost.mockReset().mockResolvedValue({})
  apiDelete.mockReset().mockResolvedValue({})
  templateStore.exportUrl.mockReset().mockReturnValue('http://api.test/export')
  templateStore.fetchVersions.mockReset().mockResolvedValue([])
  templateStore.rollbackToVersion.mockReset().mockResolvedValue(undefined)
  templateStore.deleteTemplate.mockReset().mockResolvedValue(undefined)
  templateStore.fetchInheritance.mockReset().mockResolvedValue([])
  templateStore.fetchVariables.mockReset().mockResolvedValue([])
  groupsState.groups = []
  groupsState.loading = false
  fetchGroups.mockReset()
  toastError.mockReset()
  toastSuccess.mockReset()
})

describe('templates/[name] page', () => {
  it('loads the template on mount and renders the tab nav', async () => {
    const wrapper = await mountSuspended(TemplateDetail)
    await flush()
    expect(apiGet).toHaveBeenCalledWith('/api/v1/templates/{name}', {
      params: { path: { name: 'survival' } },
    })
    expect(wrapper.text()).toContain('Overview')
    expect(wrapper.text()).toContain('Files')
    expect(wrapper.text()).toContain('Versions')
  })

  it('shows the loading skeleton before the template resolves', async () => {
    let resolve!: (v: unknown) => void
    apiGet.mockReturnValueOnce(new Promise((r) => { resolve = r }))
    const wrapper = await mountSuspended(TemplateDetail)
    expect(wrapper.find('.animate-pulse').exists()).toBe(true)
    resolve({ data: template() })
    await flush()
  })

  it('renders the overview panel by default and switches to the versions tab', async () => {
    const wrapper = await mountSuspended(TemplateDetail)
    await flush()
    expect(wrapper.find('.stub-TemplateOverviewPanel').exists()).toBe(true)
    const versionsTab = wrapper.findAll('button').find(b => b.text().includes('Versions'))
    await versionsTab!.trigger('click')
    await flush()
    expect(templateStore.fetchVersions).toHaveBeenCalledWith('survival')
    expect(wrapper.find('.stub-TemplateVersionHistory').exists()).toBe(true)
  })

  it('switches to the files tab on click', async () => {
    const wrapper = await mountSuspended(TemplateDetail)
    await flush()
    const filesTab = wrapper.findAll('button').find(b => b.text().includes('Files'))
    await filesTab!.trigger('click')
    await flush()
    expect(wrapper.find('.stub-TemplateFilesTab').exists()).toBe(true)
  })

  it('redirects to /templates and toasts when the template cannot be loaded', async () => {
    apiGet.mockRejectedValueOnce(new Error('boom'))
    await mountSuspended(TemplateDetail)
    await flush()
    expect(toastError).toHaveBeenCalledWith(
      "Can't load template",
      expect.objectContaining({ description: expect.stringContaining('survival') }),
    )
    expect(routerPush).toHaveBeenCalledWith('/templates')
  })

  it('toasts an error when version history fails to load', async () => {
    templateStore.fetchVersions.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountSuspended(TemplateDetail)
    await flush()
    await wrapper.findAll('button').find(b => b.text().includes('Versions'))!.trigger('click')
    await flush()
    expect(toastError).toHaveBeenCalledWith(
      "Can't load version history",
      expect.anything(),
    )
  })

  it('exports the template through a new window when the header asks', async () => {
    const openSpy = vi.fn()
    vi.stubGlobal('open', openSpy)
    const wrapper = await mountSuspended(TemplateDetail)
    await flush()
    wrapper.findComponent({ name: 'TemplatePageHeader' }).vm.$emit('export')
    await flush()
    expect(templateStore.exportUrl).toHaveBeenCalledWith('survival')
    expect(openSpy).toHaveBeenCalledWith('http://api.test/export', '_blank')
    vi.unstubAllGlobals()
  })

  it('deletes the template and navigates away when the overview requests it', async () => {
    const wrapper = await mountSuspended(TemplateDetail)
    await flush()
    wrapper.findComponent({ name: 'TemplateOverviewPanel' }).vm.$emit('request-delete')
    await flush()
    // ConfirmDialog is rendered; drive the page handler directly
    await (wrapper.vm as unknown as { deleteTemplate: () => Promise<void> }).deleteTemplate()
    await flush()
    expect(templateStore.deleteTemplate).toHaveBeenCalledWith('survival')
    expect(routerPush).toHaveBeenCalledWith('/templates')
  })
})
