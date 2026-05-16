/**
 * Dev-mock interceptors. Installed only when `import.meta.env.DEV` is true
 * AND the auth token equals `DEV_MOCK_TOKEN`.
 *
 * Patches:
 *   • globalThis.fetch — short-circuits API requests with canned data
 *   • globalThis.EventSource — replaces with a no-op stub so the SSE bus
 *     thinks it's connected and doesn't spam reconnects
 *
 * Both patches are idempotent and unwrap-safe — if the token is later
 * cleared the original implementations are still reachable.
 */
import {
  DEV_MOCK_TOKEN,
  mockActivity,
  mockAdminTokens,
  mockAudit,
  mockBackups,
  mockCapabilities,
  mockCatalog,
  mockComposition,
  mockCrashes,
  mockCrashTrend,
  mockDeployments,
  mockDiagnostics,
  mockExtensions,
  mockGroups,
  mockHealth,
  mockInstances,
  mockJourney,
  mockMaintenance,
  mockModules,
  mockNetworks,
  mockNodes,
  mockOverview,
  mockPlayers,
  mockRedisKeyspace,
  mockRedisSchema,
  mockResolved,
  mockRevokedCerts,
  mockRoles,
  mockSystemSettings,
  mockTemplates,
  mockUser,
  mockUsers,
  mockVersion,
  mockWorkloadCredentials,
  paginated,
} from "./data"
import { AUTH_TOKEN_KEY } from "~/lib/auth-storage"

let installed = false

export function installDevMock() {
  if (installed) return
  if (!import.meta.client) return
  if (!import.meta.env.DEV) return
  installed = true

  patchFetch()
  patchEventSource()
}

function isDevTokenActive(): boolean {
  try {
    return localStorage.getItem(AUTH_TOKEN_KEY) === DEV_MOCK_TOKEN
  } catch {
    return false
  }
}

function patchFetch() {
  const original = globalThis.fetch.bind(globalThis)
  globalThis.fetch = async function devMockFetch(input, init) {
    if (!isDevTokenActive()) return original(input, init)

    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url
    const method = (init?.method ?? (input instanceof Request ? input.method : "GET")).toUpperCase()

    const mockResponse = resolveMock(url, method, init)
    if (mockResponse) {
      return new Response(JSON.stringify(mockResponse.body), {
        status: mockResponse.status ?? 200,
        headers: { "Content-Type": "application/json" },
      })
    }
    return original(input, init)
  }
}

interface MockHit { body: unknown; status?: number }

