import type { CloudEvent } from '~/types/events'
import { getAuthToken } from '~/lib/auth-storage'

export type SseEvent = CloudEvent

type SseHandler = (event: SseEvent) => void

const MAX_BACKOFF_MS = 30_000
const HEARTBEAT_TIMEOUT_MS = 90_000
const LAST_SEQUENCE_KEY = 'prexorcloud:sse:last-sequence'

/**
 * Centralized SSE event bus. Opens a single EventSource connection shared
 * across all stores and components. Uses ticket-based auth (short-lived,
 * single-use tokens obtained via POST) instead of JWT in the URL.
 *
 * Features:
 * - Single connection for entire app (no duplicate EventSources)
 * - Ticket-based auth (JWT never appears in URL)
 * - Exponential backoff on reconnect (1s -> 2s -> 4s -> ... -> 30s max)
 * - Auto-disconnect on logout (watches auth token)
 * - Heartbeat detection (reconnect if no event for 90s)
 * - Last-seen sequence replay on reconnect
 */
export function useSseEventBus() {
  // Singleton state — survives across composable calls
  const state = useSseEventBusState()
  return {
    /** Register a handler for one or more event types */
    on(types: string | string[], handler: SseHandler) {
      const typeList = Array.isArray(types) ? types : [types]
      for (const type of typeList) {
        if (!state.handlers.has(type)) state.handlers.set(type, new Set())
        state.handlers.get(type)!.add(handler)
      }
    },
    /** Remove a handler */
    off(types: string | string[], handler: SseHandler) {
      const typeList = Array.isArray(types) ? types : [types]
      for (const type of typeList) {
        state.handlers.get(type)?.delete(handler)
      }
    },
    /** Start the SSE connection (idempotent) */
    connect: () => state.connect(),
    /** Stop the SSE connection */
    disconnect: () => state.disconnect(),
    /** Whether the SSE connection is currently open */
    connected: computed(() => state.connected.value),
    /** Last successfully processed controller event sequence */
    lastSequence: computed(() => state.lastSequence.value),
  }
}

// --- Singleton state (module-level, shared across all useSseEventBus() calls) ---

function useSseEventBusState() {
  // Use useState to ensure singleton across SSR/client boundary
  if (!import.meta.client) {
    // SSR: return no-op
    return {
      handlers: new Map<string, Set<SseHandler>>(),
      connected: ref(false),
      lastSequence: ref(0),
      connect: () => {},
      disconnect: () => {},
    }
  }

  // Client-side singleton
  if (!window.__sseEventBus) {
    const handlers = new Map<string, Set<SseHandler>>()
    const connected = ref(false)
    const lastSequence = ref(readLastSequence())
    let eventSource: EventSource | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    let heartbeatTimer: ReturnType<typeof setTimeout> | null = null
    let backoffMs = 1000

    function resetHeartbeat() {
      if (heartbeatTimer) clearTimeout(heartbeatTimer)
      heartbeatTimer = setTimeout(() => {
        console.warn('[SSE] No events received for 90s - reconnecting')
        reconnect()
      }, HEARTBEAT_TIMEOUT_MS)
    }

    function dispatch(event: SseEvent) {
      // Dispatch to type-specific handlers
      const typeHandlers = handlers.get(event.type)
      if (typeHandlers) {
        for (const handler of typeHandlers) {
          try { handler(event) } catch (e) { console.error('[SSE] Handler error:', e) }
        }
      }
      // Dispatch to wildcard handlers
      const wildcardHandlers = handlers.get('*')
      if (wildcardHandlers) {
        for (const handler of wildcardHandlers) {
          try { handler(event) } catch (e) { console.error('[SSE] Wildcard handler error:', e) }
        }
      }
    }

    function persistSequence(sequence: number) {
      if (!Number.isFinite(sequence) || sequence <= lastSequence.value) return
      lastSequence.value = sequence
      localStorage.setItem(LAST_SEQUENCE_KEY, String(sequence))
    }

    async function connect() {
      if (eventSource || reconnectTimer) return

      const token = getAuthToken()
      if (!token) return

      const config = useRuntimeConfig()
      const apiBase = config.public.apiBase as string

      // Obtain a short-lived SSE ticket via authenticated POST. There is deliberately no
      // token-in-URL fallback: the controller only accepts a ticket, so falling back to
      // ?token=<JWT> never authenticated — it just leaked the JWT into the URL (browser
      // history / proxy logs) and reconnected forever. On failure, back off and retry the
      // ticket. (audit F-F3)
      let ticket: string
      try {
        const res = await $fetch<{ ticket: string }>(`${apiBase}/api/v1/events/ticket`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}` },
        })
        ticket = res.ticket
      } catch (err) {
        console.warn('[SSE] Failed to obtain ticket, retrying with backoff:', err)
        reconnect()
        return
      }

      const params = new URLSearchParams()
      params.set('ticket', ticket)
      if (lastSequence.value > 0) {
        params.set('lastSequence', String(lastSequence.value))
      }

      const url = `${apiBase}/api/v1/events/stream?${params.toString()}`

      eventSource = new EventSource(url)
      connected.value = true
      backoffMs = 1000 // Reset backoff on successful connect attempt

      eventSource.onmessage = (event) => {
        resetHeartbeat()
        try {
          const data = JSON.parse(event.data) as SseEvent
          const sequence = typeof data.sequence === 'number'
            ? data.sequence
            : Number.parseInt(event.lastEventId, 10)
          persistSequence(sequence)
          if (data.type) dispatch(data)
        } catch { /* ignore parse errors */ }
      }

      eventSource.onopen = () => {
        connected.value = true
        backoffMs = 1000
      }

      eventSource.onerror = () => {
        reconnect()
      }
    }

    function reconnect() {
      disconnect()
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null
        connect()
      }, backoffMs)
      backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS)
    }

    function disconnect() {
      if (heartbeatTimer) { clearTimeout(heartbeatTimer); heartbeatTimer = null }
      if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
      if (eventSource) { eventSource.close(); eventSource = null }
      connected.value = false
    }

    // Auto-disconnect on logout: watch for token removal
    const checkInterval = setInterval(() => {
      const token = getAuthToken()
      if (!token && eventSource) {
        disconnect()
      }
    }, 2000)

    // Clean up on page unload
    window.addEventListener('beforeunload', () => {
      clearInterval(checkInterval)
      disconnect()
    })

    window.__sseEventBus = { handlers, connected, lastSequence, connect, disconnect }
  }

  return window.__sseEventBus!
}

function readLastSequence() {
  const value = localStorage.getItem(LAST_SEQUENCE_KEY)
  if (!value) return 0
  const parsed = Number.parseInt(value, 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
}
