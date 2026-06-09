import type { Role } from '~/lib/constants'

// Auth
export interface LoginRequest { username: string; password: string }
export interface LoginResponse { token: string; user: AuthUser }
export interface AuthUser { username: string; role: Role; permissions: string[]; avatarUrl?: string | null; minecraftUuid?: string | null; minecraftName?: string | null }

// Nodes
export interface NodeHostInfo {
  osName: string; osVersion: string; arch: string; cpuModel: string
  cpuPhysicalCores: number; cpuLogicalCores: number; cpuMaxFreqHz: number; javaVersion: string
  javaVendor: string; javaRuntime: string; javaGc: string
}
export type NodeStatus = 'ONLINE' | 'DRAINING' | 'CORDONED' | 'UNREACHABLE'
export interface ConnectedNode {
  id: string; address: string; type: 'CONNECTED'
  // Widened to string to match the openapi-generated SDK shape; pages narrow
  // via NODE_STATUS_CONFIG and StatusBadge for display.
  status: string
  cpuUsage: number; totalMemoryMb: number; usedMemoryMb: number; freeDiskMb: number; totalDiskMb: number
  instanceCount: number; connectedSince: string; lastHeartbeat: string
  labels?: Record<string, string>; hostInfo?: NodeHostInfo; firstSeen?: string; lastSeen?: string
}
export interface DisconnectedNode { id: string; type: 'DISCONNECTED'; status: 'OFFLINE'; firstSeen: string; lastSeen: string }
export interface PendingNode { id: string; type: 'PENDING'; status: 'PENDING'; tokenId: string; joinToken: string; expiresAt: string }
export type NodeEntry = ConnectedNode | DisconnectedNode | PendingNode
export type CloudNode = ConnectedNode

export interface NodeCacheStatus {
  templates: { name: string; hash: string; sizeBytes: number; lastUsed: string }[]
  jars: { platform: string; version: string; jarFile: string; sizeBytes: number; sha256: string; cachedAt: string }[]
  bootstraps: { configFormat: string; version: string; hasCds: boolean; sizeBytes: number }[]
  totalSizeBytes: number; receivedAt?: string
}

// Groups
export interface ServerGroup {
  name: string; parent: string | null; platform: string; platformVersion: string; jarFile: string
  templates: string[]; scalingMode: string
  minInstances: number; maxInstances: number; maxPlayers: number
  scaleUpThreshold: number; scaleDownAfterSeconds: number; scaleCooldownSeconds: number
  // The following five are not yet surfaced by the OpenAPI schema in some
  // controller versions, so they're optional here. Pages handle absence.
  predictiveScaling?: boolean; scaleUpMargin?: number; burstCeiling?: number
  routing?: string; drainOnShutdown?: boolean
  portRangeStart: number; portRangeEnd: number
  startupTimeoutSeconds: number; shutdownGraceSeconds: number
  maxLifetimeSeconds: number; static: boolean; staticInstanceNames: string[]
  protectedPaths: string[]; fallbackGroup: string | null; bedrockProxyGroup: string; defaultGroup: boolean
  dependsOn: string[]; startupWeight: number; maintenance: boolean
  maintenanceMessage: string; maintenanceBypass: string[]
  updateStrategy: string
  nodeAffinity: string[]; nodeAntiAffinity: string[]; spreadConstraint: string
  priority: number; memoryMb: number; jvmArgs: string[]; env: Record<string, string>
  motds: string[]; motdMode: 'STATIC' | 'SEQUENTIAL' | 'RANDOM'; motdIntervalSeconds: number
  runningInstances: number; totalPlayers: number
}

// Instances
export interface ServerInstance {
  id: string; group: string; node: string
  state: 'SCHEDULED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'CRASHED' | 'DRAINING'
  port: number; playerCount: number; uptimeMs: number; startedAt: string; deploymentRevision: number
}

// Paginated responses (shared with module frontends)
export interface PaginatedResponse<T> { data: T[]; page: number; pageSize: number; total: number }

// Templates
export interface Template { name: string; description: string; platform: string; hash: string; sizeBytes: number }
export interface TemplateVersion { templateName: string; hash: string; sizeBytes: number; createdAt: string }
export interface TemplateFile { name: string; isDirectory: boolean; size: number }
export interface TemplateVariable { key: string; value: string; description?: string }
export interface TemplateSearchResult { path: string; line: number; content: string; matchStart: number; matchEnd: number }
export interface TemplateInheritanceNode { name: string; exists: boolean }

