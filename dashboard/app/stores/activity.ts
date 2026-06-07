import { defineStore } from "pinia"
import { ref } from "vue"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

export interface ActivityEvent {
  id: string
  type: string
  /** Actor that triggered the event (username or system component). */
  actor?: string
  /** Display message — already humanized by the controller, or built client-side. */
  message: string
  /** Click-through target for the affected resource. */
  route?: string
  timestamp: string
}

type LooseClient = {
  GET: (path: string, init?: unknown) => Promise<{ data: unknown }>
}

/**
 * Historical event feed. Reads /events with pagination + accepts live SSE
 * events from the bus to prepend. The same source the notifications panel
 * watches, but here we keep the full timeline rather than sampling.
 */
export const useActivityStore = defineStore("activity", () => {
  const events = ref<ActivityEvent[]>([])
  const loading = ref(false)
  const offset = ref(0)
  const pageSize = 100
  const hasMore = ref(true)

  function loose(): LooseClient {
    return useApiClient() as unknown as LooseClient
  }

  async function fetchEvents(newOffset = 0) {
    loading.value = true
    try {
      const page = Math.floor(newOffset / pageSize) + 1
      const { data } = await loose().GET(`/api/v1/events?page=${page}&pageSize=${pageSize}`)
      const res = data as { data?: ActivityEvent[]; total?: number }
      const batch = res.data ?? []
      events.value = newOffset === 0 ? batch : events.value.concat(batch)
      offset.value = newOffset
      hasMore.value = (page * pageSize) < (res.total ?? 0)
    } catch {
      toast.error(t("store.activity.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loading.value = false
    }
  }

  async function loadMore() {
    if (loading.value || !hasMore.value) return
    await fetchEvents(offset.value + pageSize)
  }

  return { events, loading, offset, pageSize, hasMore, fetchEvents, loadMore }
})
