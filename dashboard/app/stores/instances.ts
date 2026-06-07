import { defineStore } from "pinia"
import type { Schema } from "@prexorcloud/api-sdk"
import type { CloudEvent, InstanceMetricsEvent } from "~/types/events"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

type ServiceDto = Schema<'InstanceDto'>
type ServiceListResponse = { data?: ServiceDto[]; page?: number; pageSize?: number; total?: number }

const PAGE_SIZE = 500
const MAX_PAGES = 20  // safety cap → 10 000 instances. Beyond that, paginated UI is the right answer.

export const useInstancesStore = defineStore("instances", () => {
  const instances = ref<ServiceDto[]>([])
  const loading = ref(false)
  const total = ref(0)
  const truncated = ref(false)

  // Reactive event signals — pages watch these instead of opening extra SSE connections.
  // lastInstanceMetrics carries the full inline payload so detail pages can render
  // without an extra REST round-trip on every metrics tick.
  const lastMetricsInstanceId = ref<string | null>(null)
  const lastInstanceMetrics = shallowRef<InstanceMetricsEvent | null>(null)

  async function fetchInstances() {
    loading.value = true
    truncated.value = false
    try {
      type LooseSvcClient = { GET: (path: string) => Promise<{ data: unknown }> }
      const client = useApiClient() as unknown as LooseSvcClient
      const { data: first } = await client.GET(`/api/v1/services?page=1&pageSize=${PAGE_SIZE}`)
      const firstPage = (first ?? {}) as ServiceListResponse
      const collected: ServiceDto[] = [...(firstPage.data ?? [])]
      const totalCount = firstPage.total ?? collected.length
      total.value = totalCount

      const pagesNeeded = Math.ceil(totalCount / PAGE_SIZE)
      const lastPage = Math.min(pagesNeeded, MAX_PAGES)
      for (let p = 2; p <= lastPage; p++) {
        const { data: next } = await client.GET(`/api/v1/services?page=${p}&pageSize=${PAGE_SIZE}`)
        collected.push(...(((next ?? {}) as ServiceListResponse).data ?? []))
      }
      if (pagesNeeded > MAX_PAGES) truncated.value = true

      instances.value = collected
    }
    catch { toast.error(t("store.instances.loadFailed")) }
    finally {
      loading.value = false
    }
  }

  async function stopInstance(id: string) {
    await useApiClient().POST('/api/v1/services/{id}/stop', { params: { path: { id } } })
    toast.success(t("store.instances.stopRequested"), { description: t("store.instances.stopRequestedDesc", { id }) })
    await fetchInstances()
  }

  async function forceStopInstance(id: string) {
    await useApiClient().POST('/api/v1/services/{id}/force-stop', { params: { path: { id } } })
    toast.success(t("store.instances.forceStopRequested"), { description: t("store.instances.forceStopRequestedDesc", { id }) })
    await fetchInstances()
  }

  async function deleteInstance(id: string) {
    await useApiClient().DELETE('/api/v1/services/{id}', { params: { path: { id } } })
    toast.success(t("store.instances.deleted"), { description: t("store.instances.deletedDesc", { id }) })
    await fetchInstances()
  }

  function applyPlayerDelta(instanceId: string, delta: number) {
    const inst = instances.value.find(i => i.id === instanceId)
    if (!inst) return
    inst.playerCount = Math.max(0, (inst.playerCount ?? 0) + delta)
  }

  function handleEvent(data: CloudEvent) {
    switch (data.type) {
      case "RESYNC_REQUIRED":
        fetchInstances()
        break
      case "INSTANCE_STATE_CHANGED": {
        const inst = instances.value.find(i => i.id === data.instanceId)
        if (inst) {
          inst.state = data.newState as ServiceDto["state"]
        } else {
          fetchInstances()
        }
        break
      }
      case "INSTANCE_CRASHED": {
        const inst = instances.value.find(i => i.id === data.instanceId)
        if (inst) {
          inst.state = "CRASHED" as ServiceDto["state"]
        }
        break
      }
      case "INSTANCE_METRICS": {
        lastMetricsInstanceId.value = data.instanceId
        lastInstanceMetrics.value = data
        const inst = instances.value.find(i => i.id === data.instanceId)
        if (inst && 'playerCount' in data && typeof data.playerCount === "number") {
          inst.playerCount = data.playerCount
        }
        break
      }
      case "PLAYER_CONNECTED":
        applyPlayerDelta(data.instanceId, 1)
        break
      case "PLAYER_DISCONNECTED":
        applyPlayerDelta(data.instanceId, -1)
        break
    }
  }

  const sse = useStoreSseListener([
    "INSTANCE_STATE_CHANGED",
    "INSTANCE_CRASHED",
    "INSTANCE_METRICS",
    "PLAYER_CONNECTED",
    "PLAYER_DISCONNECTED",
    "RESYNC_REQUIRED",
  ], handleEvent)

  return {
    instances,
    loading,
    total,
    truncated,
    lastMetricsInstanceId,
    lastInstanceMetrics,
    fetchInstances,
    stopInstance,
    forceStopInstance,
    deleteInstance,
    connectSse: sse.connect,
    disconnectSse: sse.disconnect,
  }
})
