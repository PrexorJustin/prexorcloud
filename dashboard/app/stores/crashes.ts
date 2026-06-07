import { defineStore } from "pinia"
import type { Schema } from "@prexorcloud/api-sdk"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

type CrashRecord = Schema<'CrashSummaryDto'>
type CrashTrend = Schema<'CrashTrendDto'>

export const useCrashesStore = defineStore("crashes", () => {
  const crashes = ref<CrashRecord[]>([])
  const loading = ref(false)
  const offset = ref(0)
  const pageSize = 50
  const hasMore = ref(true)
  const trend = ref<CrashTrend | null>(null)
  const trendLoading = ref(false)

  async function fetchCrashes(newOffset = 0) {
    loading.value = true
    try {
      const page = Math.floor(newOffset / pageSize) + 1
      const { data: res } = await useApiClient().GET('/api/v1/crashes', {
        params: { query: { page, pageSize } },
      })
      crashes.value = res?.data ?? []
      offset.value = newOffset
      hasMore.value = (page * pageSize) < (res?.total ?? 0)
    }
    catch { toast.error(t("store.crashes.loadFailed")) }
    finally {
      loading.value = false
    }
  }

  async function fetchCrash(id: string): Promise<CrashRecord | null> {
    try {
      const { data } = await useApiClient().GET('/api/v1/crashes/{id}', { params: { path: { id } } })
      return (data ?? null) as CrashRecord | null
    }
    catch {
      return null
    }
  }

  async function shareCrash(id: string, opts: { expiry?: string; isPrivate?: boolean; burnAfterRead?: boolean } = {}) {
    try {
      const { data } = await (useApiClient() as unknown as {
        POST: (path: string, init: unknown) => Promise<{ data: unknown }>
      }).POST(`/api/v1/crashes/${encodeURIComponent(id)}/share`, { body: opts })
      const result = data as { url: string; deleteUrl?: string | null }
      const message = t("store.crashes.shareSuccess", { url: result.url })
      if (result.deleteUrl) {
        toast.success(message, { description: t("store.crashes.shareRevokeHint", { url: result.deleteUrl }) })
      } else {
        toast.success(message)
      }
      return result
    } catch (e) {
      const status = (e as { response?: { status?: number } })?.response?.status
      if (status === 409) toast.error(t("store.crashes.shareDisabled"))
      else toast.error(t("store.crashes.shareFailed"))
      return null
    }
  }

  async function fetchTrend(window = '24h', buckets = 24) {
    trendLoading.value = true
    try {
      const { data } = await useApiClient().GET('/api/v1/crashes/trends', {
        params: { query: { window, buckets } },
      })
      trend.value = (data ?? null) as CrashTrend | null
    }
    catch {
      trend.value = null
    }
    finally {
      trendLoading.value = false
    }
  }

  return {
    crashes,
    loading,
    offset,
    pageSize,
    hasMore,
    trend,
    trendLoading,
    fetchCrashes,
    fetchCrash,
    fetchTrend,
    shareCrash,
  }
})
