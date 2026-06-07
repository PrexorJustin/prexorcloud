import { defineStore } from "pinia"
import { computed, ref } from "vue"
import type { Schema } from "@prexorcloud/api-sdk"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

type AuditEntry = Schema<'AuditEntry'>

// The audit log uses keyset/seek pagination (controller H.1): each page carries a
// `nextCursor` token that the next request passes back as `?cursor=`. This keeps
// deep pages flat-cost on the backend (no skip(offset)). The `cursor` query param
// and `nextCursor` field aren't in the SDK snapshot yet, so the call goes through
// a loosely-typed client — the entry shape itself stays SDK-typed.
type AuditSeekResponse = { data?: AuditEntry[]; total?: number; nextCursor?: string | null }
type LooseClient = {
  GET: (path: string, init?: { params?: { query?: Record<string, unknown> } }) => Promise<{ data?: AuditSeekResponse }>
}

export const useAuditStore = defineStore("audit", () => {
  const entries = ref<AuditEntry[]>([])
  const loading = ref(false)
  const total = ref(0)
  const pageSize = 50

  // Cursor stack: pageCursors[k] is the cursor that produced page k. Page 0 is the
  // newest page (empty cursor). "Previous" pops; "Next" pushes the live nextCursor.
  const pageCursors = ref<string[]>([""])
  const nextCursor = ref<string | null>(null)

  const offset = computed(() => (pageCursors.value.length - 1) * pageSize)
  const hasMore = computed(() => nextCursor.value != null)
  const canPrev = computed(() => pageCursors.value.length > 1)

  async function load(cursor: string) {
    loading.value = true
    try {
      const client = useApiClient() as unknown as LooseClient
      const { data: res } = await client.GET('/api/v1/audit', {
        params: { query: { cursor, pageSize } },
      })
      entries.value = (res?.data ?? []) as AuditEntry[]
      total.value = res?.total ?? 0
      nextCursor.value = res?.nextCursor ?? null
    }
    catch { toast.error(t("store.audit.loadFailed")) }
    finally {
      loading.value = false
    }
  }

  /** (Re)load from the newest page, resetting the cursor stack. */
  async function fetchEntries() {
    pageCursors.value = [""]
    await load("")
  }

  async function nextPage() {
    if (nextCursor.value == null) return
    pageCursors.value.push(nextCursor.value)
    await load(nextCursor.value)
  }

  async function prevPage() {
    if (pageCursors.value.length <= 1) return
    pageCursors.value.pop()
    await load(pageCursors.value[pageCursors.value.length - 1]!)
  }

  return {
    entries,
    loading,
    total,
    offset,
    pageSize,
    hasMore,
    canPrev,
    fetchEntries,
    nextPage,
    prevPage,
  }
})
