import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive, defineComponent, h } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ModuleSlug from '../modules/[...slug].vue'

const { route, moduleStore } = vi.hoisted(() => ({
  route: { params: { slug: ['announcements'] } as { slug: string[] | string } },
  moduleStore: {
    resolveRoute: vi.fn(),
    ensureLoaded: vi.fn(),
  },
}))

mockNuxtImport('useRoute', () => () => reactive(route))
mockNuxtImport('useModuleStore', () => () => reactive(moduleStore))

const StubModulePage = defineComponent({
  name: 'StubModulePage',
  setup: () => () => h('div', { class: 'stub-module-page' }, 'Module content here'),
})

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  route.params = { slug: ['announcements'] }
  moduleStore.resolveRoute.mockReset()
  moduleStore.ensureLoaded.mockReset()
})

describe('ModuleSlug', () => {
  it('resolves the slug via the module store on mount', async () => {
    moduleStore.resolveRoute.mockReturnValue({ moduleName: 'announcements', componentName: 'AnnouncementsPage' })
    moduleStore.ensureLoaded.mockResolvedValue({ AnnouncementsPage: StubModulePage })
    await mountSuspended(ModuleSlug)
    await flush()
    expect(moduleStore.resolveRoute).toHaveBeenCalledWith('announcements')
    expect(moduleStore.ensureLoaded).toHaveBeenCalledWith('announcements')
  })

  it('renders the resolved module component once loaded', async () => {
    moduleStore.resolveRoute.mockReturnValue({ moduleName: 'announcements', componentName: 'AnnouncementsPage' })
    moduleStore.ensureLoaded.mockResolvedValue({ AnnouncementsPage: StubModulePage })
    const wrapper = await mountSuspended(ModuleSlug)
    await flush()
    expect(wrapper.text()).toContain('Module content here')
  })

  it('joins a multi-segment slug array before resolving', async () => {
    route.params = { slug: ['announcements', '42'] }
    moduleStore.resolveRoute.mockReturnValue({ moduleName: 'announcements', componentName: 'AnnouncementsDetail' })
    moduleStore.ensureLoaded.mockResolvedValue({ AnnouncementsDetail: StubModulePage })
    await mountSuspended(ModuleSlug)
    await flush()
    expect(moduleStore.resolveRoute).toHaveBeenCalledWith('announcements/42')
  })

  it('shows the not-found error state when the slug does not resolve', async () => {
    moduleStore.resolveRoute.mockReturnValue(null)
    const wrapper = await mountSuspended(ModuleSlug)
    await flush()
    expect(wrapper.text()).toContain("Can't load module")
    expect(wrapper.text()).toContain('Module page not found')
    expect(moduleStore.ensureLoaded).not.toHaveBeenCalled()
  })

  it('shows the error state when the named component is missing from the bundle', async () => {
    moduleStore.resolveRoute.mockReturnValue({ moduleName: 'announcements', componentName: 'MissingPage' })
    moduleStore.ensureLoaded.mockResolvedValue({ SomethingElse: StubModulePage })
    const wrapper = await mountSuspended(ModuleSlug)
    await flush()
    expect(wrapper.text()).toContain('Component "MissingPage" not found in module "announcements"')
  })

  it('surfaces the thrown error message when the bundle fails to load', async () => {
    moduleStore.resolveRoute.mockReturnValue({ moduleName: 'announcements', componentName: 'AnnouncementsPage' })
    moduleStore.ensureLoaded.mockRejectedValue(new Error('network down'))
    const wrapper = await mountSuspended(ModuleSlug)
    await flush()
    expect(wrapper.text()).toContain("Can't load module")
    expect(wrapper.text()).toContain('network down')
  })

  it('renders a loading state initially while the bundle is in flight', async () => {
    moduleStore.resolveRoute.mockReturnValue({ moduleName: 'announcements', componentName: 'AnnouncementsPage' })
    let resolve!: (v: Record<string, unknown>) => void
    moduleStore.ensureLoaded.mockReturnValue(new Promise((r) => { resolve = r }))
    const wrapper = await mountSuspended(ModuleSlug)
    expect(wrapper.text()).toContain('Loading module…')
    resolve({ AnnouncementsPage: StubModulePage })
    await flush()
  })
})
