<script setup lang="ts">
import { ArrowLeft, Server, Clock, Tag, Monitor, Trash2, Copy, Check, ShieldOff, Power, Database, Box } from "lucide-vue-next"
import { toast } from "vue-sonner"
import type { NodeEntry, ConnectedNode, PendingNode, NodeCacheStatus } from "~/types/api"
import { Badge } from "~/components/ui/badge"
import { StatusBadge } from "~/components/ui/status-badge"
import { Button } from "~/components/ui/button"
import { Separator } from "~/components/ui/separator"
import { Sparkline } from "~/components/ui/sparkline"
import { formatMemory, formatUptime as formatUptimeMs, timeAgo } from "~/lib/utils"
import NodeCachePanel from "~/components/nodes/NodeCachePanel.vue"
import { useMetricsTimeseries } from "~/composables/useMetricsTimeseries"

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const nodeId = route.params.id as string

const node = ref<NodeEntry | null>(null)
const loading = ref(true)

const isConnected = computed(() => node.value?.type === "CONNECTED")
const connected = computed(() => (isConnected.value ? node.value as ConnectedNode : null))

function formatUptime(since: string): string {
  const diff = Date.now() - new Date(since).getTime()
  const days = Math.floor(diff / 86400000)
  const hours = Math.floor((diff % 86400000) / 3600000)
  const minutes = Math.floor((diff % 3600000) / 60000)
  if (days > 0) return `${days}d ${hours}h ${minutes}m`
  if (hours > 0) return `${hours}h ${minutes}m`
  return `${minutes}m`
}

function formatFreq(hz: number): string {
  return `${(hz / 1_000_000_000).toFixed(1)} GHz`
}

const now = ref(Date.now())
let heartbeatTimer: ReturnType<typeof setInterval>

const lastHeartbeatAgo = computed(() => {
  void now.value
  return connected.value ? timeAgo(connected.value.lastHeartbeat) : ''
})

const store = useNodesStore()
const isHeartbeatStale = computed(() => store.staleNodeIds.has(nodeId))
const instancesStore = useInstancesStore()

const nodeInstances = computed(() =>
  instancesStore.instances.filter(i => i.node === nodeId),
)

const cpuPercent = computed(() => connected.value ? Math.round(connected.value.cpuUsage * 100) : 0)
const memPercent = computed(() => connected.value && connected.value.totalMemoryMb ? Math.round((connected.value.usedMemoryMb / connected.value.totalMemoryMb) * 100) : 0)
const tsn = useMetricsTimeseries({ scope: { kind: 'node', id: nodeId }, window: '1h', buckets: 60 })
const diskPercent = computed(() => connected.value && connected.value.totalDiskMb ? Math.round(((connected.value.totalDiskMb - connected.value.freeDiskMb) / connected.value.totalDiskMb) * 100) : 0)

onMounted(async () => {
  heartbeatTimer = setInterval(() => { now.value = Date.now() }, 5000)
  try {
    node.value = (await useApiClient().GET('/api/v1/nodes/{id}', { params: { path: { id: nodeId } } })).data as NodeEntry
  }
  catch { toast.error(t('pages.nodeDetail.toast.loadFailedTitle'), { description: t('pages.nodeDetail.toast.loadFailedDesc', { id: nodeId }) }) }
  finally {
    loading.value = false
  }
  store.fetchNodes()
  store.connectSse()
  instancesStore.fetchInstances()
})

onUnmounted(() => {
  clearInterval(heartbeatTimer)
  store.disconnectSse()
})

// Watch the store's node list for SSE-driven changes to this node
watch(() => store.nodes.find(n => n.id === nodeId), async (storeNode) => {
  if (!storeNode) return
  // If the node type changed (e.g. offline → connected), refetch full details
  if (node.value && storeNode.type !== node.value.type) {
    try {
      node.value = (await useApiClient().GET('/api/v1/nodes/{id}', { params: { path: { id: nodeId } } })).data as NodeEntry
    }
    catch { toast.error(t('pages.nodeDetail.toast.actionFailedTitle'), { description: t('pages.nodeDetail.toast.genericRetry') }) }
  } else if (storeNode.type === 'CONNECTED' && node.value?.type === 'CONNECTED') {
    // Live-update stats from SSE
    const connected = node.value as ConnectedNode
    const updated = storeNode as ConnectedNode
    connected.cpuUsage = updated.cpuUsage
    connected.usedMemoryMb = updated.usedMemoryMb
    connected.totalMemoryMb = updated.totalMemoryMb
    connected.freeDiskMb = updated.freeDiskMb
    connected.totalDiskMb = updated.totalDiskMb
    connected.lastHeartbeat = updated.lastHeartbeat
  }
}, { deep: true })

