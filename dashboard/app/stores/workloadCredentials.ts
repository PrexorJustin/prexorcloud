import { defineStore } from "pinia"
import { ref } from "vue"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

export interface WorkloadCredential {
  tokenId: string
  instanceId: string
  group?: string
  node?: string
  issuedAt: string
  expiresAt?: string | null
  scope?: string
}

/**
 * Workload credentials — short-lived bearer tokens issued to plugin/proxy
 * processes for talking back to the controller. Compromised hosts get their
 * credentials revoked here.
 *
 * The workload-credentials paths aren't in the openapi-fetch typed `paths`
 * bundle yet (they require regen against the latest OpenAPI), so go through
 * a loose cast for now. Remove the cast once `pnpm sdk:generate` has been
 * run against a controller that ships these endpoints.
 */
type LooseClient = {
  GET: (path: string, init?: unknown) => Promise<{ data: unknown }>
  DELETE: (path: string, init?: unknown) => Promise<unknown>
}

export const useWorkloadCredentialsStore = defineStore("workloadCredentials", () => {
  const credentials = ref<WorkloadCredential[]>([])
  const loading = ref(false)

  function loose(): LooseClient {
    return useApiClient() as unknown as LooseClient
  }

  async function fetchCredentials() {
    loading.value = true
    try {
      const { data } = await loose().GET('/api/v1/workloads/credentials')
      credentials.value = ((data as { data?: WorkloadCredential[] })?.data ?? []) as WorkloadCredential[]
    } catch {
      toast.error(t("store.credentials.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loading.value = false
    }
  }

  async function revokeCredential(tokenId: string) {
    try {
      await loose().DELETE(`/api/v1/workloads/credentials/${encodeURIComponent(tokenId)}`)
      credentials.value = credentials.value.filter(c => c.tokenId !== tokenId)
    } catch {
      toast.error(t("store.credentials.revokeFailed"), { description: t("store.credentials.revokeFailedDesc") })
      throw new Error("revoke-credential")
    }
  }

  async function revokeAllForInstance(instanceId: string) {
    try {
      await loose().DELETE(`/api/v1/workloads/credentials/instances/${encodeURIComponent(instanceId)}`)
      credentials.value = credentials.value.filter(c => c.instanceId !== instanceId)
      toast.success(t("store.credentials.revokedAll", { instanceId }))
    } catch {
      toast.error(t("store.credentials.revokeFailed"), { description: t("store.credentials.revokeAllFailedDesc") })
      throw new Error("revoke-credentials-instance")
    }
  }

  return { credentials, loading, fetchCredentials, revokeCredential, revokeAllForInstance }
})
