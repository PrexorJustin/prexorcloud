import type { DefineModuleOptions } from './types'

/**
 * Defines a module's component exports for the PrexorCloud dashboard.
 * Call this once at the top level of your module's entry file.
 *
 * @example
 * ```ts
 * import { defineModule } from '@prexorcloud/module-sdk'
 * import SignListPage from './pages/SignListPage.vue'
 * import SignEditorPage from './pages/SignEditorPage.vue'
 *
 * defineModule({
 *   components: { SignListPage, SignEditorPage },
 * })
 * ```
 */
export function defineModule(options: DefineModuleOptions): DefineModuleOptions {
  return options
}
