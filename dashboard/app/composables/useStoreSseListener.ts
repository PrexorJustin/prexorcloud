import type { CloudEvent } from '~/types/events'

/**
 * Wires a store's SSE event handler to the centralized {@link useSseEventBus}
 * and exposes idempotent connect/disconnect functions. Replaces the
 * duplicated `sseConnected` flag + `bus.on(...)` / `bus.off(...)` pattern
 * that lived in nodes/instances/groups stores.
 *
 * Usage inside a Pinia store:
 * ```ts
 * const sse = useStoreSseListener(
 *   ['NODE_STATUS', 'NODE_CONNECTED'],
 *   handleEvent,
 * )
 * return { connectSse: sse.connect, disconnectSse: sse.disconnect, ... }
 * ```
 */
export function useStoreSseListener(eventTypes: readonly string[], handler: (event: CloudEvent) => void) {
  let connected = false

  function connect() {
    if (connected) return
    connected = true
    const bus = useSseEventBus()
    bus.on(eventTypes as string[], handler)
    bus.connect()
  }

  function disconnect() {
    if (!connected) return
    connected = false
    const bus = useSseEventBus()
    bus.off(eventTypes as string[], handler)
  }

  return { connect, disconnect }
}
