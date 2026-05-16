import type { Ref } from 'vue'
import { getAuthToken } from '~/lib/auth-storage'
import { useSseEventBus } from '~/composables/useSseEventBus'
import type {
  CapabilityProviderChangedEvent,
  CapabilityRegisteredEvent,
  CapabilityUnregisteredEvent,
} from '~/types/events'

export interface CapabilityBinding {
  capabilityId: string
  version: string
  moduleId: string
}

interface CapabilitiesResponse {
  bindings?: CapabilityBinding[]
}

const CAPABILITY_EVENT_TYPES = [
  'CAPABILITY_REGISTERED',
  'CAPABILITY_UNREGISTERED',
  'CAPABILITY_PROVIDER_CHANGED',
]

/**
 * Reactive view onto the controller's capability registry for one
 * `capabilityId`. Returns a `Ref` whose value is the active binding (if a
 * provider is registered) or `null` otherwise.
 *
 * Lifecycle:
 *  - Initial paint: GETs `/api/v1/modules/platform/capabilities` once and
 *    seeds the ref from the flat `bindings` array (which includes `@controller`
 *    built-ins, unlike the per-module view).
 *  - Live updates: subscribes to the global SSE bus's CAPABILITY_REGISTERED /
 *    CAPABILITY_UNREGISTERED / CAPABILITY_PROVIDER_CHANGED events and rewrites
 *    the ref when any of them name `capabilityId`.
 *  - Cleanup: unsubscribes on component unmount.
 */
export function useCapability(capabilityId: string): Ref<CapabilityBinding | null> {
  const binding = ref<CapabilityBinding | null>(null)

  if (!import.meta.client) return binding

  const config = useRuntimeConfig()
  const apiBase = config.public.apiBase as string

  // Seed from the registry. Errors are swallowed — the SSE stream will catch
  // up the next time a relevant event fires.
  void (async () => {
    const token = getAuthToken()
    if (!token) return
    try {
      const res = await $fetch<CapabilitiesResponse>(`${apiBase}/api/v1/modules/platform/capabilities`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      const found = (res.bindings ?? []).find((b) => b.capabilityId === capabilityId)
      binding.value = found ?? null
    } catch {
      // ignore — SSE will repair on the next event
    }
  })()

  const bus = useSseEventBus()
  const handler = (event: unknown) => {
    const e = event as
      | CapabilityRegisteredEvent
      | CapabilityUnregisteredEvent
      | CapabilityProviderChangedEvent
    if (e.capabilityId !== capabilityId) return
    if (e.type === 'CAPABILITY_REGISTERED') {
      binding.value = { capabilityId, version: e.version, moduleId: e.moduleId }
    } else if (e.type === 'CAPABILITY_UNREGISTERED') {
      binding.value = null
    } else if (e.type === 'CAPABILITY_PROVIDER_CHANGED') {
      binding.value = { capabilityId, version: e.toVersion, moduleId: e.moduleId }
    }
  }
  bus.on(CAPABILITY_EVENT_TYPES, handler)
  bus.connect()

  if (getCurrentInstance()) {
    onUnmounted(() => bus.off(CAPABILITY_EVENT_TYPES, handler))
  }

  return binding
}
