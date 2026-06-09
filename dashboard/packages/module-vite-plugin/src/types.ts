export interface ModuleRoute {
  /** Route path pattern, e.g. `/signs/:id` */
  path: string
  /** Name of the exported Vue component */
  component: string
  /** Display title for navigation */
  title: string
  /** Lucide icon name, or null */
  icon?: string | null
  /** Whether to show in the sidebar navigation */
  nav?: boolean
  /** Sidebar navigation group name */
  navGroup?: string | null
  /** Permission required to access this route (e.g. 'modules.manage'). Preferred over adminOnly. */
  permission?: string | null
  /** @deprecated Use `permission` instead */
  adminOnly?: boolean
}

export interface PrexorModulePluginOptions {
  /** Module identifier (e.g. `sign-editor`) — must match the backend module name */
  name: string
  /** Human-readable display name */
  displayName: string
  /** Lucide icon name for the module */
  icon?: string | null
  /** Permissions required to access the module frontend */
  permissions?: string[]
  /** Route definitions for the module's pages */
  routes: ModuleRoute[]
  /** SSE event types this module subscribes to */
  events?: string[]
  /** Entry file path (default: `src/main.ts`) */
  entry?: string
}