// Deployments — strategy/state widened to string to match SDK shapes; pages
// look up DEPLOY_STATE_CONFIG to render them.
export interface Deployment {
  id: number; groupName: string; revision: number; trigger: string
  strategy: string
  state: string
  templateSnapshot: string; configSnapshot: string
  totalInstances: number; updatedInstances: number
  createdAt: string; completedAt: string | null; rollbackOf: number | null
}

// Catalog
export interface CatalogVersion { version: string; downloadUrl: string; recommended: boolean }
export type CatalogCategory = 'SERVER' | 'PROXY'
export type ConfigFormat = 'paper' | 'spigot' | 'velocity' | 'bungeecord'
export interface CatalogEntry { platform: string; category: CatalogCategory; configFormat?: ConfigFormat | null; versions: CatalogVersion[] }

// Crashes — classification widened to string|undefined to match the SDK shape.
export interface CrashRecord {
  id: string; instanceId: string; group: string; node: string
  exitCode: number
  classification?: string | null
  causeSummary: string
  signature: string
  uptimeMs: number; crashedAt: string; logTail?: string
}

export interface CrashTrendBucket {
  ts: string
  count: number
  byClassification: Record<string, number>
}

export interface CrashTrend {
  windowStart: string
  windowEnd: string
  windowSeconds: number
  bucketSeconds: number
  total: number
  totalsByClassification: Record<string, number>
  buckets: CrashTrendBucket[]
}

// Modules
export interface ModuleFrontendRoute {
  path: string; component: string; title: string
  icon: string | null; nav: boolean; navGroup: string | null
  navGroupOrder: number | null; permission: string | null; adminOnly: boolean
}
export interface ModuleFrontend {
  displayName: string; entry: string; css?: string | null
  contentHash: string; icon: string | null
  routes: ModuleFrontendRoute[]; events: string[]
}
export interface CloudModule {
  name: string; enabled: boolean
  frontend: ModuleFrontend | null
}

export interface PlatformCapabilityProvide {
  id: string; version: string; active?: boolean
}
export interface PlatformCapabilityRequire {
  id: string; versionRange: string
  binding?: { moduleId: string; version: string } | null
}
export interface PlatformUnresolvedRequirement {
  capabilityId: string; versionRange: string; reason: string
}
export interface PlatformModuleStorage {
  mongo: boolean; redis: boolean; mongoAvailable: boolean; redisAvailable: boolean
  mongoDocumentLimit: number; redisKeyLimit: number
  mongoDatabase: string | null; mongoCollectionPrefix: string | null; redisKeyPrefix: string | null
}
export interface PlatformModuleFrontend {
  sdkVersion: number; entry: string
}
export interface PlatformCloudModule {
  moduleId: string; id: string; version: string; manifestVersion: number
  state: 'ACTIVE' | 'INACTIVE' | 'FAILED' | string
  jarFile: string; jarPath: string; sha256: string; sizeBytes: number; storedAt: string
  lastError: string | null
  backend: { entrypoint: string }
  frontend: PlatformModuleFrontend | null
  capabilities: {
    provides: PlatformCapabilityProvide[]
    requires: PlatformCapabilityRequire[]
  }
  storage: PlatformModuleStorage
  extensions: PlatformExtension[]
  unresolvedRequirements: PlatformUnresolvedRequirement[]
}
// Module registry (signed index — GET /api/v1/modules/platform/registry)
export interface RegistryModuleEntry {
  registryUrl: string; registryName: string | null
  moduleId: string; version: string; sha256: string | null
  tags: string[]; compatibleControllerVersions: string[]
  readme: string | null; signed: boolean
  provides: { id: string; version: string }[]
  installed: boolean; installedVersion?: string
}
export interface RegistryListResponse {
  registries: string[]; modules: RegistryModuleEntry[]
}

