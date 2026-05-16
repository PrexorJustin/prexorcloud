import { defineStore } from "pinia"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

export interface ShareRequest {
  expiry?: string
  isPrivate?: boolean
  burnAfterRead?: boolean
}

export interface ShareLogRequest extends ShareRequest {
  level?: string
  logger?: string
  limit?: number
}

export interface ShareResult {
  shareId: string
  url: string
  rawUrl: string
  expiresAt?: string
  isPrivate: boolean
  burnAfterRead: boolean
  deleteToken?: string | null
  deleteUrl?: string | null
}

export interface ShareRecord {
  id: string
  kind: "CRASH" | "CONTROLLER_LOGS" | "DAEMON_LOGS" | "DIAGNOSTICS" | "INSTANCE_CONSOLE"
  resourceId: string | null
  url: string
  rawUrl: string
  expiresAt?: string | null
  burnAfterRead: boolean
  isPrivate: boolean
  sizeBytes: number
  sharedByUser: string
  sharedAt: string
  revokedAt?: string | null
  revocable: boolean
}

export interface ShareListPage {
  data: ShareRecord[]
  total: number
  page: number
  pageSize: number
}

type LooseClient = {
  POST: (path: string, init?: unknown) => Promise<{ data: unknown; error?: unknown; response: { status: number } }>
  GET: (path: string, init?: unknown) => Promise<{ data: unknown; error?: unknown; response: { status: number } }>
}

function loose(): LooseClient {
  return useApiClient() as unknown as LooseClient
}

function statusOf(err: unknown): number | null {
  if (err && typeof err === 'object' && 'response' in err) {
    const r = (err as { response?: { status?: number } }).response
    if (r && typeof r.status === 'number') return r.status
  }
  return null
}

export const useShareStore = defineStore("share", () => {
  const records = ref<ShareRecord[]>([])
  const total = ref(0)
  const loading = ref(false)
  const page = ref(1)
  const pageSize = 50
  const activeFilter = ref<string | null>(null)
  const activeOnly = ref(false)

  async function fetchRecords(targetPage = 1): Promise<void> {
    loading.value = true
    try {
      const query: Record<string, unknown> = { page: targetPage, pageSize }
      if (activeFilter.value) query.kind = activeFilter.value
      if (activeOnly.value) query.activeOnly = true
      const { data } = await loose().GET('/api/v1/shares', { params: { query } })
      const res = data as ShareListPage | undefined
      records.value = res?.data ?? []
      total.value = res?.total ?? 0
      page.value = targetPage
    } catch (e) {
      handleShareError(e, "store.share.listFailed")
    } finally {
      loading.value = false
    }
  }

  async function revoke(id: string): Promise<ShareRecord | null> {
    try {
      const { data } = await loose().POST(`/api/v1/shares/${encodeURIComponent(id)}/revoke`)
      const record = data as ShareRecord
      toast.success(t("store.share.revoked", { id }))
      // Refresh local view so the table reflects the new revokedAt timestamp.
      records.value = records.value.map(r => r.id === record.id ? record : r)
      return record
    } catch (e) {
      const status = statusOf(e)
      if (status === 409) toast.error(t("store.share.alreadyRevoked"))
      else if (status === 422) toast.error(t("store.share.notRevocable"))
      else handleShareError(e, "store.share.revokeFailed")
      return null
    }
  }

  async function shareControllerLogs(req: ShareLogRequest): Promise<ShareResult | null> {
    try {
      const { data } = await loose().POST('/api/v1/system/logs/share', { body: req })
      const result = data as ShareResult
      toastShareSuccess("store.share.logsSuccess", result)
      return result
    } catch (e) {
      handleShareError(e, "store.share.logsFailed")
      return null
    }
  }

  async function shareDaemonLogs(nodeId: string, req: ShareLogRequest): Promise<ShareResult | null> {
    try {
      const { data } = await loose().POST(`/api/v1/nodes/${encodeURIComponent(nodeId)}/logs/share`, { body: req })
      const result = data as ShareResult
      toastShareSuccess("store.share.logsSuccess", result)
      return result
    } catch (e) {
      handleShareError(e, "store.share.logsFailed")
      return null
    }
  }

  async function shareDiagnostics(req: ShareRequest = {}): Promise<ShareResult | null> {
    try {
      const { data } = await loose().POST('/api/v1/system/diagnostics/share', { body: req })
      const result = data as ShareResult
      toastShareSuccess("store.share.diagnosticsSuccess", result)
      return result
    } catch (e) {
      handleShareError(e, "store.share.diagnosticsFailed")
      return null
    }
  }

  function toastShareSuccess(messageKey: string, result: ShareResult) {
    const message = t(messageKey, { url: result.url })
    if (result.deleteUrl) {
      toast.success(message, { description: t("store.share.revokeHint", { url: result.deleteUrl }) })
    } else {
      toast.success(message)
    }
  }

  function handleShareError(err: unknown, fallbackKey: string) {
    const status = statusOf(err)
    if (status === 409) {
      toast.error(t("store.share.disabled"))
    } else if (status === 502) {
      toast.error(t("store.share.upstream"))
    } else {
      toast.error(t(fallbackKey))
    }
  }

  return {
    shareControllerLogs,
    shareDaemonLogs,
    shareDiagnostics,
    records,
    total,
    loading,
    page,
    pageSize,
    activeFilter,
    activeOnly,
    fetchRecords,
    revoke,
  }
})
