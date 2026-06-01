import type { Component } from 'vue'
import type {
  CloudModule,
  PlatformCapabilityGraph,
  PlatformCloudModule,
  PlatformExtension,
  PlatformExtensionResponse,
  PlatformModuleOverviewResponse,
  PlatformResolvedExtension,
  PlatformResolvedExtensionResponse,
  RegistryListResponse,
  RegistryModuleEntry,
} from '~/types/api'
import type { CloudEvent } from '~/types/events'
import { getAuthToken } from '~/lib/auth-storage'

/**
 * Module registry store: discovers installed modules from the controller,
 * lazy-loads frontend bundles via dynamic import(), and provides reactive
 * state for the sidebar and module pages.
 *
 * Frontend bundles are ES modules loaded via import(). They import shared
 * dependencies from '@prexorcloud/module-sdk' which resolves via the browser
 * import map to the dashboard's auto-generated SDK bridge (/sdk/prexorcloud.mjs).
 */
export const useModuleStore = defineStore('modules', () => {
  const modules = ref<CloudModule[]>([])
  const platformModules = ref<PlatformCloudModule[]>([])
  const capabilityGraph = ref<PlatformCapabilityGraph | null>(null)
  const platformExtensions = ref<PlatformExtension[]>([])
  const resolvedExtensions = ref<PlatformResolvedExtension[]>([])
  const platformError = ref<string | null>(null)
  const registryModules = ref<RegistryModuleEntry[]>([])
  const registries = ref<string[]>([])
  const registryError = ref<string | null>(null)
  const loadedModules = new Map<string, Record<string, Component>>()
  const loadingModules = new Set<string>()
  const loadingWaiters = new Map<string, Array<{ resolve: (v: Record<string, Component>) => void; reject: (e: Error) => void }>>()

  // --- SSE for live module discovery via centralized event bus ---
  function handleModuleEvent(event: CloudEvent) {
    if (event.type === 'RESYNC_REQUIRED') {
      void refreshPlatformState()
      return
    }
    const moduleName = 'moduleName' in event && typeof event.moduleName === 'string'
      ? event.moduleName
      : null
    if (event.type === 'MODULE_LOADED') {
      if (moduleName) invalidate(moduleName)
      void refreshPlatformState()
    }
    if (event.type === 'MODULE_UNLOADED' && moduleName) {
      invalidate(moduleName)
      void refreshPlatformState()
    }
    // Frontend hot-reload from `prexorctl module dev`: the platform module's
    // classloader/data/routes are unchanged, so we only need to bust the
    // bundle cache + refresh the registry so the new contentHash flows
    // through to active <component :is> mounts.
    if (event.type === 'MODULE_FRONTEND_RELOADED' && moduleName) {
      invalidate(moduleName)
      void fetchRegistry()
    }
  }

  let sseConnected = false

  function connectSse() {
    if (sseConnected) return
    sseConnected = true
    const bus = useSseEventBus()
    bus.on(['MODULE_LOADED', 'MODULE_UNLOADED', 'MODULE_FRONTEND_RELOADED', 'RESYNC_REQUIRED'], handleModuleEvent)
    bus.connect()
  }

  function disconnectSse() {
    if (!sseConnected) return
    sseConnected = false
    const bus = useSseEventBus()
    bus.off(['MODULE_LOADED', 'MODULE_UNLOADED', 'MODULE_FRONTEND_RELOADED', 'RESYNC_REQUIRED'], handleModuleEvent)
  }

  const modulesWithFrontend = computed(() =>
    modules.value.filter((m): m is CloudModule & { frontend: NonNullable<CloudModule['frontend']> } =>
      m.frontend !== null,
    ),
  )

  const frontendByModuleId = computed(() => {
    const indexed = new Map<string, CloudModule>()
    for (const mod of modules.value) indexed.set(mod.name, mod)
    return indexed
  })

  async function fetchRegistry() {
    try {
      const { data: res } = await useApiClient().GET('/api/v1/modules')
      modules.value = (res?.data ?? []) as CloudModule[]
    }
    catch {
      modules.value = []
    }
  }

  async function fetchPlatformOverview() {
    try {
      const res = await moduleApiFetch<PlatformModuleOverviewResponse>('/api/v1/modules/platform')
      platformModules.value = res.modules ?? []
      platformError.value = null
    }
    catch (e) {
      platformModules.value = []
      platformError.value = e instanceof Error ? e.message : 'Failed to load platform modules'
    }
  }

  async function fetchCapabilityGraph() {
    try {
      capabilityGraph.value = await moduleApiFetch<PlatformCapabilityGraph>('/api/v1/modules/platform/capabilities')
      platformError.value = null
    }
    catch (e) {
      capabilityGraph.value = null
      platformError.value = e instanceof Error ? e.message : 'Failed to load capability graph'
    }
  }

  async function fetchPlatformExtensions(target?: string) {
    const query = target ? `?target=${encodeURIComponent(target)}` : ''
    try {
      const res = await moduleApiFetch<PlatformExtensionResponse>(`/api/v1/modules/platform/extensions${query}`)
      platformExtensions.value = res.extensions ?? []
      platformError.value = null
    }
    catch (e) {
      platformExtensions.value = []
      platformError.value = e instanceof Error ? e.message : 'Failed to load extension registry'
    }
  }

  async function resolvePlatformExtensions(target: string, runtimeVersion: string, extensionIds: string[] = []) {
    const params = new URLSearchParams({ target, version: runtimeVersion })
    for (const extensionId of extensionIds) params.append('extensionId', extensionId)
    try {
      const res = await moduleApiFetch<PlatformResolvedExtensionResponse>(
        `/api/v1/modules/platform/extensions/resolve?${params.toString()}`,
      )
      resolvedExtensions.value = res.resolved ?? []
      platformError.value = null
    }
    catch (e) {
      resolvedExtensions.value = []
      platformError.value = e instanceof Error ? e.message : 'Failed to resolve extension variants'
    }
  }

  async function refreshPlatformState() {
    await Promise.all([
      fetchRegistry(),
      fetchPlatformOverview(),
      fetchCapabilityGraph(),
      fetchPlatformExtensions(),
    ])
  }

  async function uninstallPlatformModule(moduleId: string) {
    await moduleApiFetch<void>(`/api/v1/modules/platform/${encodeURIComponent(moduleId)}`, { method: 'DELETE' })
    invalidate(moduleId)
    await refreshPlatformState()
  }

  /** Browse the configured registries' aggregated index, optionally filtered by query. */
  async function fetchRegistryCatalog(query?: string) {
    const q = query && query.trim() ? `?q=${encodeURIComponent(query.trim())}` : ''
    try {
      const res = await moduleApiFetch<RegistryListResponse>(`/api/v1/modules/platform/registry${q}`)
      registries.value = res.registries ?? []
      registryModules.value = res.modules ?? []
      registryError.value = null
    }
    catch (e) {
      registryModules.value = []
      registryError.value = e instanceof Error ? e.message : 'Failed to load module registry'
    }
  }

  /** Pull and install a module from a configured registry. Refreshes installed state on success. */
  async function installFromRegistry(moduleId: string, version?: string, registryUrl?: string) {
    const body: Record<string, string> = { moduleId }
    if (version) body.version = version
    if (registryUrl) body.registryUrl = registryUrl
    await moduleApiFetch<unknown>('/api/v1/modules/platform/registry/install', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    await Promise.all([refreshPlatformState(), fetchRegistryCatalog()])
  }

  /**
   * Resolves a URL slug to a module name + component export name + route metadata.
   * e.g. "announcements" -> { moduleName: "announcements", componentName: "AnnouncementsPage" }
   */
  function resolveRoute(slug: string): { moduleName: string; componentName: string } | null {
    for (const mod of modulesWithFrontend.value) {
      if (!slug.startsWith(mod.name)) continue
      const rest = slug.slice(mod.name.length)
      for (const route of mod.frontend.routes) {
        if (matchPath(route.path, rest || '/')) {
          return { moduleName: mod.name, componentName: route.component }
        }
      }
    }
    return null
  }

  /**
   * Loads a module's frontend bundle via dynamic import().
   * The module's ES module imports from '@prexorcloud/module-sdk' are resolved
   * by the browser import map to the dashboard's SDK bridge.
   */
  async function ensureLoaded(moduleName: string): Promise<Record<string, Component>> {
    const cached = loadedModules.get(moduleName)
    if (cached) return cached

    if (loadingModules.has(moduleName)) {
      return new Promise((resolve, reject) => {
        const waiters = loadingWaiters.get(moduleName) ?? []
        waiters.push({ resolve, reject })
        loadingWaiters.set(moduleName, waiters)
      })
    }

    const mod = modulesWithFrontend.value.find(m => m.name === moduleName)
    if (!mod) throw new Error(`Module "${moduleName}" not found or has no frontend`)

    loadingModules.add(moduleName)

    try {
      // Inject CSS if the module provides a stylesheet
      if (mod.frontend.css) {
        const existing = document.querySelector(`link[data-module="${moduleName}"]`)
        if (!existing) {
          const link = document.createElement('link')
          link.rel = 'stylesheet'
          link.href = mod.frontend.css
          link.dataset.module = moduleName
          document.head.appendChild(link)
        }
      }

      // Load the module's ES module entry via dynamic import()
      // The browser resolves '@prexorcloud/module-sdk' imports via the import map
      const entry = await import(/* @vite-ignore */ mod.frontend.entry)

      // The module uses defineModule() which returns { components, setup? }
      let components: Record<string, Component>
      if (entry.default && typeof entry.default === 'object' && entry.default.components) {
        const definition = entry.default as { components: Record<string, Component>; setup?: () => void | Promise<void> }
        components = definition.components
        if (definition.setup) await definition.setup()
      }
      else {
        // Fallback: direct exports pattern
        components = {}
        for (const [key, value] of Object.entries(entry)) {
          if (key !== 'default' && (typeof value === 'object' || typeof value === 'function')) {
            components[key] = markRaw(value as Component)
          }
        }
      }

      // Mark all components as raw to prevent Vue from making them reactive
      for (const [key, value] of Object.entries(components)) {
        components[key] = markRaw(value as Component)
      }

      loadedModules.set(moduleName, components)
      loadingModules.delete(moduleName)
      // Resolve any callers waiting for this module
      const waiters = loadingWaiters.get(moduleName)
      if (waiters) {
        loadingWaiters.delete(moduleName)
        for (const w of waiters) w.resolve(components)
      }
      return components
    }
    catch (err) {
      loadingModules.delete(moduleName)
      // Reject any callers waiting for this module
      const waiters = loadingWaiters.get(moduleName)
      if (waiters) {
        loadingWaiters.delete(moduleName)
        for (const w of waiters) w.reject(err instanceof Error ? err : new Error(String(err)))
      }
      throw err
    }
  }

  /**
   * Remove a module's cached components and injected CSS.
   */
  function invalidate(moduleName: string) {
    loadedModules.delete(moduleName)
    // Clean up injected CSS
    const link = document.querySelector(`link[data-module="${moduleName}"]`)
    if (link) link.remove()
  }

  return {
    modules, modulesWithFrontend, frontendByModuleId,
    platformModules, capabilityGraph, platformExtensions, resolvedExtensions, platformError,
    registryModules, registries, registryError,
    fetchRegistry, fetchPlatformOverview, fetchCapabilityGraph, fetchPlatformExtensions, resolvePlatformExtensions,
    fetchRegistryCatalog, installFromRegistry,
    refreshPlatformState, uninstallPlatformModule,
    resolveRoute, ensureLoaded, invalidate,
    connectSse, disconnectSse,
  }
})

function matchPath(pattern: string, path: string): boolean {
  const patternParts = pattern.split('/').filter(Boolean)
  const pathParts = path.split('/').filter(Boolean)
  if (patternParts.length !== pathParts.length) return false
  return patternParts.every((part, i) => part.startsWith(':') || part === pathParts[i])
}

async function moduleApiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const config = useRuntimeConfig()
  const apiBase = config.public.apiBase as string
  const headers = new Headers(init.headers)
  const token = import.meta.client ? getAuthToken() : null
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(`${apiBase}${path}`, { ...init, headers })
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { error?: { message?: string }; message?: string } | null
    throw new Error(body?.error?.message ?? body?.message ?? `${response.status} ${response.statusText}`)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return await response.json() as T
}
