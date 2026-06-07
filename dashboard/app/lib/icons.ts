import type { Component } from 'vue'
import * as LucideIcons from 'lucide-vue-next'

const iconMap = new Map<string, Component>()

for (const [name, component] of Object.entries(LucideIcons)) {
  // Only include actual Vue components (objects/functions), not type exports
  if (typeof component === 'object' || typeof component === 'function') {
    iconMap.set(name, component as Component)
  }
}

/**
 * Resolves a lucide icon name string (e.g. "PenTool") to its Vue component.
 * Used by the module system to map icon names from module manifests to components.
 */
export function resolveIcon(name?: string | null): Component | undefined {
  if (!name) return undefined
  return iconMap.get(name)
}
