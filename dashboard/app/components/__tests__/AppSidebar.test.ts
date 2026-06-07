import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import AppSidebar from '../layout/AppSidebar.vue'
import { SidebarProvider } from '~/components/ui/sidebar'

const { route, auth, moduleStore, routerPush } = vi.hoisted(() => ({
  route: { path: '/' } as { path: string },
  auth: {
    can: vi.fn(() => true),
    logout: vi.fn(),
    user: { username: 'alice', role: 'ADMIN' } as { username: string; role: string } | null,
  },
  moduleStore: { modulesWithFrontend: [] as unknown[] },
  routerPush: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => reactive(route))
mockNuxtImport('useAuthStore', () => () => reactive(auth))
mockNuxtImport('useModuleStore', () => () => reactive(moduleStore))
mockNuxtImport('useRouter', () => () => ({
  push: routerPush,
  replace: vi.fn(),
  afterEach: vi.fn(),
  beforeEach: vi.fn(),
  beforeResolve: vi.fn(),
  isReady: () => Promise.resolve(),
  resolve: (to: string | { path?: string }) => {
    const path = typeof to === 'string' ? to : (to.path ?? '/')
    return { href: path, fullPath: path, path, matched: [], params: {}, query: {}, hash: '' }
  },
  currentRoute: reactive(route),
}))

const Host = {
  components: { SidebarProvider, AppSidebar },
  template: '<SidebarProvider><AppSidebar /></SidebarProvider>',
}

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
  document.body.innerHTML = ''
})

beforeEach(() => {
  route.path = '/'
  auth.can.mockReset()
  auth.can.mockReturnValue(true)
  auth.logout.mockReset()
  auth.user = { username: 'alice', role: 'ADMIN' }
  moduleStore.modulesWithFrontend = []
  routerPush.mockReset()
})

async function mount() {
  const wrapper = await mountSuspended(Host)
  active = wrapper
  return wrapper
}

describe('AppSidebar', () => {
  it('renders every nav group when all permissions are granted', async () => {
    const wrapper = await mount()
    const text = wrapper.text()
    for (const label of ['Overview', 'Workloads', 'Cluster', 'Configuration', 'Observability', 'Identity', 'Operations']) {
      expect(text).toContain(label)
    }
  })

  it('drops groups whose every item is permission-gated when access is denied', async () => {
    auth.can.mockReturnValue(false)
    const wrapper = await mount()
    const text = wrapper.text()
    // Identity + Operations have only gated items → gone
    expect(text).not.toContain('Identity')
    expect(text).not.toContain('Operations')
    // Overview has permission-free items → still present
    expect(text).toContain('Overview')
  })

  it('injects a Modules group for runtime modules with nav routes', async () => {
    moduleStore.modulesWithFrontend = [
      { name: 'playtime', frontend: { icon: 'Box', routes: [{ nav: true, title: 'Playtime', path: '/', permission: null, adminOnly: false }] } },
    ]
    const wrapper = await mount()
    expect(wrapper.text()).toContain('Playtime')
  })

  it('renders the signed-in user name and role in the footer', async () => {
    const wrapper = await mount()
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('ADMIN')
  })

  it('logging out clears auth and routes to /login', async () => {
    const wrapper = await mount()
    const trigger = wrapper.findAll('button').find(b => b.text().includes('alice'))!
    await trigger.trigger('click')
    const logout = Array.from(document.body.querySelectorAll('*'))
      .find(el => el.textContent?.trim() === 'Log out') as HTMLElement
    logout.click()
    await Promise.resolve()
    expect(auth.logout).toHaveBeenCalled()
    expect(routerPush).toHaveBeenCalledWith('/login')
  })
})
