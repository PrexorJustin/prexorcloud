import { defineStore } from "pinia"
import { ref } from "vue"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

export interface AdminToken {
  tokenId: string
  nodeId?: string | null
  expiresAt: string
  createdAt?: string
  /** Returned only on POST; never echoed by GET. */
  joinToken?: string
}

/**
 * Admin (node-join) tokens. Issued via POST /admin/tokens; the raw join token
 * is shown once and never persisted. Use revoke to invalidate before expiry.
 */
export const useAdminTokensStore = defineStore("adminTokens", () => {
  const tokens = ref<AdminToken[]>([])
  const loading = ref(false)

  async function fetchTokens() {
    loading.value = true
    try {
      const { data } = await useApiClient().GET('/api/v1/admin/tokens')
      tokens.value = ((data as { data?: AdminToken[] })?.data ?? []) as AdminToken[]
    } catch {
      toast.error(t("store.adminTokens.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loading.value = false
    }
  }

  async function generateToken(body: { nodeId?: string; ttlSeconds?: number }): Promise<AdminToken | null> {
    try {
      const { data } = await useApiClient().POST('/api/v1/admin/tokens', { body: body as never })
      const created = data as AdminToken
      tokens.value = [created, ...tokens.value]
      toast.success(t("store.adminTokens.generated"), { description: t("store.adminTokens.generatedDesc") })
      return created
    } catch {
      toast.error(t("store.adminTokens.generateFailed"), { description: t("store.adminTokens.generateFailedDesc") })
      return null
    }
  }

  async function revokeToken(id: string) {
    try {
      await useApiClient().DELETE('/api/v1/admin/tokens/{id}', { params: { path: { id } } })
      tokens.value = tokens.value.filter(t => t.tokenId !== id)
      toast.success(t("store.adminTokens.revoked"))
    } catch {
      toast.error(t("store.adminTokens.revokeFailed"), { description: t("store.adminTokens.revokeFailedDesc") })
      throw new Error("revoke-token")
    }
  }

  return { tokens, loading, fetchTokens, generateToken, revokeToken }
})
