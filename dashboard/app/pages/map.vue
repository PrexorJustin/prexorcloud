<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue"
import { Server, RotateCcw, Maximize2 } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { DetailSheet } from "~/components/ui/detail-sheet"
import { StatusBadge } from "~/components/ui/status-badge"
import TopologyCanvas from "~/components/topology/TopologyCanvas.vue"
import { formatUptime } from "~/lib/utils"
import type { NodeEntry, ServerInstance } from "~/types/api"

const nodesStore = useNodesStore()
const instancesStore = useInstancesStore()

onMounted(() => {
  nodesStore.fetchNodes()
  instancesStore.fetchInstances()
  nodesStore.connectSse()
  instancesStore.connectSse()
})
onUnmounted(() => {
  nodesStore.disconnectSse()
  instancesStore.disconnectSse()
})

// Filter — search by node id or instance id.
const search = ref("")
const filteredNodes = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return nodesStore.nodes
  return nodesStore.nodes.filter(n => {
    if (n.id.toLowerCase().includes(q)) return true
    return instancesStore.instances.some(i => i.node === n.id && i.id.toLowerCase().includes(q))
  })
})

// Per-node CPU history — accumulated from SSE store ticks. Skip the
// expensive accumulation if we don't have NODE_STATUS events flowing yet;
// the topology still renders without sparklines.
const cpuHistory = ref<Record<string, number[]>>({})
const HISTORY_LEN = 30

watch(
  () => nodesStore.nodes.map(n => ({ id: n.id, cpu: 'cpuUsage' in n ? n.cpuUsage : 0 })),
  (snapshot) => {
    for (const { id, cpu } of snapshot) {
      const list = cpuHistory.value[id] ?? []
      list.push(cpu * 100)
      if (list.length > HISTORY_LEN) list.shift()
      cpuHistory.value[id] = [...list]
    }
  },
  { deep: true },
)

// Sheet on instance click.
const sheetInstance = ref<ServerInstance | null>(null)
const sheetOpen = computed({
  get: () => sheetInstance.value !== null,
  set: (v) => { if (!v) sheetInstance.value = null },
})

function onInstanceClick(instanceId: string) {
  const inst = instancesStore.instances.find(i => i.id === instanceId)
  if (inst) sheetInstance.value = inst as ServerInstance
}

const canvasRef = ref<{ resetLayout: () => void; fitView: (opts?: { padding?: number }) => void } | null>(null)
function reset() { canvasRef.value?.resetLayout(); window.location.reload() }
function fit() { canvasRef.value?.fitView({ padding: 0.15 }) }
</script>

<template>
  <div class="flex flex-1 flex-col gap-4">
    <PageHeader title="Map" description="Cluster topology — every node and the instances running on it.">
      <template #actions>
        <Input v-model="search" placeholder="Filter by node or instance…" class="w-64" />
        <Button variant="outline" size="sm" @click="fit">
          <Maximize2 class="mr-1.5 size-3.5" /> Fit
        </Button>
        <Button variant="outline" size="sm" @click="reset">
          <RotateCcw class="mr-1.5 size-3.5" /> Reset layout
        </Button>
      </template>
    </PageHeader>

    <EmptyState
      v-if="!nodesStore.loading && filteredNodes.length === 0"
      :icon="Server"
      :title="search ? 'No matches' : 'No nodes connected'"
      :description="search ? 'Try clearing the filter or searching by another term.' : 'Start a daemon with a join token to see the cluster.'"
    />

    <div
      v-else
      class="relative flex-1 overflow-hidden rounded-2xl border border-glass-border bg-glass/40 backdrop-blur-xl"
      style="min-height: calc(100vh - 220px)"
    >
      <TopologyCanvas
        ref="canvasRef"
        :nodes="(filteredNodes as unknown as NodeEntry[])"
        :instances="(instancesStore.instances as unknown as ServerInstance[])"
        :cpu-history="cpuHistory"
        @instance-click="onInstanceClick"
      />
    </div>

    <DetailSheet
      :open="sheetOpen"
      :title="sheetInstance?.id"
      eyebrow="Instance"
      :full-page-path="sheetInstance ? `/instances/${sheetInstance.id}` : undefined"
      @update:open="sheetOpen = $event"
    >
      <template v-if="sheetInstance" #status>
        <StatusBadge :state="sheetInstance.state" :pulse="sheetInstance.state === 'RUNNING'" />
      </template>

      <div v-if="sheetInstance" class="space-y-4 text-sm">
        <div class="flex justify-between">
          <span class="text-muted-foreground">Group</span>
          <NuxtLink :to="`/groups/${sheetInstance.group}`" class="mono text-primary hover:underline">{{ sheetInstance.group }}</NuxtLink>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">Node</span>
          <NuxtLink :to="`/nodes/${sheetInstance.node}`" class="mono text-primary hover:underline">{{ sheetInstance.node }}</NuxtLink>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">Port</span>
          <span class="tabular">{{ sheetInstance.port }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">Players</span>
          <span class="tabular">{{ sheetInstance.playerCount }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">Uptime</span>
          <span class="tabular">{{ formatUptime(sheetInstance.uptimeMs) }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">Deployment</span>
          <span class="tabular">rev {{ sheetInstance.deploymentRevision }}</span>
        </div>
      </div>
    </DetailSheet>
  </div>
</template>