function resolveMock(url: string, method: string, init?: RequestInit): MockHit | null {
  if (!url.includes("/api/v1/")) return null

  const u = new URL(url, "http://localhost")
  const path = u.pathname.replace(/^\/+/, "/")

  // Auth
  if (path.endsWith("/api/v1/auth/me")) return { body: mockUser }
  if (path.endsWith("/api/v1/auth/refresh")) return { body: { token: DEV_MOCK_TOKEN } }
  if (path.endsWith("/api/v1/auth/logout")) return { body: {} }
  if (path.endsWith("/api/v1/auth/login")) {
    const body = parseJsonBody(init)
    return { body: { token: DEV_MOCK_TOKEN, user: { ...mockUser, username: body?.username ?? "dev" } } }
  }

  // Pagination helper — used by many endpoints below.
  const list = paginatedFromQuery(u)

  // Overview + system
  if (path.endsWith("/api/v1/overview")) return { body: mockOverview }
  if (path.endsWith("/api/v1/system/health")) return { body: mockHealth }
  if (path.endsWith("/api/v1/system/ready")) return { body: { status: "UP" } }
  if (path.endsWith("/api/v1/system/version")) return { body: mockVersion }
  if (path.endsWith("/api/v1/system/diagnostics")) return { body: mockDiagnostics }
  if (path.endsWith("/api/v1/system/redis/keyspace")) return { body: mockRedisKeyspace }
  if (path.endsWith("/api/v1/system/redis/schema")) return { body: mockRedisSchema }
  if (path.endsWith("/api/v1/system/settings")) return { body: mockSystemSettings }
  if (path.endsWith("/api/v1/system/logs")) return { body: paginated([], list.page, list.pageSize) }

  // Maintenance
  if (path.endsWith("/api/v1/maintenance")) {
    if (method === "PUT") {
      const body = parseJsonBody(init) ?? {}
      Object.assign(mockMaintenance, body)
      return { body: mockMaintenance }
    }
    return { body: mockMaintenance }
  }

  // Backups
  if (path.endsWith("/api/v1/backups") || path.endsWith("/api/v1/backups/")) {
    if (method === "POST") {
      const body = parseJsonBody(init) ?? {}
      const created = { id: `bkp-${Date.now()}`, createdAt: new Date().toISOString(), sizeBytes: 140_000_000, instanceCount: mockInstances.length, templateCount: mockTemplates.length, verifiedAt: null, verifyStatus: "PENDING" as const, notes: String(body.notes ?? "") }
      mockBackups.unshift(created)
      return { body: created }
    }
    return { body: paginated(mockBackups, list.page, list.pageSize) }
  }
  if (path.endsWith("/api/v1/backups/prune")) return { body: { pruned: 0 }, status: 200 }
  if (/\/api\/v1\/backups\/[^/]+\/verify$/.test(path)) return { body: {}, status: 200 }
  if (/\/api\/v1\/backups\/[^/]+$/.test(path)) {
    const id = decodeURIComponent(path.split("/").pop()!)
    return { body: mockBackups.find(b => b.id === id) ?? mockBackups[0] }
  }
  if (path.endsWith("/api/v1/restore")) return { body: { restoreId: `rst-${Date.now()}` }, status: 202 }

  // (`list` already declared at the top of this function.)

  if (path.endsWith("/api/v1/services") || path.endsWith("/api/v1/services/")) {
    return { body: paginated(mockInstances, list.page, list.pageSize) }
  }
  if (/\/api\/v1\/services\/[^/]+$/.test(path)) {
    const id = decodeURIComponent(path.split("/").pop()!)
    const inst = mockInstances.find(i => i.id === id) ?? mockInstances[0]
    return { body: inst }
  }
  if (path.endsWith("/composition")) return { body: { templates: [], extensions: [] } }
  if (path.endsWith("/metrics") && path.includes("/services/")) {
    return { body: {
      instanceId: path.split("/")[4],
      tps1m: 19.8, tps5m: 19.5, tps15m: 19.6,
      msptAvg: 38, heapUsedMb: 1_400, heapMaxMb: 2_048,
      gcCollections: 124, gcTimeMs: 380, threadCount: 56,
      serverVersion: "Paper-1.21.1-12", pluginCount: 14,
      worlds: [
        { name: "world",        environment: "OVERWORLD", entityCount: 1_240, chunkCount: 720, playerCount: 22 },
        { name: "world_nether", environment: "NETHER",    entityCount: 320,   chunkCount: 110, playerCount: 0 },
      ],
    } }
  }
  if (path.endsWith("/proxy-metrics")) {
    return { body: {
      instanceId: path.split("/")[4],
      proxyMemoryUsedMb: 380, proxyMemoryMaxMb: 1_024, proxyUptimeMs: 72 * 3600 * 1000,
      totalNetworkPlayers: 147,
      playerPings: [
        { uuid: "1a2b3c4d-1111-2222-3333-444455556666", username: "Steve", ping: 38 },
        { uuid: "5e6f7g8h-1111-2222-3333-444455556666", username: "Alex", ping: 92 },
        { uuid: "9i0j1k2l-1111-2222-3333-444455556666", username: "Notch", ping: 145 },
      ],
    } }
  }

  // Nodes
  if (path.endsWith("/api/v1/nodes")) return { body: mockNodes }
  if (/\/api\/v1\/nodes\/[^/]+$/.test(path)) {
    const id = path.split("/").pop()!
    return { body: mockNodes.find(n => n.id === id) ?? mockNodes[0] }
  }
  if (path.endsWith("/cache") && path.includes("/nodes/")) {
    return { body: { templates: [], modules: [], lastRefreshedAt: new Date().toISOString() } }
  }
  if (path.endsWith("/api/v1/nodes/revoked-certs")) return { body: [] }

  // Groups
  if (path.endsWith("/api/v1/groups") || path.endsWith("/api/v1/groups/")) return { body: mockGroups }
  if (/\/api\/v1\/groups\/[^/]+\/deployments$/.test(path)) {
    return { body: paginated(mockDeployments, list.page, list.pageSize) }
  }
  if (/\/api\/v1\/groups\/[^/]+\/resolved$/.test(path)) {
    const name = path.split("/")[4]
    return { body: { ...(mockGroups.find(g => g.name === name) ?? mockGroups[0]), resolvedTemplates: [] } }
  }
  if (/\/api\/v1\/groups\/[^/]+$/.test(path)) {
    const name = decodeURIComponent(path.split("/").pop()!)
    return { body: mockGroups.find(g => g.name === name) ?? mockGroups[0] }
  }

  // Networks
  if (path.endsWith("/api/v1/networks") || path.endsWith("/api/v1/networks/")) return { body: mockNetworks }

  // Templates
  if (path.endsWith("/api/v1/templates") || path.endsWith("/api/v1/templates/")) return { body: mockTemplates }
  if (/\/api\/v1\/templates\/[^/]+\/files\b/.test(path)) {
    return { body: { children: [
      { name: "config.yml",     type: "FILE",      size: 2_400 },
      { name: "plugins",        type: "DIRECTORY", size: 0 },
      { name: "world",          type: "DIRECTORY", size: 0 },
    ] } }
  }
  if (/\/api\/v1\/templates\/[^/]+\/versions$/.test(path)) {
    return { body: [
      { hash: "i9j0k1l2", message: "Bump server.properties limit", createdAt: new Date(Date.now() - 24 * 3600 * 1000).toISOString(), bytes: 4_800_000 },
      { hash: "f7g8h9i0", message: "Initial commit",               createdAt: new Date(Date.now() - 96 * 3600 * 1000).toISOString(), bytes: 4_700_000 },
    ] }
  }
  if (/\/api\/v1\/templates\/[^/]+\/inheritance$/.test(path)) {
    return { body: { chain: ["base", "base-paper"] } }
  }
  if (/\/api\/v1\/templates\/[^/]+\/variables$/.test(path)) {
    return { body: { variables: {} } }
  }
  if (/\/api\/v1\/templates\/[^/]+$/.test(path)) {
    const name = decodeURIComponent(path.split("/").pop()!)
    return { body: mockTemplates.find(t => t.name === name) ?? mockTemplates[0] }
  }

  // Catalog
  if (path.endsWith("/api/v1/catalog")) return { body: mockCatalog }
  if (/\/api\/v1\/catalog\/[^/]+\/versions$/.test(path)) {
    const platform = path.split("/")[4] ?? ""
    return { body: mockCatalog.find(c => c.platform.toLowerCase() === platform.toLowerCase())?.versions ?? [] }
  }

  // Crashes
  if (path.endsWith("/api/v1/crashes") || path.endsWith("/api/v1/crashes/")) {
    return { body: paginated(mockCrashes, list.page, list.pageSize) }
  }
  if (path.endsWith("/api/v1/crashes/trends")) {
    return { body: mockCrashTrend }
  }
  if (/\/api\/v1\/crashes\/[^/]+$/.test(path)) {
    const id = path.split("/").pop()!
    return { body: mockCrashes.find(c => c.id === id) ?? mockCrashes[0] }
  }

  // Audit
  if (path.endsWith("/api/v1/audit")) return { body: paginated(mockAudit, list.page, list.pageSize) }

  // Identity — users
  if (path.endsWith("/api/v1/users") || path.endsWith("/api/v1/users/")) {
    return { body: paginated(mockUsers, list.page, list.pageSize) }
  }
  if (/\/api\/v1\/users\/[^/]+\/preferences$/.test(path)) {
    return { body: { notifications: { crashes: true, deployments: true, nodes: true }, defaultLandingPage: '/', theme: 'system', sidebarExpanded: true } }
  }
  if (/\/api\/v1\/users\/[^/]+\/avatar$/.test(path)) {
    return { body: {}, status: method === "GET" ? 404 : 200 }
  }
  if (/\/api\/v1\/users\/[^/]+\/minecraft$/.test(path)) {
    return { body: {} }
  }
  if (/\/api\/v1\/users\/[^/]+$/.test(path)) {
    const username = decodeURIComponent(path.split("/").pop()!)
    return { body: mockUsers.find(u => u.username === username) ?? mockUsers[0] }
  }

  // Identity — roles
  if (path.endsWith("/api/v1/roles") || path.endsWith("/api/v1/roles/")) {
    return { body: paginated(mockRoles, list.page, list.pageSize) }
  }
  if (/\/api\/v1\/roles\/[^/]+$/.test(path)) {
    const name = decodeURIComponent(path.split("/").pop()!)
    return { body: mockRoles.find(r => r.name === name) ?? mockRoles[0] }
  }

  // Identity — admin (join) tokens
  if (path.endsWith("/api/v1/admin/tokens") || path.endsWith("/api/v1/admin/tokens/")) {
    if (method === "POST") {
      const created = { tokenId: `tok-${Math.random().toString(36).slice(2, 8)}`, nodeId: parseJsonBody(init)?.nodeId ?? null, expiresAt: new Date(Date.now() + 86_400_000).toISOString(), createdAt: new Date().toISOString(), joinToken: `dev-${Math.random().toString(36).slice(2)}` }
      return { body: created }
    }
    return { body: paginated(mockAdminTokens, list.page, list.pageSize) }
  }
  if (/\/api\/v1\/admin\/tokens\/[^/]+$/.test(path)) {
    return { body: {}, status: 200 }
  }

  // Identity — workload credentials
  if (path.endsWith("/api/v1/workloads/credentials") || path.endsWith("/api/v1/workloads/credentials/")) {
    return { body: paginated(mockWorkloadCredentials, list.page, list.pageSize) }
  }
  if (/\/api\/v1\/workloads\/credentials\/instances\/[^/]+$/.test(path)) {
    return { body: {}, status: 200 }
  }
  if (/\/api\/v1\/workloads\/credentials\/[^/]+$/.test(path)) {
    return { body: {}, status: 200 }
  }

  // Modules
  if (path.endsWith("/api/v1/modules")) return { body: mockModules }
  if (path.endsWith("/api/v1/modules/platform")) return { body: mockModules }
  if (path.endsWith("/api/v1/modules/platform/extensions")) return { body: mockExtensions }
  if (path.endsWith("/api/v1/modules/platform/capabilities")) return { body: mockCapabilities }
  if (path.endsWith("/api/v1/modules/platform/leaked-classloaders")) return { body: [] }
  if (path.endsWith("/api/v1/modules/platform/manifests")) return { body: [] }

  // Events / SSE prep
  if (path.endsWith("/api/v1/events/ticket")) return { body: { ticket: "dev-mock-ticket" } }
  if (path.endsWith("/api/v1/events")) return { body: paginated(mockActivity, list.page, list.pageSize) }
  if (path.endsWith("/api/v1/events/active")) return { body: [] }

  // Players + journey + transfer
  if (path.endsWith("/api/v1/players") || path.endsWith("/api/v1/players/")) {
    return { body: paginated(mockPlayers, list.page, list.pageSize) }
  }
  if (/\/api\/v1\/players\/[^/]+\/journey$/.test(path)) {
    const id = decodeURIComponent(path.split("/")[4] ?? "")
    return { body: paginated(mockJourney(id), list.page, list.pageSize) }
  }
  if (/\/api\/v1\/players\/[^/]+\/transfer$/.test(path)) return { body: {}, status: 200 }
  if (/\/api\/v1\/players\/[^/]+$/.test(path)) {
    const id = decodeURIComponent(path.split("/").pop()!)
    return { body: mockPlayers.find(p => p.id === id || p.uuid === id) ?? mockPlayers[0] }
  }

  // Node TLS revocation
  if (path.endsWith("/api/v1/nodes/revoked-certs")) return { body: paginated(mockRevokedCerts, list.page, list.pageSize) }
  if (/\/api\/v1\/nodes\/[^/]+\/revoke-cert$/.test(path)) return { body: {}, status: 200 }

  // Instance composition + group resolved + cache warm (PR 5 additions)
  if (/\/api\/v1\/services\/[^/]+\/composition$/.test(path)) return { body: mockComposition }
  if (/\/api\/v1\/groups\/[^/]+\/resolved$/.test(path)) return { body: mockResolved }
  if (/\/api\/v1\/nodes\/[^/]+\/cache\/warm$/.test(path)) return { body: {}, status: 202 }

  // Mutations — accept and return success-shaped echo
  if (method !== "GET") return { body: {}, status: 200 }

  // Anything we don't know about: empty list / object
  return { body: {} }
}

