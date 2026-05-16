import type { Component } from 'vue'

export interface NavItem {
  title: string
  /** i18n key resolved at render time; falls back to `title` when absent (module-injected items). */
  titleKey?: string
  url: string
  icon?: Component
  /** Permission required to see this item (e.g. 'audit.view'). Replaces adminOnly. */
  permission?: string
  badge?: () => number | string | undefined
}

export interface NavGroup {
  id: number
  /** English name — used for module `navGroup` matching; not shown directly. */
  label: string
  /** i18n key resolved at render time; falls back to `label` when absent. */
  labelKey?: string
  sortOrder: number
  items: NavItem[]
}
