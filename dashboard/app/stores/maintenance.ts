import { defineStore } from "pinia"
import { ref } from "vue"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

export interface GroupMaintenance {
  groupName: string
  enabled: boolean
  message?: string
  bypassUsernames?: string[]
}

export interface MaintenanceState {
  globalEnabled: boolean
  globalMessage?: string
  globalBypassUsernames?: string[]
  groups: GroupMaintenance[]
}

type LooseClient = {
  GET: (path: string, init?: unknown) => Promise<{ data: unknown }>
  PUT: (path: string, init?: unknown) => Promise<{ data: unknown }>
}

/**
 * Cluster maintenance flag + per-group overrides. Global enabled prevents new
 * player joins everywhere. Per-group overrides are scoped to one group.
 */
export const useMaintenanceStore = defineStore("maintenance", () => {
  const state = ref<MaintenanceState>({
    globalEnabled: false,
    globalMessage: "",
    globalBypassUsernames: [],
    groups: [],
  })
  const loading = ref(false)

  function loose(): LooseClient {
    return useApiClient() as unknown as LooseClient
  }

  async function fetchState() {
    loading.value = true
    try {
      const { data } = await loose().GET('/api/v1/maintenance')
      const next = data as MaintenanceState
      state.value = {
        globalEnabled: next.globalEnabled ?? false,
        globalMessage: next.globalMessage ?? "",
        globalBypassUsernames: next.globalBypassUsernames ?? [],
        groups: next.groups ?? [],
      }
    } catch {
      toast.error(t("store.maintenance.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loading.value = false
    }
  }

  async function updateState(body: Partial<MaintenanceState>) {
    try {
      await loose().PUT('/api/v1/maintenance', { body })
      await fetchState()
    } catch {
      toast.error(t("store.maintenance.updateFailed"), { description: t("store.maintenance.updateFailedDesc") })
      throw new Error("update-maintenance")
    }
  }

  async function setGlobal(enabled: boolean, message?: string) {
    await updateState({ globalEnabled: enabled, globalMessage: message ?? state.value.globalMessage })
    toast.success(enabled ? t("store.maintenance.clusterEnabled") : t("store.maintenance.clusterDisabled"))
  }

  async function setGroupMaintenance(groupName: string, enabled: boolean, message?: string) {
    const next = state.value.groups.filter(g => g.groupName !== groupName)
    if (enabled || message) {
      next.push({ groupName, enabled, message, bypassUsernames: state.value.groups.find(g => g.groupName === groupName)?.bypassUsernames ?? [] })
    }
    await updateState({ groups: next })
    toast.success(enabled ? t("store.maintenance.groupEnabled", { group: groupName }) : t("store.maintenance.groupDisabled", { group: groupName }))
  }

  return { state, loading, fetchState, setGlobal, setGroupMaintenance, updateState }
})
