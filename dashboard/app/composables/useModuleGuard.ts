/**
 * Checks whether a module is installed and enabled.
 * Use in module pages to conditionally render content or show "not installed" state.
 */
export function useModuleGuard(moduleName: string) {
  const moduleStore = useModuleStore()

  const installed = computed(() =>
    moduleStore.modules.some(m => m.name === moduleName)
    || moduleStore.platformModules.some(m => m.moduleId === moduleName),
  )

  const enabled = computed(() =>
    moduleStore.modules.some(m => m.name === moduleName && m.enabled)
    || moduleStore.platformModules.some(m => m.moduleId === moduleName && m.state === 'ACTIVE'),
  )

  return { installed, enabled }
}
