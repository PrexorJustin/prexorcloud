import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ModulesIndex from '../modules/index.vue'

const { moduleStore, toastSuccess, toastError } = vi.hoisted(() => ({
  moduleStore: {
    platformModules: [] as unknown[],
    capabilityGraph: null as unknown,
    platformExtensions: [] as unknown[],
    resolvedExtensions: [] as unknown[],
    platformError: null as string | null,
    frontendByModuleId: new Map<string, unknown>(),
    moduleHealth: {} as Record<string, unknown>,
    moduleResources: {} as Record<string, unknown>,
    refreshPlatformState: vi.fn(() => Promise.resolve()),
    resolvePlatformExtensions: vi.fn(() => Promise.resolve()),
    uninstallPlatformModule: vi.fn(() => Promise.resolve()),
    fetchModuleDiagnostics: vi.fn(() => Promise.resolve()),
    fetchRegistryCatalog: vi.fn(() => Promise.resolve()),
  },
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

mockNuxtImport('useModuleStore', () => () => reactive(moduleStore))
mockNuxtImport('useRuntimeConfig', () => () => ({
  app: { baseURL: '/', buildId: 'test' },
  public: { apiBase: 'http://api.test' },
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))

function makeModule(over: Record<string, unknown> = {}) {
  return {
    moduleId: 'announcements',
    version: '1.0.0',
    state: 'ACTIVE',
    backend: { entrypoint: 'com.example.Announcements' },
    capabilities: { provides: [], requires: [] },
    unresolvedRequirements: [],
    storage: { mongo: false, redis: false, mongoAvailable: false, redisAvailable: false, mongoDocumentLimit: 0, redisKeyLimit: 0 },
    ...over,
  }
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  moduleStore.platformModules = []
  moduleStore.capabilityGraph = null
  moduleStore.platformExtensions = []
  moduleStore.resolvedExtensions = []
  moduleStore.platformError = null
  moduleStore.frontendByModuleId = new Map()
  moduleStore.moduleHealth = {}
  moduleStore.moduleResources = {}
  moduleStore.refreshPlatformState.mockReset().mockResolvedValue(undefined)
  moduleStore.resolvePlatformExtensions.mockReset().mockResolvedValue(undefined)
  moduleStore.uninstallPlatformModule.mockReset().mockResolvedValue(undefined)
  moduleStore.fetchModuleDiagnostics.mockReset().mockResolvedValue(undefined)
  moduleStore.fetchRegistryCatalog.mockReset().mockResolvedValue(undefined)
  toastSuccess.mockReset()
  toastError.mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ModulesIndex', () => {
  it('renders the header and refreshes platform state on mount', async () => {
    const wrapper = await mountSuspended(ModulesIndex)
    await flush()
    expect(wrapper.text()).toContain('Modules')
    expect(wrapper.text()).toContain('Runtime-loaded plugins')
    expect(moduleStore.refreshPlatformState).toHaveBeenCalled()
    expect(moduleStore.resolvePlatformExtensions).toHaveBeenCalled()
  })

  it('shows the empty state when no modules are installed', async () => {
    const wrapper = await mountSuspended(ModulesIndex)
    await flush()
    expect(wrapper.text()).toContain('No modules installed')
  })

  it('renders a card per installed module with summary counts', async () => {
    moduleStore.platformModules = [
      makeModule(),
      makeModule({ moduleId: 'webhooks', state: 'FAILED' }),
    ]
    const wrapper = await mountSuspended(ModulesIndex)
    await flush()
    expect(wrapper.text()).toContain('announcements')
    expect(wrapper.text()).toContain('webhooks')
    // Installed = 2, Active = 1
    expect(wrapper.text()).toContain('Active')
  })

  it('surfaces the platform error banner', async () => {
    moduleStore.platformError = 'controller exploded'
    const wrapper = await mountSuspended(ModulesIndex)
    await flush()
    expect(wrapper.text()).toContain('controller exploded')
  })

  it('switches to the capabilities view and shows the empty declaration message', async () => {
    const wrapper = await mountSuspended(ModulesIndex)
    await flush()
    const tab = wrapper.findAll('button').find(b => b.text().includes('Capabilities'))!
    await tab.trigger('click')
    expect(wrapper.text()).toContain('No capability declarations')
  })

  it('switching to extensions view shows the resolver and triggers a resolve', async () => {
    const wrapper = await mountSuspended(ModulesIndex)
    await flush()
    const tab = wrapper.findAll('button').find(b => b.text().includes('Extensions'))!
    await tab.trigger('click')
    expect(wrapper.text()).toContain('Extension registry')
    moduleStore.resolvePlatformExtensions.mockClear()
    const resolveBtn = wrapper.findAll('button').find(b => b.text().includes('Resolve'))!
    await resolveBtn.trigger('click')
    expect(moduleStore.resolvePlatformExtensions).toHaveBeenCalledWith('server/paper', '1.20.4')
  })

  it('confirming a module removal calls uninstall and toasts success', async () => {
    moduleStore.platformModules = [makeModule()]
    const wrapper = await mountSuspended(ModulesIndex)
    await flush()
    const removeBtn = wrapper.findAll('button').find(b => b.text().includes('Remove'))!
    await removeBtn.trigger('click')
    await flush()
    const confirm = Array.from(document.body.querySelectorAll('button'))
      .find(b => b.textContent?.trim() === 'Remove') as HTMLElement
    confirm.click()
    await flush()
    expect(moduleStore.uninstallPlatformModule).toHaveBeenCalledWith('announcements')
    expect(toastSuccess).toHaveBeenCalled()
  })

  it('a failed removal toasts an error', async () => {
    moduleStore.platformModules = [makeModule()]
    moduleStore.uninstallPlatformModule.mockRejectedValue(new Error('nope'))
    const wrapper = await mountSuspended(ModulesIndex)
    await flush()
    const removeBtn = wrapper.findAll('button').find(b => b.text().includes('Remove'))!
    await removeBtn.trigger('click')
    await flush()
    const confirm = Array.from(document.body.querySelectorAll('button'))
      .find(b => b.textContent?.trim() === 'Remove') as HTMLElement
    confirm.click()
    await flush()
    expect(toastError).toHaveBeenCalled()
  })
})
