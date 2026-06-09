export interface BaseEvent { type: string; timestamp: string; sequence?: number }

export interface NodeConnectedEvent extends BaseEvent { type: 'NODE_CONNECTED'; nodeId: string; sessionId: string }
export interface NodeDisconnectedEvent extends BaseEvent { type: 'NODE_DISCONNECTED'; nodeId: string }
export interface NodeStatusEvent extends BaseEvent { type: 'NODE_STATUS'; nodeId: string; cpuUsage: number; usedMemoryMb: number; totalMemoryMb: number; lastHeartbeatAt: string | null }
export interface NodeHeartbeatStaleEvent extends BaseEvent { type: 'NODE_HEARTBEAT_STALE'; nodeId: string; missedPongs: number; lastHeartbeatAt: string | null }
export interface NodeHeartbeatResumedEvent extends BaseEvent { type: 'NODE_HEARTBEAT_RESUMED'; nodeId: string; lastHeartbeatAt: string | null }
export interface InstanceScheduledEvent extends BaseEvent { type: 'INSTANCE_SCHEDULED'; instanceId: string; group: string; node: string }
export interface InstanceStartedEvent extends BaseEvent { type: 'INSTANCE_STARTED'; instanceId: string }
export interface InstanceStoppedEvent extends BaseEvent { type: 'INSTANCE_STOPPED'; instanceId: string }
export interface InstanceCrashedEvent extends BaseEvent { type: 'INSTANCE_CRASHED'; instanceId: string; classification: string; exitCode: number }
export interface InstanceStateChangedEvent extends BaseEvent { type: 'INSTANCE_STATE_CHANGED'; instanceId: string; group: string; nodeId: string; oldState: string; newState: string }
export interface InstanceMetricsWorldSnapshot {
  name: string
  environment: string
  entityCount: number
  chunkCount: number
  playerCount: number
}
export interface InstanceMetricsEvent extends BaseEvent {
  type: 'INSTANCE_METRICS'
  instanceId: string
  group: string
  tps1m: number
  tps5m: number
  tps15m: number
  msptAvg: number
  heapUsedMb: number
  heapMaxMb: number
  gcCollections: number
  gcTimeMs: number
  threadCount: number
  playerCount: number
  maxPlayers: number
  worldCount: number
  totalEntities: number
  totalChunks: number
  worlds: InstanceMetricsWorldSnapshot[]
  serverVersion: string
  pluginCount: number
}
export interface PlayerConnectedEvent extends BaseEvent { type: 'PLAYER_CONNECTED'; uuid: string; name: string; instanceId: string; group: string }
export interface PlayerDisconnectedEvent extends BaseEvent { type: 'PLAYER_DISCONNECTED'; uuid: string; name: string; instanceId: string; group: string }
export interface GroupCreatedEvent extends BaseEvent { type: 'GROUP_CREATED'; groupName: string }
export interface GroupUpdatedEvent extends BaseEvent { type: 'GROUP_UPDATED'; groupName: string }
export interface GroupDeletedEvent extends BaseEvent { type: 'GROUP_DELETED'; groupName: string }
export interface GroupCrashLoopEvent extends BaseEvent { type: 'GROUP_CRASH_LOOP'; group: string; crashCount: number; windowStart: string }
export interface GroupAggregatesUpdatedEvent extends BaseEvent { type: 'GROUP_AGGREGATES_UPDATED'; groupName: string; runningInstances: number; totalPlayers: number }
export interface TemplateUpdatedEvent extends BaseEvent { type: 'TEMPLATE_UPDATED'; templateName: string; oldHash: string; newHash: string }
export interface MetricsUpdateEvent extends BaseEvent { type: 'METRICS_UPDATE' }
export interface DeploymentCreatedEvent extends BaseEvent { type: 'DEPLOYMENT_CREATED'; groupName: string; revision: number }
export interface DeploymentCompletedEvent extends BaseEvent { type: 'DEPLOYMENT_COMPLETED'; groupName: string; revision: number }
export interface DeploymentRolledBackEvent extends BaseEvent { type: 'DEPLOYMENT_ROLLED_BACK'; groupName: string; revision: number }
export interface NodeCacheStatusEvent extends BaseEvent { type: 'NODE_CACHE_STATUS'; nodeId: string }
export interface NodeDrainRequestedEvent extends BaseEvent { type: 'NODE_DRAIN_REQUESTED'; nodeId: string }
export interface NodeDrainCompletedEvent extends BaseEvent { type: 'NODE_DRAIN_COMPLETED'; nodeId: string }
export interface ModuleLoadedEvent extends BaseEvent { type: 'MODULE_LOADED'; moduleName: string; hasFrontend: boolean }
export interface ModuleUnloadedEvent extends BaseEvent { type: 'MODULE_UNLOADED'; moduleName: string }
export interface ModuleFrontendReloadedEvent extends BaseEvent { type: 'MODULE_FRONTEND_RELOADED'; moduleName: string; contentHash: string }
export interface MaintenanceUpdatedEvent extends BaseEvent { type: 'MAINTENANCE_UPDATED'; globalEnabled: boolean; message: string }
export interface CapabilityRegisteredEvent extends BaseEvent { type: 'CAPABILITY_REGISTERED'; capabilityId: string; version: string; moduleId: string }
export interface CapabilityUnregisteredEvent extends BaseEvent { type: 'CAPABILITY_UNREGISTERED'; capabilityId: string; moduleId: string }
export interface CapabilityProviderChangedEvent extends BaseEvent { type: 'CAPABILITY_PROVIDER_CHANGED'; capabilityId: string; moduleId: string; fromVersion: string; toVersion: string }
export interface ResyncRequiredEvent extends BaseEvent {
  type: 'RESYNC_REQUIRED'
  lastSequence: number
  earliestSequence: number
  latestSequence: number
}
export interface CustomCloudEvent extends BaseEvent {
  type: `${string}:${string}`
  source: string
  payload: Record<string, unknown>
}

export type CloudEvent =
  | NodeConnectedEvent | NodeDisconnectedEvent | NodeStatusEvent
  | NodeHeartbeatStaleEvent | NodeHeartbeatResumedEvent
  | NodeCacheStatusEvent | NodeDrainRequestedEvent | NodeDrainCompletedEvent
  | InstanceScheduledEvent | InstanceStartedEvent | InstanceStoppedEvent
  | InstanceCrashedEvent | InstanceStateChangedEvent | InstanceMetricsEvent
  | PlayerConnectedEvent | PlayerDisconnectedEvent
  | GroupCreatedEvent | GroupUpdatedEvent | GroupDeletedEvent | GroupCrashLoopEvent | GroupAggregatesUpdatedEvent
  | TemplateUpdatedEvent | MetricsUpdateEvent
  | DeploymentCreatedEvent | DeploymentCompletedEvent | DeploymentRolledBackEvent
  | ModuleLoadedEvent | ModuleUnloadedEvent | ModuleFrontendReloadedEvent
  | MaintenanceUpdatedEvent
  | CapabilityRegisteredEvent | CapabilityUnregisteredEvent | CapabilityProviderChangedEvent
  | ResyncRequiredEvent
  | CustomCloudEvent