function paginatedFromQuery(u: URL) {
  const page = Number.parseInt(u.searchParams.get("page") ?? "1", 10) || 1
  const pageSize = Number.parseInt(u.searchParams.get("pageSize") ?? "100", 10) || 100
  return { page, pageSize }
}

function parseJsonBody(init?: RequestInit): Record<string, unknown> | null {
  if (!init?.body) return null
  try { return JSON.parse(init.body as string) as Record<string, unknown> } catch { return null }
}

function patchEventSource() {
  if (typeof EventSource === "undefined") return
  const Original = EventSource

  class DevMockEventSource extends EventTarget {
    static readonly CONNECTING = 0
    static readonly OPEN = 1
    static readonly CLOSED = 2
    readonly CONNECTING = DevMockEventSource.CONNECTING
    readonly OPEN = DevMockEventSource.OPEN
    readonly CLOSED = DevMockEventSource.CLOSED

    url: string
    withCredentials = false
    readyState: 0 | 1 | 2 = 1
    onopen: ((this: EventSource, ev: Event) => void) | null = null
    onmessage: ((this: EventSource, ev: MessageEvent) => void) | null = null
    onerror: ((this: EventSource, ev: Event) => void) | null = null

    constructor(url: string | URL, init?: EventSourceInit) {
      super()
      this.url = typeof url === "string" ? url : url.toString()
      // Pretend we opened successfully on next tick.
      queueMicrotask(() => {
        this.onopen?.call(this as unknown as EventSource, new Event("open"))
        this.dispatchEvent(new Event("open"))
      })
      void init
    }

    close() { this.readyState = 2 }
  }

  globalThis.EventSource = new Proxy(Original, {
    construct(target, args: ConstructorParameters<typeof EventSource>) {
      const url = String(args[0] ?? "")
      if (isDevTokenActive() && url.includes("/api/v1/events/stream")) {
        return new DevMockEventSource(url) as unknown as EventSource
      }
      return Reflect.construct(target, args)
    },
  })
}
