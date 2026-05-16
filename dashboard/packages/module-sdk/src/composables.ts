import type { MaybeRefOrGetter, Ref } from 'vue'
import type { ModuleApi, ModuleAuth, CloudEvent, CapabilityBinding, EventSubscription, ToastFn, PaginatedDataOptions, PaginatedDataReturn, FilterListOptions, FilteredListReturn } from './types'

const STUB_ERROR =
  '@prexorcloud/module-sdk: This function is a type stub and should not be called directly. ' +
  'It is replaced at runtime by the PrexorCloud dashboard. ' +
  'If you see this error, @prexorcloud/module-sdk was not properly externalized in your Vite config — ' +
  'make sure you are using @prexorcloud/module-vite-plugin.'

/**
 * Returns a scoped API client for the current module.
 * All requests are automatically prefixed with `/api/v1/modules/<moduleName>/`.
 */
export function useApi(): ModuleApi {
  throw new Error(STUB_ERROR)
}

/**
 * Subscribes to SSE events matching the given types.
 * Returns a handle to stop the subscription.
 */
export function useModuleEvents(
  eventTypes: string[],
  handler: (event: CloudEvent) => void,
): EventSubscription {
  throw new Error(STUB_ERROR)
}

/**
 * Returns the toast notification function (vue-sonner).
 */
export function useToast(): ToastFn {
  throw new Error(STUB_ERROR)
}

/**
 * Returns the current authentication state.
 */
export function useAuthStore(): ModuleAuth {
  throw new Error(STUB_ERROR)
}

/** @deprecated Use {@link useAuthStore} instead. */
export const useAuth = useAuthStore

/**
 * Returns a scoped API client for the given module name.
 *
 * Two forms:
 *   useScopedApi('player-journey')                        — direct module name
 *   useScopedApi({ capability: 'prexor.player.journey' }) — resolves at call time
 *     against /api/v1/modules/platform/capabilities so the client survives
 *     provider changes (e.g. capability migrating to a different module).
 */
export function useScopedApi(moduleName: string): ModuleApi
export function useScopedApi(target: { capability: string }): ModuleApi
export function useScopedApi(_target: string | { capability: string }): ModuleApi {
  throw new Error(STUB_ERROR)
}

/**
 * Returns a reactive ref to the currently active capability binding for
 * `capabilityId`, or `null` when no provider is registered.
 *
 * Re-subscribes to the controller's capability lifecycle SSE stream and
 * updates when the capability graph changes (provider activates, deactivates,
 * or rebinds). Performs an initial fetch against
 * GET /api/v1/modules/platform/capabilities so the value is non-null on first
 * paint when a provider is already present.
 */
export function useCapability(_capabilityId: string): Ref<CapabilityBinding | null> {
  throw new Error(STUB_ERROR)
}

/**
 * Paginated data fetcher for module APIs.
 */
export function usePaginatedData<T>(
  api: ReturnType<typeof useScopedApi>,
  endpoint: MaybeRefOrGetter<string>,
  options?: PaginatedDataOptions,
): PaginatedDataReturn<T> {
  throw new Error(STUB_ERROR)
}

/**
 * Client-side filtering and search for a reactive list.
 */
export function useFilteredList<T>(
  source: () => T[],
  options?: FilterListOptions,
): FilteredListReturn<T> {
  throw new Error(STUB_ERROR)
}

/**
 * Checks whether a module is installed and enabled.
 */
export function useModuleGuard(moduleName: string): { installed: { value: boolean }; enabled: { value: boolean } } {
  throw new Error(STUB_ERROR)
}

/**
 * Navigates to the given path.
 */
export function navigateTo(path: string): void {
  throw new Error(STUB_ERROR)
}

/**
 * Returns the current route object.
 */
export function useRoute(): any {
  throw new Error(STUB_ERROR)
}

/**
 * Returns the router instance.
 */
export function useRouter(): any {
  throw new Error(STUB_ERROR)
}

/**
 * Toast notification function (vue-sonner).
 */
export const toast: ToastFn = (() => { throw new Error(STUB_ERROR) }) as any
