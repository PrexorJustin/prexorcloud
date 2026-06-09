export const ROLES = {
  ADMIN: 'ADMIN',
  OPERATOR: 'OPERATOR',
  VIEWER: 'VIEWER',
} as const

/** Built-in roles are typed; custom roles are any string */
export type Role = (typeof ROLES)[keyof typeof ROLES] | (string & {})

export const NODE_STATES = {
  ONLINE: 'ONLINE',
  DRAINING: 'DRAINING',
  CORDONED: 'CORDONED',
  UNREACHABLE: 'UNREACHABLE',
} as const

export const INSTANCE_STATES = {
  SCHEDULED: 'SCHEDULED',
  STARTING: 'STARTING',
  RUNNING: 'RUNNING',
  STOPPING: 'STOPPING',
  STOPPED: 'STOPPED',
  CRASHED: 'CRASHED',
} as const

export const CRASH_CLASSIFICATIONS = {
  OOM: 'OOM',
  ERROR: 'ERROR',
  SIGKILL: 'SIGKILL',
  SIGTERM: 'SIGTERM',
} as const

export const PERMISSION_GROUPS: Record<string, { label: string; permissions: string[] }> = {
  nodes: { label: 'Nodes', permissions: ['nodes.view', 'nodes.drain'] },
  groups: { label: 'Groups', permissions: ['groups.view', 'groups.create', 'groups.update', 'groups.delete', 'groups.start'] },
  instances: { label: 'Instances', permissions: ['instances.view', 'instances.stop', 'instances.command', 'instances.console'] },
  networks: { label: 'Networks', permissions: ['networks.view', 'networks.create', 'networks.update', 'networks.delete'] },
  templates: { label: 'Templates', permissions: ['templates.view', 'templates.create', 'templates.update', 'templates.delete'] },
  crashes: { label: 'Crashes', permissions: ['crashes.view'] },
  tokens: { label: 'Tokens', permissions: ['tokens.view', 'tokens.create', 'tokens.revoke'] },
  users: { label: 'Users', permissions: ['users.view', 'users.create', 'users.update', 'users.delete'] },
  roles: { label: 'Roles', permissions: ['roles.view', 'roles.manage'] },
  modules: { label: 'Modules', permissions: ['modules.view', 'modules.manage'] },
  catalog: { label: 'Catalog', permissions: ['catalog.view', 'catalog.manage'] },
  audit: { label: 'Audit', permissions: ['audit.view'] },
  system: { label: 'System', permissions: ['system.settings'] },
  metrics: { label: 'Metrics', permissions: ['metrics.view'] },
  events: { label: 'Events', permissions: ['events.stream'] },
} as const

export const ALL_PERMISSIONS = Object.values(PERMISSION_GROUPS).flatMap(g => g.permissions)

// UI style configs for status displays
export interface StatusStyle { label: string; color: string; dot: string; bg: string }

export const NODE_STATUS_CONFIG: Record<string, StatusStyle> = {
  ONLINE: { label: "Online", color: "text-success", dot: "bg-success", bg: "bg-success/20" },
  DRAINING: { label: "Draining", color: "text-warning", dot: "bg-warning", bg: "bg-warning/20" },
  CORDONED: { label: "Cordoned", color: "text-warning", dot: "bg-warning", bg: "bg-warning/20" },
  UNREACHABLE: { label: "Unreachable", color: "text-destructive", dot: "bg-destructive", bg: "bg-destructive/20" },
  OFFLINE: { label: "Offline", color: "text-muted-foreground", dot: "bg-muted-foreground", bg: "bg-muted/20" },
  PENDING: { label: "Pending", color: "text-primary", dot: "bg-primary", bg: "bg-primary/20" },
}

export const INSTANCE_STATE_CONFIG: Record<string, StatusStyle> = {
  RUNNING: { label: "Running", color: "text-success", dot: "bg-success", bg: "bg-success/20" },
  STARTING: { label: "Starting", color: "text-primary", dot: "bg-primary", bg: "bg-primary/20" },
  SCHEDULED: { label: "Scheduled", color: "text-primary", dot: "bg-primary", bg: "bg-primary/20" },
  STOPPING: { label: "Stopping", color: "text-warning", dot: "bg-warning", bg: "bg-warning/20" },
  STOPPED: { label: "Stopped", color: "text-muted-foreground", dot: "bg-muted-foreground", bg: "bg-muted/20" },
  CRASHED: { label: "Crashed", color: "text-destructive", dot: "bg-destructive", bg: "bg-destructive/20" },
  DRAINING: { label: "Draining", color: "text-warning", dot: "bg-warning", bg: "bg-warning/20" },
}

export const SCALING_MODE_CONFIG: Record<string, { label: string; color: string }> = {
  DYNAMIC: { label: "Dynamic", color: "text-success" },
  STATIC: { label: "Static", color: "text-primary" },
  MANUAL: { label: "Manual", color: "text-warning" },
}

export const DEPLOY_STATE_CONFIG: Record<string, { label: string; color: string }> = {
  PENDING: { label: "Pending", color: "text-muted-foreground" },
  IN_PROGRESS: { label: "In Progress", color: "text-primary" },
  PAUSED: { label: "Paused", color: "text-warning" },
  COMPLETED: { label: "Completed", color: "text-success" },
  FAILED: { label: "Failed", color: "text-destructive" },
  ROLLED_BACK: { label: "Rolled Back", color: "text-warning" },
}

export const EVENT_TYPES = {
  NODE_CONNECTED: 'NODE_CONNECTED',
  NODE_DISCONNECTED: 'NODE_DISCONNECTED',
  NODE_STATUS: 'NODE_STATUS',
  INSTANCE_SCHEDULED: 'INSTANCE_SCHEDULED',
  INSTANCE_STARTED: 'INSTANCE_STARTED',
  INSTANCE_STOPPED: 'INSTANCE_STOPPED',
  INSTANCE_CRASHED: 'INSTANCE_CRASHED',
  PLAYER_CONNECTED: 'PLAYER_CONNECTED',
  PLAYER_DISCONNECTED: 'PLAYER_DISCONNECTED',
  GROUP_CREATED: 'GROUP_CREATED',
  GROUP_UPDATED: 'GROUP_UPDATED',
  GROUP_DELETED: 'GROUP_DELETED',
  GROUP_CRASH_LOOP: 'GROUP_CRASH_LOOP',
  METRICS_UPDATE: 'METRICS_UPDATE',
} as const
