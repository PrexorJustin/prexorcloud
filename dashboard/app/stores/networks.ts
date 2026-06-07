import { defineStore } from "pinia"
import type { Schema } from "@prexorcloud/api-sdk"
import { withLoading, withMutation } from "~/composables/useStoreCrud"
import { t } from "~/lib/translate"

type NetworkComposition = Schema<'NetworkComposition'>

export const useNetworksStore = defineStore("networks", () => {
  const networks = ref<NetworkComposition[]>([])
  const loading = ref(false)

  async function fetchNetworks() {
    await withLoading(loading, t("store.networks.loadFailed"), async () => {
      const { data } = await useApiClient().GET('/api/v1/networks')
      networks.value = (data?.data ?? []) as NetworkComposition[]
    })
  }

  async function createNetwork(body: NetworkComposition) {
    await withMutation(
      () => useApiClient().POST('/api/v1/networks', { body }),
      {
        success: { message: t("store.networks.created"), description: t("store.networks.createdDesc", { name: body.name }) },
        errorMsg: t("store.networks.createFailed"),
        onSuccess: fetchNetworks,
      },
    )
  }

  async function updateNetwork(name: string, body: NetworkComposition) {
    await withMutation(
      () => useApiClient().PUT('/api/v1/networks/{name}', { params: { path: { name } }, body }),
      {
        success: { message: t("store.networks.updated"), description: t("store.networks.updatedDesc", { name }) },
        errorMsg: t("store.networks.updateFailed"),
        onSuccess: fetchNetworks,
      },
    )
  }

  async function deleteNetwork(name: string) {
    await withMutation(
      () => useApiClient().DELETE('/api/v1/networks/{name}', { params: { path: { name } } }),
      {
        success: { message: t("store.networks.deleted"), description: t("store.networks.deletedDesc", { name }) },
        errorMsg: t("store.networks.deleteFailed"),
        onSuccess: fetchNetworks,
      },
    )
  }

  return {
    networks,
    loading,
    fetchNetworks,
    createNetwork,
    updateNetwork,
    deleteNetwork,
  }
})
