import type { ComputedRef, Ref } from 'vue'

// ── Roles ──────────────────────────────────────────────────────────────────────

export type Role = 'ADMIN' | 'OPERATOR' | 'VIEWER'

// ── Auth ───────────────────────────────────────────────────────────────────────

export interface AuthUser {
  username: string
  role: Role
  avatarUrl?: string | null
  minecraftUuid?: string | null
  minecraftName?: string | null
}

// ── Nodes ──────────────────────────────────────────────────────────────────────

export interface NodeHostInfo {
  osName: string; osVersion: string; arch: string; cpuModel: string
  cpuPhysicalCores: number; cpuLogicalCores: number; cpuMaxFreqHz: number; javaVersion: string
  javaVendor: string; javaRuntime: string; javaGc: string
}

export interface ConnectedNode {
  id: string; address: string; type: 'CONNECTED'
  status: 'ONLINE' | 'DRAINING' | 'CORDONED' | 'UNREACHABLE'
  cpuUsage: number; totalMemoryMb: number; usedMemoryMb: number; freeDiskMb: number; totalDiskMb: number
  instanceCount: number; connectedSince: string; lastHeartbeat: string
  labels?: Record<string, string>; hostInfo?: NodeHostInfo; firstSeen?: string; lastSeen?: string
}

export interface DisconnectedNode {
  id: string; type: 'DISCONNECTED'; status: 'OFFLINE'; firstSeen: string; lastSeen: string
}

export interface PendingNode {
  id: string; type: 'PENDING'; status: 'PENDING'; tokenId: string; joinToken: string; expiresAt: string
}

export type NodeEntry = ConnectedNode | DisconnectedNode | PendingNode
export type CloudNode = ConnectedNode

export interface NodeCacheStatus {
  templates: { name: string; hash: string; sizeBytes: number; lastUsed: string }[]
  jars: { platform: string; version: string; jarFile: string; sizeBytes: number; sha256: string; cachedAt: string }[]
  bootstraps: { configFormat: string; version: string; hasCds: boolean; sizeBytes: number }[]
  totalSizeBytes: number; receivedAt?: string
}

// ── Groups ─────────────────────────────────────────────────────────────────────

export interface ServerGroup {
  name: string; parent: string | null; platform: string; platformVersion: string; jarFile: string
  templates: string[]; scalingMode: 'STATIC' | 'DYNAMIC' | 'MANUAL'
  minInstances: number; maxInstances: number; maxPlayers: number
  scaleUpThreshold: number; scaleDownAfterSeconds: number; scaleCooldownSeconds: number
  predictiveScaling: boolean; scaleUpMargin: number; burstCeiling: number
  routing: string; portRangeStart: number; portRangeEnd: number
  startupTimeoutSeconds: number; shutdownGraceSeconds: number; drainOnShutdown: boolean
  maxLifetimeSeconds: number; static: boolean; staticInstanceNames: string[]
  protectedPaths: string[]; fallbackGroup: string | null; defaultGroup: boolean
  dependsOn: string[]; startupWeight: number; maintenance: boolean
  updateStrategy: 'ON_NEW' | 'ROLLING' | 'CANARY' | 'MANUAL'
  nodeAffinity: string[]; nodeAntiAffinity: string[]; spreadConstraint: string
  priority: number; memoryMb: number; jvmArgs: string[]; env: Record<string, string>
  runningInstances: number; totalPlayers: number
}

// ── Instances ──────────────────────────────────────────────────────────────────

export type InstanceState = 'SCHEDULED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'CRASHED' | 'DRAINING'

export interface ServerInstance {
  id: string; group: string; node: string
  state: InstanceState
  port: number; playerCount: number; uptimeMs: number; startedAt: string; deploymentRevision: number
}

// ── Players ────────────────────────────────────────────────────────────────────

export interface Player {
  id: string; name: string; currentInstance: string; currentGroup: string
  proxyInstance: string; connectedSince: string
}

export interface PlayerRecord {
  uuid: string; name: string; online: boolean; firstSeen: string; lastSeen: string
  totalPlaytimeSeconds: number; sessionCount: number; lastGroup: string
  nameHistory: NameHistoryEntry[]
}

export interface NameHistoryEntry { name: string; changedAt: string }

export interface PlayerSession {
  id: number; instanceId: string; group: string
  startTime: string; endTime: string | null; durationSeconds: number | null
}

// ── Pagination ─────────────────────────────────────────────────────────────────

export interface PaginatedResponse<T> { items: T[]; page: number; pageSize: number; total: number }

// ── Templates ──────────────────────────────────────────────────────────────────

