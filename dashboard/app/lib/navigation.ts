import {
  Activity,
  AlertTriangle,
  BadgeCheck,
  Box,
  Database,
  FileCode,
  FileText,
  Gauge,
  HeartPulse,
  KeyRound,
  Layers,
  Network,
  Package,
  PackageSearch,
  Puzzle,
  Rocket,
  ScrollText,
  Server,
  Share2,
  Settings,
  Shield,
  Ticket,
  Users,
  UsersRound,
  Workflow,
  Wrench,
} from 'lucide-vue-next'
import type { NavGroup } from '~/types/navigation'

/**
 * Sidebar IA — Kubernetes-mental-model: Overview · Workloads · Cluster ·
 * Configuration · Observability · Identity · Operations · Settings.
 *
 * Items routing to pages that don't exist yet (Deployments, Players, Activity,
 * Logs, System status, Users, Roles, Tokens, Workload credentials,
 * Certificates, Backups, Maintenance) are intentional stubs — they show up in
 * the sidebar so the IA is testable end-to-end before each page lands.
 *
 * Permission gating uses `auth.can(permission)`; falsy means always visible.
 * Runtime modules inject extra items via `addNavItem` (see AppSidebar.vue) and
 * default to `Modules` when no `navGroup` is provided.
 *
 * `label`/`title` hold the English copy and are used for module `navGroup`
 * matching; `labelKey`/`titleKey` carry the i18n key resolved at render time.
 */
export const navigation: NavGroup[] = [
  {
    id: 1,
    label: 'Overview',
    labelKey: 'nav.groups.overview',
    sortOrder: 10,
    items: [
      { title: 'Dashboard', titleKey: 'nav.items.dashboard', url: '/',    icon: Gauge },
      { title: 'Map',       titleKey: 'nav.items.map',       url: '/map', icon: Workflow },
    ],
  },
  {
    id: 2,
    label: 'Workloads',
    labelKey: 'nav.groups.workloads',
    sortOrder: 20,
    items: [
      { title: 'Groups',      titleKey: 'nav.items.groups',      url: '/groups',                 icon: Layers },
      { title: 'Instances',   titleKey: 'nav.items.instances',   url: '/instances',              icon: Box },
      { title: 'Deployments', titleKey: 'nav.items.deployments', url: '/workloads/deployments',  icon: Rocket },
    ],
  },
  {
    id: 3,
    label: 'Cluster',
    labelKey: 'nav.groups.cluster',
    sortOrder: 30,
    items: [
      { title: 'Controllers', titleKey: 'nav.items.controllers', url: '/cluster/controllers', icon: HeartPulse, permission: 'cluster.view' },
      { title: 'Nodes',       titleKey: 'nav.items.nodes',       url: '/nodes',               icon: Server },
      { title: 'Networks',    titleKey: 'nav.items.networks',    url: '/networks',            icon: Network, permission: 'networks.view' },
      { title: 'Players',     titleKey: 'nav.items.players',     url: '/cluster/players',     icon: UsersRound },
    ],
  },
  {
    id: 4,
    label: 'Configuration',
    labelKey: 'nav.groups.configuration',
    sortOrder: 40,
    items: [
      { title: 'Templates', titleKey: 'nav.items.templates', url: '/templates', icon: FileCode },
      { title: 'Catalog',   titleKey: 'nav.items.catalog',   url: '/catalog',   icon: Package },
      { title: 'Modules',   titleKey: 'nav.items.modules',   url: '/modules',          icon: Puzzle },
      { title: 'Registry',  titleKey: 'nav.items.moduleRegistry', url: '/modules/registry', icon: PackageSearch, permission: 'modules.view' },
    ],
  },
  {
    id: 5,
    label: 'Observability',
    labelKey: 'nav.groups.observability',
    sortOrder: 50,
    items: [
      { title: 'Crashes',       titleKey: 'nav.items.crashes',      url: '/crashes',                icon: AlertTriangle },
      { title: 'Activity',      titleKey: 'nav.items.activity',     url: '/observability/activity', icon: Activity },
      { title: 'Logs',          titleKey: 'nav.items.logs',         url: '/observability/logs',     icon: FileText },
      { title: 'System status', titleKey: 'nav.items.systemStatus', url: '/observability/system',   icon: HeartPulse },
      { title: 'Audit',         titleKey: 'nav.items.audit',        url: '/audit',                  icon: ScrollText, permission: 'audit.view' },
      { title: 'Shares',        titleKey: 'nav.items.shares',       url: '/shares',                 icon: Share2,     permission: 'share.revoke' },
    ],
  },
  {
    id: 6,
    label: 'Identity',
    labelKey: 'nav.groups.identity',
    sortOrder: 60,
    items: [
      { title: 'Users',                titleKey: 'nav.items.users',               url: '/users',                  icon: Users,      permission: 'users.view' },
      { title: 'Roles',                titleKey: 'nav.items.roles',               url: '/roles',                  icon: Shield,     permission: 'roles.view' },
      { title: 'Tokens',               titleKey: 'nav.items.tokens',              url: '/tokens',                 icon: Ticket,     permission: 'tokens.view' },
      { title: 'Workload credentials', titleKey: 'nav.items.workloadCredentials', url: '/workloads/credentials',  icon: KeyRound,   permission: 'credentials.view' },
      { title: 'Certificates',         titleKey: 'nav.items.certificates',        url: '/identity/certificates',  icon: BadgeCheck, permission: 'nodes.edit' },
    ],
  },
  {
    id: 7,
    label: 'Operations',
    labelKey: 'nav.groups.operations',
    sortOrder: 70,
    items: [
      { title: 'Backups',     titleKey: 'nav.items.backups',     url: '/operations/backups',     icon: Database, permission: 'backups.view' },
      { title: 'Maintenance', titleKey: 'nav.items.maintenance', url: '/operations/maintenance', icon: Wrench,   permission: 'maintenance.edit' },
    ],
  },
  {
    id: 8,
    label: 'Settings',
    labelKey: 'nav.groups.settings',
    sortOrder: 90,
    items: [
      { title: 'Settings', titleKey: 'nav.items.settings', url: '/settings', icon: Settings },
    ],
  },
]
