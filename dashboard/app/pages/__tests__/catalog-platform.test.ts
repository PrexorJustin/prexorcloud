import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CatalogPlatform from '../catalog/[platform].vue'

const {
  fetchCatalog, deleteVersion, markRecommended, fetchGroups,
  catalogStore, groupsStore, routerPush, toastError,
} = vi.hoisted(() => ({
  fetchCatalog: vi.fn(),
  deleteVersion: vi.fn(),
  markRecommended: vi.fn(),
  fetchGroups: vi.fn(),
  catalogStore: { entries: [] as Record<string, unknown>[] },
  groupsStore: { groups: [] as Record<string, unknown>[] },
  routerPush: vi.fn(),
  toastError: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => ({ params: { platform: 'paper' } }))
// Nuxt's own client plugins call router lifecycle hooks (afterEach, onError,
// beforeResolve, …) — the mock exposes them as no-ops alongside the push/
// replace spies the page under test exercises.
mockNuxtImport('useRouter', () => () => ({
  push: routerPush,
  replace: routerPush,
  afterEach: () => () => {},
  beforeEach: () => () => {},
  beforeResolve: () => () => {},
  onError: () => () => {},
  isReady: () => Promise.resolve(),
  resolve: (to: unknown) => ({ href: typeof to === 'string' ? to : '/' }),
  getRoutes: () => [],
  hasRoute: () => false,
  currentRoute: { value: { params: { platform: 'paper' }, fullPath: '/catalog/paper', path: '/catalog/paper', query: {}, hash: '', name: undefined, matched: [], meta: {} } },
  options: { routes: [] },
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/catalog', () => ({
  useCatalogStore: () => reactive(Object.assign(catalogStore, { fetchCatalog, deleteVersion, markRecommended })),
}))
vi.mock('~/stores/groups', () => ({
  useGroupsStore: () => reactive(Object.assign(groupsStore, { fetchGroups })),
}))
vi.mock('~/components/catalog/AddVersionDialog.vue', () => ({
  default: { name: 'AddVersionDialog', template: '<div />' },
}))

function paperEntry() {
  return {
    platform: 'paper', category: 'SERVER', configFormat: 'YAML',
    versions: [
      { version: '1.21.1', downloadUrl: 'https://dl/1.21.1', recommended: true },
      { version: '1.20.4', downloadUrl: 'https://dl/1.20.4', recommended: false },
    ],
  }
}

beforeEach(() => {
  fetchCatalog.mockReset().mockResolvedValue(undefined)
  deleteVersion.mockReset().mockResolvedValue(undefined)
  markRecommended.mockReset().mockResolvedValue(undefined)
  fetchGroups.mockReset()
  routerPush.mockReset()
  toastError.mockReset()
  catalogStore.entries = [paperEntry()]
  groupsStore.groups = []
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

describe('CatalogPlatform', () => {
  it('renders the platform name in the header', async () => {
    const wrapper = await mountSuspended(CatalogPlatform)
    expect(wrapper.text()).toContain('paper')
    expect(wrapper.text()).toContain('Platform')
  })

  it('fetches the catalog and groups on mount', async () => {
    await mountSuspended(CatalogPlatform)
    await flush()
    expect(fetchCatalog).toHaveBeenCalled()
    expect(fetchGroups).toHaveBeenCalled()
  })

  it('renders the entry versions sorted with the recommended one flagged', async () => {
    const wrapper = await mountSuspended(CatalogPlatform)
    await flush()
    expect(wrapper.text()).toContain('1.21.1')
    expect(wrapper.text()).toContain('1.20.4')
    expect(wrapper.text()).toContain('Versions')
  })

  it('redirects to /catalog when the platform is not in the catalog', async () => {
    catalogStore.entries = []
    await mountSuspended(CatalogPlatform)
    await flush()
    expect(toastError).toHaveBeenCalledWith('Platform not found', expect.any(Object))
    expect(routerPush).toHaveBeenCalledWith('/catalog')
  })

  it('redirects to /catalog and toasts when the catalog fetch throws', async () => {
    fetchCatalog.mockRejectedValueOnce(new Error('down'))
    await mountSuspended(CatalogPlatform)
    await flush()
    expect(toastError).toHaveBeenCalledWith("Can't load catalog", expect.any(Object))
    expect(routerPush).toHaveBeenCalledWith('/catalog')
  })

  it('marks a non-recommended version as recommended via the store', async () => {
    const wrapper = await mountSuspended(CatalogPlatform)
    await flush()
    const markBtn = wrapper.find('button[title="Mark as recommended"]')
    expect(markBtn.exists()).toBe(true)
    await markBtn.trigger('click')
    await flush()
    expect(markRecommended).toHaveBeenCalledWith('paper', '1.20.4')
  })

  it('deletes a version after confirming the dialog', async () => {
    const wrapper = await mountSuspended(CatalogPlatform)
    await flush()
    const delBtn = wrapper.find('button[title="Delete version"]')
    await delBtn.trigger('click')
    await flush()
    wrapper.findComponent({ name: 'ConfirmDialog' }).vm.$emit('confirm')
    await flush()
    expect(deleteVersion).toHaveBeenCalledWith('paper', expect.any(String))
  })

  it('lists groups that use the platform', async () => {
    groupsStore.groups = [{ name: 'survival', platform: 'paper', platformVersion: '1.21.1' }]
    const wrapper = await mountSuspended(CatalogPlatform)
    await flush()
    expect(wrapper.text()).toContain('Used by groups')
    expect(wrapper.text()).toContain('survival')
  })
})
