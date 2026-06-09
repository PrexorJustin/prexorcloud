/**
 * Reactive permission check composable.
 *
 * Usage:
 *   const { can, canAny } = useCan()
 *   v-if="can('audit.view')"
 *   v-if="canAny('users.create', 'users.update')"
 */
export function useCan() {
  const auth = useAuthStore()
  return {
    can: (permission: string) => auth.can(permission),
    canAny: (...permissions: string[]) => auth.canAny(...permissions),
  }
}
