import { defineStore } from "pinia"
import { ref } from "vue"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

export interface RevokedCert {
  nodeId: string
  serial?: string
  revokedAt: string
  reason?: string
}

type LooseClient = {
  GET: (path: string, init?: unknown) => Promise<{ data: unknown }>
  POST: (path: string, init?: unknown) => Promise<{ data: unknown }>
  DELETE: (path: string, init?: unknown) => Promise<unknown>
}

/**
 * Node TLS-cert revocation list. The compromise-response surface — operators
 * land here when a node is suspected leaked, revoke the cert, and the daemon
 * loses controller access immediately.
 */
export const useCertificatesStore = defineStore("certificates", () => {
  const revoked = ref<RevokedCert[]>([])
  const loading = ref(false)

  function loose(): LooseClient {
    return useApiClient() as unknown as LooseClient
  }

  async function fetchRevoked() {
    loading.value = true
    try {
      const { data } = await loose().GET('/api/v1/nodes/revoked-certs')
      revoked.value = ((data as { data?: RevokedCert[] })?.data ?? []) as RevokedCert[]
    } catch {
      toast.error(t("store.certificates.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loading.value = false
    }
  }

  async function revoke(nodeId: string, reason?: string) {
    try {
      await loose().POST(`/api/v1/nodes/${encodeURIComponent(nodeId)}/revoke-cert`, { body: { reason } })
      toast.success(t("store.certificates.revoked", { nodeId }), { description: t("store.certificates.revokedDesc") })
      await fetchRevoked()
    } catch {
      toast.error(t("store.certificates.revokeFailed"), { description: t("store.certificates.revokeFailedDesc") })
      throw new Error("revoke-cert")
    }
  }

  async function unrevoke(nodeId: string) {
    try {
      await loose().DELETE(`/api/v1/nodes/${encodeURIComponent(nodeId)}/revoke-cert`)
      toast.success(t("store.certificates.unrevoked", { nodeId }))
      await fetchRevoked()
    } catch {
      toast.error(t("store.certificates.unrevokeFailed"), { description: t("store.certificates.unrevokeFailedDesc") })
      throw new Error("unrevoke-cert")
    }
  }

  return { revoked, loading, fetchRevoked, revoke, unrevoke }
})