export interface Template { name: string; description: string; platform: string; hash: string; sizeBytes: number }
export interface TemplateVersion { templateName: string; hash: string; sizeBytes: number; createdAt: string }

// ── Deployments ────────────────────────────────────────────────────────────────

export interface Deployment {
  id: number; groupName: string; revision: number; trigger: string
  strategy: 'ON_NEW' | 'ROLLING' | 'CANARY' | 'MANUAL'
  state: 'PENDING' | 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'ROLLED_BACK'
  templateSnapshot: string; configSnapshot: string
  totalInstances: number; updatedInstances: number
  createdAt: string; completedAt: string | null; rollbackOf: number | null
}

// ── Metrics ────────────────────────────────────────────────────────────────────

export interface WorldSnapshot { name: string; environment: string; entityCount: number; chunkCount: number; playerCount: number }

export interface InstanceMetrics {
  instanceId: string; tps1m: number; tps5m: number; tps15m: number; msptAvg: number
  heapUsedMb: number; heapMaxMb: number; heapCommittedMb: number
  gcCollections: number; gcTimeMs: number; threadCount: number; daemonThreadCount: number
  playerCount: number; maxPlayers: number; worldCount: number; totalEntities: number; totalChunks: number
  worlds: WorldSnapshot[]; serverVersion: string; pluginCount: number; uptimeMs: number; collectedAt: string
}

export interface ProxyMetrics {
  instanceId: string; proxyMemoryUsedMb: number; proxyMemoryMaxMb: number
  proxyUptimeMs: number; totalNetworkPlayers: number
  playerPings: { uuid: string; username: string; ping: number }[]; collectedAt: string
}

// ── Modules ────────────────────────────────────────────────────────────────────