export interface PlatformCapabilityGraphModule {
  moduleId: string; state: string
  provides: PlatformCapabilityProvide[]
  requires: PlatformCapabilityRequire[]
  unresolvedRequirements: PlatformUnresolvedRequirement[]
}
export interface PlatformCapabilityMetrics {
  resolutionCount: number; unresolvedRequirementCount: number
  rebindingEventCount: number; lastResolutionLatencyMillis: number
}
export interface PlatformCapabilityGraph {
  modules: PlatformCapabilityGraphModule[]
  metrics: PlatformCapabilityMetrics
}
export interface PlatformExtensionVariant {
  id: string; mcVersionRange: string; runtimeApiVersion: string
  artifact: string; sha256: string; installPath: string
}
export interface PlatformExtension {
  moduleId: string; id: string; target: string; activation: string
  conflicts: string[]; variants: PlatformExtensionVariant[]
}
export interface PlatformResolvedExtension {
  moduleId: string; extensionId: string; target: string; activation: string
  variantId: string; mcVersionRange: string; runtimeApiVersion: string
  artifact: string; sha256: string; installPath: string
}
export interface PlatformModuleOverviewResponse {
  modules: PlatformCloudModule[]
  capabilityMetrics: PlatformCapabilityMetrics
}
// Module health (GET /api/v1/modules/platform/{moduleId}/health)
export type ModuleHealthStatus = 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY' | 'UNKNOWN'
export interface ModuleHealthInfo {
  moduleId: string
  monitoringEnabled: boolean
  status: ModuleHealthStatus
  detail: string
  checkedAt: string | null
}
// Module resources + soft quota (GET /api/v1/modules/platform/{moduleId}/resources)
export interface ModuleResourceQuota {
  maxCpuMillisPerMinute: number
  maxAllocatedMbPerMinute: number
  maxThreads: number
}
export interface ModuleQuotaEvaluation {
  cpuMillisPerMinute: number
  allocatedMbPerMinute: number
  liveThreads: number
  cpuExceeded: boolean
  allocationExceeded: boolean
  threadsExceeded: boolean
  anyExceeded: boolean
  evaluatedAt: string
}
export interface ModuleResourceInfo {
  moduleId: string
  trackingEnabled: boolean
  cpuMillis?: number
  allocatedBytes?: number
  liveThreads?: number
  sampledAt?: string
  quota?: ModuleResourceQuota
  quotaEvaluation?: ModuleQuotaEvaluation
}
export interface PlatformExtensionResponse {
  target?: string
  extensions: PlatformExtension[]
}
export interface PlatformResolvedExtensionResponse {
  target: string; runtimeVersion: string
  resolved: PlatformResolvedExtension[]
}

// User Preferences
export interface UserPreferences {
  notifications: Record<string, boolean>; defaultLandingPage: string
  theme: 'light' | 'dark' | 'system'; sidebarExpanded: boolean
}

// Users & Roles
export interface User { username: string; role: Role; createdAt: string }
export interface RoleDefinition { name: string; permissions: string[]; builtIn: boolean; userCount?: number }

// Audit
export interface AuditEntry { id: number; username: string; action: string; resourceType: string; resourceId: string; details: string; ipAddress: string; createdAt: string }

// System
export interface SystemVersion { version: string; gitCommit: string; javaVersion: string }
export interface SystemSettings { nodeCount: number; instanceCount: number; playerCount: number; schedulerInterval: number; heartbeatInterval: number; metricsEnabled: boolean }

// Instance Metrics
export interface WorldSnapshot { name: string; environment: string; entityCount: number; chunkCount: number; playerCount: number }
export interface ProxyMetrics {
  instanceId: string; proxyMemoryUsedMb: number; proxyMemoryMaxMb: number
  proxyUptimeMs: number; totalNetworkPlayers: number
  playerPings: { uuid: string; username: string; ping: number }[]; collectedAt: string
}

export interface InstanceMetrics {
  instanceId: string; tps1m: number; tps5m: number; tps15m: number; msptAvg: number
  heapUsedMb: number; heapMaxMb: number; heapCommittedMb: number
  gcCollections: number; gcTimeMs: number; threadCount: number; daemonThreadCount: number
  playerCount: number; maxPlayers: number; worldCount: number; totalEntities: number; totalChunks: number
  worlds: WorldSnapshot[]; serverVersion: string; pluginCount: number; uptimeMs: number; collectedAt: string
}

// Metrics
export interface MetricsSummary { nodes: number; instances: number; players: number; groups: number; crashes: number }

// Join Tokens
export interface JoinToken { tokenId: string; nodeId: string; expiresAt: string }
export interface JoinTokenCreated { tokenId: string; joinToken: string; expiresAt: string }

// Overview
export interface OverviewStats { nodeCount: number; instanceCount: number; playerCount: number; groupCount: number }

// API Error
export interface ApiError { error: { code: string; message: string; status: number } }
