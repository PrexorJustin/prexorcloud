import { defineStore } from "pinia"
import type { Schema } from "@prexorcloud/api-sdk"
import type { CloudEvent } from "~/types/events"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

type NodeDto = Schema<'ConnectedNodeDto'> | Schema<'DisconnectedNodeDto'> | Schema<'PendingNodeDto'>
type NodeListResponse = { data?: NodeDto[]; page?: number; pageSize?: number; total?: number }

const PAGE_SIZE = 500
const MAX_PAGES = 10  // safety cap → 5 000 nodes. Real clusters cap out long before this.

export const useNodesStore = defineStore("nodes", () => {
  const nodes = ref<NodeDto[]>([])
  const loading = ref(false)
  const total = ref(0)
  const truncated = ref(false)

  async function fetchNodes() {
    loading.value = true
    truncated.value = false
    try {
      type LooseNodeClient = { GET: (path: string) => Promise<{ data: unknown }> }
      const client = useApiClient() as unknown as LooseNodeClient
      const { data: first } = await client.GET(`/api/v1/nodes?page=1&pageSize=${PAGE_SIZE}`)
      const firstPage = (first ?? {}) as NodeListResponse
      const collected: NodeDto[] = [...(firstPage.data ?? [])]
      const totalCount = firstPage.total ?? collected.length
      total.value = totalCount

      const pagesNeeded = Math.ceil(totalCount / PAGE_SIZE)
      const lastPage = Math.min(pagesNeeded, MAX_PAGES)
      for (let p = 2; p <= lastPage; p++) {
        const { data: next } = await client.GET(`/api/v1/nodes?page=${p}&pageSize=${PAGE_SIZE}`)
        collected.push(...(((next ?? {}) as NodeListResponse).data ?? []))
      }
      if (pagesNeeded > MAX_PAGES) truncated.value = true

      nodes.value = collected
    }
    catch { toast.error(t("store.nodes.loadFailed")) }
    finally {
      loading.value = false
    }
  }

  // SSE real-time updates via centralized event bus
  const notifiedDrains = new Set<string>()

  function handleEvent(data: CloudEvent) {
    switch (data.type) {
      case "RESYNC_REQUIRED":
        fetchNodes()
        break
      case "NODE_STATUS": {
        const node = nodes.value.find(n => n.id === data.nodeId)
        if (node && node.type === "CONNECTED") {
          const connected = node as Schema<'ConnectedNodeDto'>
          connected.cpuUsage = data.cpuUsage
          connected.usedMemoryMb = data.usedMemoryMb
          connected.totalMemoryMb = data.totalMemoryMb
          if (data.lastHeartbeatAt) connected.lastHeartbeat = data.lastHeartbeatAt
        }
        break
      }
      case "NODE_HEARTBEAT_STALE": {
        const node = nodes.value.find(n => n.id === data.nodeId)
        if (node && node.type === "CONNECTED") {
          (node as Schema<'ConnectedNodeDto'>).status = "UNREACHABLE"
        }
        staleNodeIds.value = new Set(staleNodeIds.value).add(data.nodeId)
        break
      }
      case "NODE_HEARTBEAT_RESUMED": {
        const node = nodes.value.find(n => n.id === data.nodeId)
        if (node && node.type === "CONNECTED") {
          const connected = node as Schema<'ConnectedNodeDto'>
          if (connected.status === "UNREACHABLE") connected.status = "ONLINE"
          if (data.lastHeartbeatAt) connected.lastHeartbeat = data.lastHeartbeatAt
        }
        const next = new Set(staleNodeIds.value)
        next.delete(data.nodeId)
        staleNodeIds.value = next
        break
      }
      case "NODE_CONNECTED":
        fetchNodes()
        break
      case "NODE_DISCONNECTED": {
        const idx = nodes.value.findIndex(n => n.id === data.nodeId)
        if (idx !== -1) {
          nodes.value[idx] = {
            id: data.nodeId,
            type: "DISCONNECTED",
            status: "OFFLINE",
            firstSeen: (nodes.value[idx] as any).connectedSince ?? data.timestamp,
            lastSeen: data.timestamp,
          } as Schema<'DisconnectedNodeDto'>
        }
        break
      }
      case "NODE_DRAIN_COMPLETED": {
        const node = nodes.value.find(n => n.id === data.nodeId)
        if (node && node.type === "CONNECTED") {
          (node as Schema<'ConnectedNodeDto'>).status = "ONLINE"
        }
        if (!notifiedDrains.has(data.nodeId)) {
          notifiedDrains.add(data.nodeId)
          toast.success(t("store.nodes.drainCompleted"), {
            description: t("store.nodes.drainCompletedDesc", { id: data.nodeId }),
          })
          setTimeout(() => notifiedDrains.delete(data.nodeId), 60000)
        }
        break
      }
      case "NODE_DRAIN_REQUESTED": {
        const node = nodes.value.find(n => n.id === data.nodeId)
        if (node && node.type === "CONNECTED") {
          (node as Schema<'ConnectedNodeDto'>).status = "DRAINING"
        }
        break
      }
      case "NODE_CACHE_STATUS": {
        cacheUpdatedNodeId.value = data.nodeId
        break
      }
    }
  }

  const sse = useStoreSseListener([
    "NODE_STATUS", "NODE_CONNECTED", "NODE_DISCONNECTED",
    "NODE_DRAIN_COMPLETED", "NODE_DRAIN_REQUESTED", "NODE_CACHE_STATUS",
    "NODE_HEARTBEAT_STALE", "NODE_HEARTBEAT_RESUMED",
    "RESYNC_REQUIRED",
  ], handleEvent)

  // Cache invalidation — detail page watches this to re-fetch
  const cacheUpdatedNodeId = ref<string | null>(null)

  // Nodes currently in the heartbeat-stale window — UI can show a banner
  // without polling wall-clock.
  const staleNodeIds = ref<Set<string>>(new Set())

  const explodingNodeId = ref<string | null>(null)

  function explodeAndRemove(nodeId: string) {
    explodingNodeId.value = nodeId
    setTimeout(() => {
      nodes.value = nodes.value.filter(n => n.id !== nodeId)
      explodingNodeId.value = null
    }, 700)
  }

  return {
    nodes,
    loading,
    total,
    truncated,
    explodingNodeId,
    cacheUpdatedNodeId,
    staleNodeIds,
    fetchNodes,
    explodeAndRemove,
    connectSse: sse.connect,
    disconnectSse: sse.disconnect,
  }
})