const activeTab = ref<'overview' | 'cache'>('overview')
const cache = ref<NodeCacheStatus | null>(null)
const cacheLoading = ref(false)

async function fetchCache() {
  cacheLoading.value = true
  try {
    cache.value = (await useApiClient().GET('/api/v1/nodes/{id}/cache', { params: { path: { id: nodeId } } })).data as NodeCacheStatus
  }
  catch { toast.error(t('pages.nodeDetail.toast.actionFailedTitle'), { description: t('pages.nodeDetail.toast.genericRetry') }) }
  finally {
    cacheLoading.value = false
  }
}

// Auto-refresh cache when SSE notifies us of a change
watch(() => store.cacheUpdatedNodeId, (updatedId) => {
  if (updatedId === nodeId && activeTab.value === 'cache') {
    fetchCache()
  }
})

function formatBytes(bytes: number): string {
  if (bytes >= 1073741824) return `${(bytes / 1073741824).toFixed(1)} GB`
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

const deleting = ref(false)
const copied = ref(false)
const draining = ref(false)
const cordoning = ref(false)

const confirmOpen = ref(false)
const confirmAction = ref<(() => Promise<void>) | null>(null)
const confirmTitle = ref("")
const confirmDesc = ref("")
const confirmLabel = ref("")
const confirmLoading = ref(false)

function showConfirm(title: string, desc: string, label: string, action: () => Promise<void>) {
  confirmTitle.value = title
  confirmDesc.value = desc
  confirmLabel.value = label
  confirmAction.value = action
  confirmOpen.value = true
}

async function onConfirm() {
  if (!confirmAction.value) return
  confirmLoading.value = true
  try {
    await confirmAction.value()
  }
  finally {
    confirmLoading.value = false
    confirmOpen.value = false
  }
}

async function copyToken() {
  if (!node.value || node.value.type !== "PENDING") return
  await navigator.clipboard.writeText((node.value as PendingNode).joinToken)
  copied.value = true
  setTimeout(() => (copied.value = false), 2000)
}

function requestDelete() {
  if (!node.value) return
  const isPending = node.value.type === "PENDING"
  showConfirm(
    isPending ? t('pages.nodeDetail.confirm.revokeTitle') : t('pages.nodeDetail.confirm.deleteTitle'),
    isPending
      ? t('pages.nodeDetail.confirm.revokeDesc', { id: nodeId })
      : t('pages.nodeDetail.confirm.deleteDesc', { id: nodeId }),
    isPending ? t('pages.nodeDetail.revoke') : t('pages.nodeDetail.delete'),
    async () => {
      const wasPending = node.value!.type === "PENDING"
      if (wasPending) {
        await useApiClient().DELETE('/api/v1/admin/tokens/{id}', { params: { path: { id: (node.value as PendingNode).tokenId } } })
      } else {
        await useApiClient().DELETE('/api/v1/nodes/{id}', { params: { path: { id: nodeId } } })
      }
      toast.success(wasPending ? t('pages.nodeDetail.toast.tokenRevokedTitle') : t('pages.nodeDetail.toast.nodeDeletedTitle'), {
        description: wasPending ? t('pages.nodeDetail.toast.tokenRevokedDesc', { id: nodeId }) : t('pages.nodeDetail.toast.nodeDeletedDesc', { id: nodeId }),
      })
      await router.push("/nodes")
    },
  )
}

function requestDrain() {
  showConfirm(
    t('pages.nodeDetail.confirm.drainTitle'),
    t('pages.nodeDetail.confirm.drainDesc', { id: nodeId }),
    t('pages.nodeDetail.drain'),
    async () => {
      await useApiClient().POST('/api/v1/nodes/{id}/drain', { params: { path: { id: nodeId } } })
      node.value = (await useApiClient().GET('/api/v1/nodes/{id}', { params: { path: { id: nodeId } } })).data as NodeEntry
      toast.success(t('pages.nodeDetail.toast.drainStartedTitle'), { description: t('pages.nodeDetail.toast.drainStartedDesc', { id: nodeId }) })
    },
  )
}

async function undrainNode() {
  draining.value = true
  try {
    await useApiClient().POST('/api/v1/nodes/{id}/undrain', { params: { path: { id: nodeId } } })
    node.value = (await useApiClient().GET('/api/v1/nodes/{id}', { params: { path: { id: nodeId } } })).data as NodeEntry
    toast.success(t('pages.nodeDetail.toast.drainCancelledTitle'), { description: t('pages.nodeDetail.toast.drainCancelledDesc', { id: nodeId }) })
  }
  catch { toast.error(t('pages.nodeDetail.toast.actionFailedTitle'), { description: t('pages.nodeDetail.toast.genericRetry') }) }
  finally {
    draining.value = false
  }
}

function requestCordon() {
  showConfirm(
    t('pages.nodeDetail.confirm.cordonTitle'),
    t('pages.nodeDetail.confirm.cordonDesc', { id: nodeId }),
    t('pages.nodeDetail.cordon'),
    async () => {
      await useApiClient().POST('/api/v1/nodes/{id}/cordon', { params: { path: { id: nodeId } } })
      node.value = (await useApiClient().GET('/api/v1/nodes/{id}', { params: { path: { id: nodeId } } })).data as NodeEntry
      toast.success(t('pages.nodeDetail.toast.cordonedTitle'), { description: t('pages.nodeDetail.toast.cordonedDesc', { id: nodeId }) })
    },
  )
}

async function uncordonNode() {
  cordoning.value = true
  try {
    await useApiClient().POST('/api/v1/nodes/{id}/uncordon', { params: { path: { id: nodeId } } })
    node.value = (await useApiClient().GET('/api/v1/nodes/{id}', { params: { path: { id: nodeId } } })).data as NodeEntry
    toast.success(t('pages.nodeDetail.toast.uncordonedTitle'), { description: t('pages.nodeDetail.toast.uncordonedDesc', { id: nodeId }) })
  }
  catch { toast.error(t('pages.nodeDetail.toast.actionFailedTitle'), { description: t('pages.nodeDetail.toast.genericRetry') }) }
  finally {
    cordoning.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <!-- Back + Header -->
    <div class="flex items-center gap-4">
      <Button variant="ghost" size="icon" as-child>
        <NuxtLink to="/nodes">
          <ArrowLeft class="size-5" />
        </NuxtLink>
      </Button>
      <div class="flex-1 min-w-0">
        <p class="eyebrow mb-1">{{ t('pages.nodeDetail.node') }}</p>
        <h1 class="text-2xl font-bold tracking-tight text-gradient-title mono">{{ nodeId }}</h1>
        <p class="mt-0.5 text-sm text-muted-foreground">
          <template v-if="connected">{{ connected.address }}</template>
          <template v-else-if="node?.type === 'PENDING'">{{ t('pages.nodeDetail.awaitingConnection') }}</template>
          <template v-else-if="node?.type === 'DISCONNECTED'">{{ t('pages.nodeDetail.disconnected') }}</template>
          <template v-else>{{ t('pages.nodeDetail.nodeDetails') }}</template>
        </p>
      </div>
      <StatusBadge v-if="node" :state="node.status" :pulse="node.status === 'ONLINE'" />
    </div>

    <!-- Loading -->
    <div v-if="loading" class="grid grid-cols-1 lg:grid-cols-2 gap-5">
      <div v-for="i in 4" :key="i" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6 animate-pulse">
        <div class="h-5 bg-glass rounded w-32 mb-4" />
        <div class="space-y-3">
          <div class="h-4 bg-glass rounded w-full" />
          <div class="h-4 bg-glass rounded w-3/4" />
          <div class="h-4 bg-glass rounded w-1/2" />
        </div>
      </div>
    </div>

    <!-- Not found -->
    <div v-else-if="!node" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border py-24 flex flex-col items-center justify-center text-center">
      <Server class="size-12 text-muted-foreground/30 mb-3" />
      <p class="text-base font-semibold">{{ t('pages.nodeDetail.notFoundTitle') }}</p>
      <p class="mt-1 text-sm text-muted-foreground">{{ t('pages.nodeDetail.notFoundHint') }}</p>
    </div>

    <!-- Connected node details -->
    <template v-else-if="connected">
      <!-- Tabs: Overview & Cache -->
      <nav class="flex gap-1 border-b border-glass-border -mb-px">
        <button
          v-for="tab in ([
            { key: 'overview' as const, label: t('pages.nodeDetail.tabs.overview'), icon: Server },
            { key: 'cache' as const, label: t('pages.nodeDetail.tabs.cache'), icon: Database },
          ])"
          :key="tab.key"
          :class="[
            'inline-flex items-center gap-2 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors -mb-px',
            activeTab === tab.key
              ? 'border-primary text-foreground'
              : 'border-transparent text-muted-foreground hover:text-foreground hover:border-muted-foreground/30',
          ]"
          @click="() => { activeTab = tab.key; if (tab.key === 'cache' && !cache) fetchCache() }"
        >
          <component :is="tab.icon" class="size-4" />
          {{ tab.label }}
        </button>
      </nav>

      <template v-if="activeTab === 'overview'">
        <div class="flex flex-col gap-5">
          <!-- Detail cards -->
          <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
            <!-- Connection info -->
            <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
              <h2 class="mb-4 flex items-center gap-2 text-base font-semibold">
                <Clock class="size-4 text-muted-foreground" />
                {{ t('pages.nodeDetail.connection') }}
              </h2>
              <div class="flex flex-col gap-3">
                <div class="flex items-center justify-between">
                  <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.conn.address') }}</span>
                  <span class="text-sm font-medium text-foreground font-mono">{{ connected.address }}</span>
                </div>
                <Separator class="bg-glass-border" />
                <div class="flex items-center justify-between">
                  <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.conn.connectedSince') }}</span>
                  <span class="text-sm text-foreground">{{ new Date(connected.connectedSince).toLocaleString() }}</span>
                </div>
                <Separator class="bg-glass-border" />
                <div class="flex items-center justify-between">
                  <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.conn.uptime') }}</span>
                  <span class="text-sm font-medium text-foreground">{{ formatUptime(connected.connectedSince) }}</span>
                </div>
                <Separator class="bg-glass-border" />
                <div class="flex items-center justify-between">
                  <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.conn.lastHeartbeat') }}</span>
                  <span class="flex items-center gap-2">
                    <span
                      v-if="isHeartbeatStale"
                      class="rounded-full bg-destructive/15 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-destructive"
                    >{{ t('pages.nodeDetail.stale') }}</span>
                    <span class="text-sm text-foreground" :title="new Date(connected.lastHeartbeat).toLocaleString()">{{ lastHeartbeatAgo }}</span>
                  </span>
                </div>
                <Separator class="bg-glass-border" />
                <!-- CPU -->
                <div>
                  <div class="flex justify-between mb-1.5">
                    <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.conn.cpu') }}</span>
                    <span class="text-sm text-foreground tabular-nums">{{ cpuPercent }}%</span>
                  </div>
                  <div class="h-2 rounded-full bg-glass overflow-hidden">
                    <div
                      class="h-full rounded-full transition-all duration-500"
                      :class="cpuPercent > 85 ? 'bg-destructive' : cpuPercent > 70 ? 'bg-warning' : 'bg-success'"
                      :style="{ width: `${cpuPercent}%` }"
                    />
                  </div>
                  <Sparkline v-if="tsn.loaded.value" :data="tsn.series.cpuUsage ?? []" :height="20" tone="success" class="mt-1.5" />
                </div>
                <!-- Memory -->
                <div>
                  <div class="flex justify-between mb-1.5">
                    <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.conn.memory') }}</span>
                    <span class="text-sm text-foreground tabular-nums">{{ formatMemory(connected.usedMemoryMb) }} / {{ formatMemory(connected.totalMemoryMb) }}</span>
                  </div>
                  <div class="h-2 rounded-full bg-glass overflow-hidden">
                    <div
                      class="h-full rounded-full transition-all duration-500"
                      :class="memPercent > 85 ? 'bg-destructive' : memPercent > 70 ? 'bg-warning' : 'bg-success'"
                      :style="{ width: `${memPercent}%` }"
                    />
                  </div>
                  <Sparkline v-if="tsn.loaded.value" :data="tsn.series.usedMemoryMb ?? []" :height="20" tone="primary" class="mt-1.5" />
                </div>
                <!-- Disk -->
                <div>
                  <div class="flex justify-between mb-1.5">
                    <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.conn.disk') }}</span>
                    <span class="text-sm text-foreground tabular-nums">{{ formatMemory(connected.totalDiskMb - connected.freeDiskMb) }} / {{ formatMemory(connected.totalDiskMb) }}</span>
                  </div>
                  <div class="h-2 rounded-full bg-glass overflow-hidden">
                    <div
                      class="h-full rounded-full transition-all duration-500"
                      :class="diskPercent > 90 ? 'bg-destructive' : diskPercent > 75 ? 'bg-warning' : 'bg-success'"
                      :style="{ width: `${diskPercent}%` }"
                    />
                  </div>
                </div>
                <div>
                  <div class="flex items-center justify-between">
                    <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.conn.instances') }}</span>
                    <span class="text-sm text-foreground tabular-nums">{{ connected.instanceCount }}</span>
                  </div>
                  <Sparkline v-if="tsn.loaded.value" :data="tsn.series.instanceCount ?? []" :height="20" tone="secondary" class="mt-1.5" />
                </div>
              </div>
            </div>

            <!-- Host info -->
            <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
              <h2 class="mb-4 flex items-center gap-2 text-base font-semibold">
                <Monitor class="size-4 text-muted-foreground" />
                {{ t('pages.nodeDetail.host') }}
              </h2>
              <template v-if="connected.hostInfo">
                <div class="flex flex-col gap-3">
                  <div class="flex items-center justify-between">
                    <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.hostInfo.os') }}</span>
                    <span class="text-sm text-foreground">{{ connected.hostInfo.osName }} {{ connected.hostInfo.osVersion }}</span>
                  </div>
                  <Separator class="bg-glass-border" />
                  <div class="flex items-center justify-between">
                    <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.hostInfo.arch') }}</span>
                    <span class="text-sm text-foreground">{{ connected.hostInfo.arch }}</span>
                  </div>
                  <Separator class="bg-glass-border" />
                  <div class="flex items-center justify-between">
                    <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.hostInfo.cpuModel') }}</span>
                    <span class="text-sm text-foreground truncate ml-4 text-right">{{ connected.hostInfo.cpuModel }}</span>
                  </div>
                  <Separator class="bg-glass-border" />
                  <div class="flex items-center justify-between">
                    <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.hostInfo.cores') }}</span>
                    <span class="text-sm text-foreground">{{ connected.hostInfo.cpuPhysicalCores }}P / {{ connected.hostInfo.cpuLogicalCores }}L</span>
                  </div>
                  <Separator class="bg-glass-border" />
                  <div class="flex items-center justify-between">
                    <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.hostInfo.maxFreq') }}</span>
                    <span class="text-sm text-foreground">{{ formatFreq(connected.hostInfo.cpuMaxFreqHz) }}</span>
                  </div>
                  <Separator class="bg-glass-border" />
                  <div class="flex items-center justify-between">
                    <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.hostInfo.java') }}</span>
                    <span class="text-sm text-foreground">{{ connected.hostInfo.javaVersion }}</span>
                  </div>
                  <template v-if="connected.hostInfo.javaVendor && connected.hostInfo.javaVendor !== 'unknown'">
                    <Separator class="bg-glass-border" />
                    <div class="flex items-center justify-between">
                      <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.hostInfo.jvmVendor') }}</span>
                      <span class="text-sm text-foreground truncate ml-4 text-right">{{ connected.hostInfo.javaVendor }}</span>
                    </div>
                  </template>
                  <template v-if="connected.hostInfo.javaRuntime && connected.hostInfo.javaRuntime !== 'unknown'">
                    <Separator class="bg-glass-border" />
                    <div class="flex items-center justify-between">
                      <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.hostInfo.jvmRuntime') }}</span>
                      <span class="text-sm text-foreground truncate ml-4 text-right">{{ connected.hostInfo.javaRuntime }}</span>
                    </div>
                  </template>
                  <template v-if="connected.hostInfo.javaGc && connected.hostInfo.javaGc !== 'unknown'">
                    <Separator class="bg-glass-border" />
                    <div class="flex items-center justify-between">
                      <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.hostInfo.gc') }}</span>
                      <span class="text-sm text-foreground">{{ connected.hostInfo.javaGc }}</span>
                    </div>
                  </template>
                </div>
              </template>
              <p v-else class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.noHostInfo') }}</p>
            </div>
          </div>

          <!-- Labels -->
          <div v-if="connected.labels && Object.keys(connected.labels).length > 0" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
            <h3 class="text-lg font-semibold text-foreground mb-4 flex items-center gap-2">
              <Tag class="size-5 text-muted-foreground" />
              {{ t('pages.nodeDetail.labels') }}
            </h3>
            <div class="flex flex-wrap gap-2">
              <Badge v-for="(value, key) in connected.labels" :key="key" variant="outline" class="text-sm">
                {{ key }}: {{ value }}
              </Badge>
            </div>
          </div>

          <!-- Instances on this node -->
          <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
            <h3 class="text-lg font-semibold text-foreground mb-4 flex items-center gap-2">
              <Box class="size-5 text-muted-foreground" />
              {{ t('pages.nodeDetail.instances') }}
            </h3>
            <div v-if="nodeInstances.length === 0" class="text-sm text-muted-foreground text-center py-6">{{ t('pages.nodeDetail.noInstances') }}</div>
            <div v-else class="overflow-hidden rounded-xl border border-glass-border">
              <div class="flex items-center h-9 px-4 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider">
                <div class="w-44 shrink-0">{{ t('pages.nodeDetail.instColumns.instance') }}</div>
                <div class="w-32 shrink-0">{{ t('pages.nodeDetail.instColumns.group') }}</div>
                <div class="w-24 shrink-0 text-center">{{ t('pages.nodeDetail.instColumns.state') }}</div>
                <div class="w-20 shrink-0 text-right">{{ t('pages.nodeDetail.instColumns.players') }}</div>
                <div class="flex-1 text-right">{{ t('pages.nodeDetail.instColumns.uptime') }}</div>
              </div>
              <div v-for="inst in nodeInstances" :key="inst.id" class="flex items-center h-10 px-4 border-b border-glass-border/50 last:border-0 cursor-pointer hover:bg-glass-hover transition-colors" @click="navigateTo(`/instances/${inst.id}`)">
                <div class="w-44 shrink-0 flex items-center gap-2">
                  <span class="text-sm font-medium text-foreground truncate mono">{{ inst.id }}</span>
                </div>
                <div class="w-32 shrink-0">
                  <NuxtLink :to="`/groups/${inst.group}`" class="text-sm text-primary hover:underline" @click.stop>{{ inst.group }}</NuxtLink>
                </div>
                <div class="w-24 shrink-0 text-center">
                  <StatusBadge :state="inst.state" />
                </div>
                <div class="w-20 shrink-0 text-right text-sm text-foreground tabular-nums">{{ inst.playerCount }}</div>
                <div class="flex-1 text-right text-sm text-muted-foreground tabular-nums">{{ formatUptimeMs(inst.uptimeMs) }}</div>
              </div>
            </div>
          </div>
        </div>
      </template>

      <template v-else-if="activeTab === 'cache'">
        <NodeCachePanel :node-id="nodeId" :cache="cache" :loading="cacheLoading" @refresh="fetchCache" />
      </template>
    </template>

    <!-- Disconnected node -->
    <template v-else-if="node.type === 'DISCONNECTED'">
      <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
        <h2 class="mb-4 text-base font-semibold">{{ t('pages.nodeDetail.nodeInformation') }}</h2>
        <div class="flex flex-col gap-3">
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.disc.status') }}</span>
            <StatusBadge state="OFFLINE" />
          </div>
          <Separator class="bg-glass-border" />
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.disc.firstSeen') }}</span>
            <span class="text-sm text-foreground">{{ new Date(node.firstSeen).toLocaleString() }}</span>
          </div>
          <Separator class="bg-glass-border" />
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.disc.lastSeen') }}</span>
            <span class="text-sm text-foreground">{{ new Date(node.lastSeen).toLocaleString() }}</span>
          </div>
        </div>
      </div>
    </template>

    <!-- Pending node -->
    <template v-else-if="node.type === 'PENDING'">
      <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
        <h2 class="mb-4 text-base font-semibold">{{ t('pages.nodeDetail.pendingNode') }}</h2>
        <div class="flex flex-col gap-3">
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.pending.status') }}</span>
            <StatusBadge tone="primary" :label="t('pages.nodeDetail.pending.pending')" pulse />
          </div>
          <Separator class="bg-glass-border" />
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.pending.expires') }}</span>
            <span class="text-sm text-foreground">{{ new Date(node.expiresAt).toLocaleString() }}</span>
          </div>
          <Separator class="bg-glass-border" />
          <div>
            <span class="text-sm text-muted-foreground">{{ t('pages.nodeDetail.pending.joinToken') }}</span>
            <div class="flex items-center gap-2 mt-2">
              <code class="flex-1 p-3 bg-glass rounded-xl text-sm text-foreground font-mono break-all border border-glass-border">
                {{ node.joinToken }}
              </code>
              <Button
                variant="outline"
                size="icon"
                class="shrink-0 border-glass-border"
                @click="copyToken"
              >
                <Check v-if="copied" class="size-4 text-success" />
                <Copy v-else class="size-4" />
              </Button>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- Danger Zone -->
    <template v-if="node && !loading">
      <!-- Online: Drain -->
      <div v-if="node.type === 'CONNECTED' && node.status === 'ONLINE'" class="rounded-2xl border border-warning/30 bg-warning/5 p-6">
        <div class="flex items-center justify-between">
          <div class="flex items-start gap-3">
            <div class="size-10 rounded-xl bg-warning/20 flex items-center justify-center shrink-0 mt-0.5">
              <Power class="size-5 text-warning" />
            </div>
            <div>
              <h2 class="text-base font-semibold">{{ t('pages.nodeDetail.drainZone.title') }}</h2>
              <p class="mt-1 text-sm text-muted-foreground">{{ t('pages.nodeDetail.drainZone.body') }}</p>
            </div>
          </div>
          <Button
            variant="outline"
            class="ml-4 shrink-0 border-warning/50 text-warning hover:bg-warning/10"
            :disabled="draining"
            @click="requestDrain"
          >
            {{ draining ? t('pages.nodeDetail.draining') : t('pages.nodeDetail.drain') }}
          </Button>
        </div>
      </div>

      <!-- Draining: Undrain -->
      <div v-else-if="node.type === 'CONNECTED' && node.status === 'DRAINING'" class="rounded-2xl border border-warning/30 bg-warning/5 p-6">
        <div class="flex items-center justify-between">
          <div class="flex items-start gap-3">
            <div class="size-10 rounded-xl bg-warning/20 flex items-center justify-center shrink-0 mt-0.5">
              <Power class="size-5 text-warning" />
            </div>
            <div>
              <h2 class="text-base font-semibold">{{ t('pages.nodeDetail.undrainZone.title') }}</h2>
              <p class="mt-1 text-sm text-muted-foreground">{{ t('pages.nodeDetail.undrainZone.body') }}</p>
            </div>
          </div>
          <Button
            variant="outline"
            class="ml-4 shrink-0 border-warning/50 text-warning hover:bg-warning/10"
            :disabled="draining"
            @click="undrainNode"
          >
            {{ draining ? t('pages.nodeDetail.cancelling') : t('pages.nodeDetail.cancelDrain') }}
          </Button>
        </div>
      </div>

      <!-- Online/Draining: Cordon -->
      <div v-if="node.type === 'CONNECTED' && (node.status === 'ONLINE' || node.status === 'DRAINING')" class="rounded-2xl border border-warning/30 bg-warning/5 p-6">
        <div class="flex items-center justify-between">
          <div class="flex items-start gap-3">
            <div class="size-10 rounded-xl bg-warning/20 flex items-center justify-center shrink-0 mt-0.5">
              <ShieldOff class="size-5 text-warning" />
            </div>
            <div>
              <h2 class="text-base font-semibold">{{ t('pages.nodeDetail.cordonZone.title') }}</h2>
              <p class="mt-1 text-sm text-muted-foreground">{{ t('pages.nodeDetail.cordonZone.body') }}</p>
            </div>
          </div>
          <Button
            variant="outline"
            class="ml-4 shrink-0 border-warning/50 text-warning hover:bg-warning/10"
            :disabled="cordoning"
            @click="requestCordon"
          >
            {{ cordoning ? t('pages.nodeDetail.cordoning') : t('pages.nodeDetail.cordon') }}
          </Button>
        </div>
      </div>

      <!-- Cordoned: Uncordon -->
      <div v-else-if="node.type === 'CONNECTED' && node.status === 'CORDONED'" class="rounded-2xl border border-warning/30 bg-warning/5 p-6">
        <div class="flex items-center justify-between">
          <div class="flex items-start gap-3">
            <div class="size-10 rounded-xl bg-warning/20 flex items-center justify-center shrink-0 mt-0.5">
              <ShieldOff class="size-5 text-warning" />
            </div>
            <div>
              <h2 class="text-base font-semibold">{{ t('pages.nodeDetail.uncordonZone.title') }}</h2>
              <p class="mt-1 text-sm text-muted-foreground">{{ t('pages.nodeDetail.uncordonZone.body') }}</p>
            </div>
          </div>
          <Button
            variant="outline"
            class="ml-4 shrink-0 border-warning/50 text-warning hover:bg-warning/10"
            :disabled="cordoning"
            @click="uncordonNode"
          >
            {{ cordoning ? t('pages.nodeDetail.uncordoning') : t('pages.nodeDetail.uncordon') }}
          </Button>
        </div>
      </div>

      <!-- Offline/Pending: Delete -->
      <div v-if="node.type === 'DISCONNECTED' || node.type === 'PENDING'" class="rounded-2xl border border-destructive/30 bg-destructive/5 p-6">
        <div class="flex items-center justify-between">
          <div class="flex items-start gap-3">
            <div class="size-10 rounded-xl bg-destructive/20 flex items-center justify-center shrink-0 mt-0.5">
              <Trash2 class="size-5 text-destructive" />
            </div>
            <div>
              <h2 class="text-base font-semibold">{{ node.type === 'PENDING' ? t('pages.nodeDetail.deleteZone.revokeTitle') : t('pages.nodeDetail.deleteZone.deleteTitle') }}</h2>
              <p class="mt-1 text-sm text-muted-foreground">
                <template v-if="node.type === 'PENDING'">{{ t('pages.nodeDetail.deleteZone.revokeBody') }}</template>
                <template v-else>{{ t('pages.nodeDetail.deleteZone.deleteBody') }}</template>
              </p>
            </div>
          </div>
          <Button
            variant="outline"
            class="ml-4 shrink-0 border-destructive/50 text-destructive hover:bg-destructive/10"
            :disabled="deleting"
            @click="requestDelete"
          >
            <Trash2 class="mr-2 size-4" />
            {{ deleting ? (node.type === 'PENDING' ? t('pages.nodeDetail.revoking') : t('pages.nodeDetail.deleting')) : (node.type === 'PENDING' ? t('pages.nodeDetail.revoke') : t('pages.nodeDetail.delete')) }}
          </Button>
        </div>
      </div>
    </template>
    <!-- Confirm Dialog -->
    <ConfirmDialog
      :open="confirmOpen"
      :title="confirmTitle"
      :description="confirmDesc"
      :confirm-label="confirmLabel"
      :loading="confirmLoading"
      @update:open="confirmOpen = $event"
      @confirm="onConfirm"
    />
  </div>
</template>
