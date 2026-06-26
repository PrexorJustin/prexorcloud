import { defineStore } from "pinia"
import type { Schema } from "@prexorcloud/api-sdk"
import type { CloudEvent } from "~/types/events"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"
import { withLoading, withMutation } from "~/composables/useStoreCrud"

type ServerGroup = Schema<'GroupDto'>

export const useGroupsStore = defineStore("groups", () => {
  const groups = ref<ServerGroup[]>([])
  const loading = ref(false)

  // Reactive signal for deployment events — detail page watches this
  const lastDeploymentEvent = ref<{ groupName: string; type: string } | null>(null)

  async function fetchGroups() {
    await withLoading(loading, t("store.groups.loadFailed"), async () => {
      const { data } = await useApiClient().GET('/api/v1/groups')
      groups.value = data?.data ?? []
    })
  }

  async function createGroup(body: Partial<ServerGroup>) {
    await withMutation(
      () => useApiClient().POST('/api/v1/groups', { body: body as Schema<'GroupConfig'> }),
      {
        success: { message: t("store.groups.created"), description: t("store.groups.createdDesc", { name: body.name }) },
        errorMsg: t("store.groups.createFailed"),
        onSuccess: fetchGroups,
      },
    )
  }

  /** Merge-patch a group's typed variable values (key→value overrides). */
  async function updateGroupVariables(name: string, variableValues: Record<string, string>) {
    await withMutation(
      () => useApiClient().PATCH('/api/v1/groups/{name}', {
        params: { path: { name } },
        body: { variableValues } as Schema<'GroupConfig'>,
      }),
      {
        success: { message: t("store.groups.variablesSaved"), description: t("store.groups.variablesSavedDesc", { name }) },
        errorMsg: t("store.groups.variablesSaveFailed"),
        onSuccess: fetchGroups,
      },
    )
  }

  function handleEvent(data: CloudEvent) {
    switch (data.type) {
      case "RESYNC_REQUIRED":
        fetchGroups()
        break
      case "GROUP_CREATED":
      case "GROUP_UPDATED":
        fetchGroups()
        break
      case "GROUP_DELETED":
        groups.value = groups.value.filter(group => group.name !== data.groupName)
        break
      case "GROUP_CRASH_LOOP":
        toast.warning(t("store.groups.crashLoop"), { description: t("store.groups.crashLoopDesc", { group: data.group }) })
        break
      case "GROUP_AGGREGATES_UPDATED": {
        const idx = groups.value.findIndex(g => g.name === data.groupName)
        if (idx !== -1) {
          const existing = groups.value[idx]!
          // Only patch the two aggregate fields; leave config unchanged.
          groups.value = groups.value.toSpliced(idx, 1, {
            ...existing,
            runningInstances: data.runningInstances,
            totalPlayers: data.totalPlayers,
          })
        }
        break
      }
      case "DEPLOYMENT_CREATED":
      case "DEPLOYMENT_COMPLETED":
      case "DEPLOYMENT_ROLLED_BACK":
        lastDeploymentEvent.value = { groupName: data.groupName, type: data.type }
        break
    }
  }

  const sse = useStoreSseListener([
    "GROUP_CREATED", "GROUP_UPDATED", "GROUP_DELETED", "GROUP_CRASH_LOOP",
    "GROUP_AGGREGATES_UPDATED",
    "DEPLOYMENT_CREATED", "DEPLOYMENT_COMPLETED", "DEPLOYMENT_ROLLED_BACK",
    "RESYNC_REQUIRED",
  ], handleEvent)

  return {
    groups,
    loading,
    lastDeploymentEvent,
    fetchGroups,
    createGroup,
    updateGroupVariables,
    connectSse: sse.connect,
    disconnectSse: sse.disconnect,
  }
})