export interface ModuleFrontendRoute {
  path: string; component: string; title: string
  icon: string | null; nav: boolean; navGroup: string | null
  permission: string | null; adminOnly: boolean
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

// ── Capabilities ───────────────────────────────────────────────────────────────

/**
 * Active capability binding as seen by the dashboard.
 *
 * Mirrors `CapabilityRegistry.CapabilityBinding` on the controller. The
 * `moduleId` may be `@controller` for capabilities the controller registers
 * itself (e.g. `prexor.player.journey` before Layer 5 extracts it).
 */
export interface CapabilityBinding {
  capabilityId: string
  version: string
  moduleId: string
}

// ── API Error ──────────────────────────────────────────────────────────────────

export interface ApiError { error: { code: string; message: string; status: number } }

// ── Events ─────────────────────────────────────────────────────────────────────

export interface BaseEvent { type: string; timestamp: string }

export interface NodeConnectedEvent extends BaseEvent { type: 'NODE_CONNECTED'; name: string; address: string }
export interface NodeDisconnectedEvent extends BaseEvent { type: 'NODE_DISCONNECTED'; nodeId: string }
export interface NodeStatusEvent extends BaseEvent { type: 'NODE_STATUS'; nodeId: string; cpuUsage: number; usedMemoryMb: number; totalMemoryMb: number }
export interface NodeCacheStatusEvent extends BaseEvent { type: 'NODE_CACHE_STATUS'; nodeId: string }
export interface NodeDrainRequestedEvent extends BaseEvent { type: 'NODE_DRAIN_REQUESTED'; nodeId: string }
export interface NodeDrainCompletedEvent extends BaseEvent { type: 'NODE_DRAIN_COMPLETED'; nodeId: string }

export interface InstanceScheduledEvent extends BaseEvent { type: 'INSTANCE_SCHEDULED'; instanceId: string; group: string; node: string }
export interface InstanceStartedEvent extends BaseEvent { type: 'INSTANCE_STARTED'; instanceId: string }
export interface InstanceStoppedEvent extends BaseEvent { type: 'INSTANCE_STOPPED'; instanceId: string }
export interface InstanceCrashedEvent extends BaseEvent { type: 'INSTANCE_CRASHED'; instanceId: string; classification: string; exitCode: number }
export interface InstanceStateChangedEvent extends BaseEvent { type: 'INSTANCE_STATE_CHANGED'; instanceId: string; oldState: string; newState: string }
export interface InstanceMetricsEvent extends BaseEvent { type: 'INSTANCE_METRICS'; instanceId: string }

export interface PlayerConnectedEvent extends BaseEvent { type: 'PLAYER_CONNECTED'; playerName: string; playerId: string; server: string }
export interface PlayerDisconnectedEvent extends BaseEvent { type: 'PLAYER_DISCONNECTED'; playerName: string; playerId: string }

export interface GroupCreatedEvent extends BaseEvent { type: 'GROUP_CREATED'; groupName: string }
export interface GroupUpdatedEvent extends BaseEvent { type: 'GROUP_UPDATED'; groupName: string }
export interface GroupDeletedEvent extends BaseEvent { type: 'GROUP_DELETED'; groupName: string }
export interface GroupCrashLoopEvent extends BaseEvent { type: 'GROUP_CRASH_LOOP'; groupName: string }

export interface TemplateUpdatedEvent extends BaseEvent { type: 'TEMPLATE_UPDATED'; templateName: string; oldHash: string; newHash: string }
export interface MetricsUpdateEvent extends BaseEvent { type: 'METRICS_UPDATE' }

export interface DeploymentCreatedEvent extends BaseEvent { type: 'DEPLOYMENT_CREATED'; groupName: string; revision: number }
export interface DeploymentCompletedEvent extends BaseEvent { type: 'DEPLOYMENT_COMPLETED'; groupName: string; revision: number }
export interface DeploymentRolledBackEvent extends BaseEvent { type: 'DEPLOYMENT_ROLLED_BACK'; groupName: string; revision: number }

export interface CustomCloudEvent extends BaseEvent { type: string; source: string; payload: Record<string, unknown> }

export type CloudEvent =
  | NodeConnectedEvent | NodeDisconnectedEvent | NodeStatusEvent
  | NodeCacheStatusEvent | NodeDrainRequestedEvent | NodeDrainCompletedEvent
  | InstanceScheduledEvent | InstanceStartedEvent | InstanceStoppedEvent
  | InstanceCrashedEvent | InstanceStateChangedEvent | InstanceMetricsEvent
  | PlayerConnectedEvent | PlayerDisconnectedEvent
  | GroupCreatedEvent | GroupUpdatedEvent | GroupDeletedEvent | GroupCrashLoopEvent
  | TemplateUpdatedEvent | MetricsUpdateEvent
  | DeploymentCreatedEvent | DeploymentCompletedEvent | DeploymentRolledBackEvent
  | CustomCloudEvent

// ── SDK-specific types ─────────────────────────────────────────────────────────

export interface ModuleApi {
  get: <T = unknown>(path: string) => Promise<T>
  post: <T = unknown>(path: string, body?: unknown) => Promise<T>
  put: <T = unknown>(path: string, body?: unknown) => Promise<T>
  patch: <T = unknown>(path: string, body?: unknown) => Promise<T>
  del: <T = unknown>(path: string) => Promise<T>
}

export interface ModuleAuth {
  user: AuthUser | null
  isAuthenticated: boolean
}

export interface EventSubscription {
  stop: () => void
}

export interface ModuleSetupContext {
  api: ModuleApi
  events: (eventTypes: string[], handler: (event: CloudEvent) => void) => EventSubscription
}

export interface DefineModuleOptions {
  components: Record<string, unknown>
  setup?: (ctx: ModuleSetupContext) => void
}

// ── Paginated data ───────────────────────────────────────────────────────────

export interface PaginatedDataOptions {
  pageSize?: number
  immediate?: boolean
  refreshOn?: string[]
  searchDebounce?: number
}

export interface PaginatedDataReturn<T> {
  items: Ref<T[]>
  page: Ref<number>
  pageSize: Ref<number>
  total: Ref<number>
  totalPages: ComputedRef<number>
  hasNextPage: ComputedRef<boolean>
  hasPrevPage: ComputedRef<boolean>
  loading: Ref<boolean>
  error: Ref<string | null>
  search: Ref<string>
  filters: Ref<Record<string, string>>
  fetch: () => Promise<void>
  refresh: () => Promise<void>
  nextPage: () => void
  prevPage: () => void
  goToPage: (p: number) => void
}

// ── Filter list ──────────────────────────────────────────────────────────────

export interface FilterOption {
  key: string
  label: string
  icon?: any
}

export interface FilterListOptions {
  searchFields?: (item: any) => string[]
}

export interface FilteredListReturn<T> {
  search: { value: string }
  filteredItems: { value: T[] }
  activeFilters: { value: Set<string> }
  toggleFilter: (key: string) => void
}

/** Toast notification function signature (provided by vue-sonner at runtime) */
export type ToastFn = ((message: string, data?: { description?: string; duration?: number }) => string | number) & {
  success: (message: string, data?: { description?: string; duration?: number }) => string | number
  error: (message: string, data?: { description?: string; duration?: number }) => string | number
  warning: (message: string, data?: { description?: string; duration?: number }) => string | number
  info: (message: string, data?: { description?: string; duration?: number }) => string | number
  loading: (message: string, data?: { description?: string; duration?: number }) => string | number
  dismiss: (id?: string | number) => string | number
}
