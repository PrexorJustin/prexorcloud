import { defineStore } from "pinia"
import type { Schema } from "@prexorcloud/api-sdk"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

type AuditEntry = Schema<'AuditEntry'>

export const useAuditStore = defineStore("audit", () => {
  const entries = ref<AuditEntry[]>([])
  const loading = ref(false)
  const offset = ref(0)
  const pageSize = 50
  const hasMore = ref(true)

  async function fetchEntries(newOffset = 0) {
    loading.value = true
    try {
      const page = Math.floor(newOffset / pageSize) + 1
      const { data: res } = await useApiClient().GET('/api/v1/audit', {
        params: { query: { page, pageSize } },
      })
      entries.value = (res?.data ?? []) as AuditEntry[]
      offset.value = newOffset
      hasMore.value = (page * pageSize) < (res?.total ?? 0)
    }
    catch { toast.error(t("store.audit.loadFailed")) }
    finally {
      loading.value = false
    }
  }

  return {
    entries,
    loading,
    offset,
    pageSize,
    hasMore,
    fetchEntries,
  }
})
