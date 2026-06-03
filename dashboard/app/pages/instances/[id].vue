<script setup lang="ts">
import { ArrowLeft, Box, Square, Trash2, Zap, Activity, Cpu, Globe, Network, Layers, Puzzle } from "lucide-vue-next"
import { toast } from "vue-sonner"
import type { ServerInstance, InstanceMetrics, ProxyMetrics } from "~/types/api"
import { StatusBadge } from "~/components/ui/status-badge"
import { Button } from "~/components/ui/button"
import { CodeBlock } from "~/components/ui/code-block"
import { Eyebrow } from "~/components/ui/eyebrow"
import { Sparkline } from "~/components/ui/sparkline"
import { formatUptime } from "~/lib/utils"
import { useMetricsTimeseries } from "~/composables/useMetricsTimeseries"

interface CompositionData {
  templates?: Array<{ name: string; hash?: string; source?: string }>
  extensions?: Array<{ id: string; module?: string; installPath?: string }>
  jvmArgs?: string[]
  env?: Record<string, string>
}
const composition = shallowRef<CompositionData | null>(null)
const compositionLoading = ref(false)
async function fetchComposition() {
  compositionLoading.value = true
  try {
    type Loose = { GET: (p: string) => Promise<{ data: unknown }> }
    const c = useApiClient() as unknown as Loose
    const { data } = await c.GET(`/api/v1/services/${encodeURIComponent(instanceId)}/composition`)
    composition.value = (data ?? null) as CompositionData | null
  } catch { /* leave empty — section just won't show */ }
  finally { compositionLoading.value = false }
}

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const instanceId = route.params.id as string

const instance = shallowRef<ServerInstance | null>(null)
const metrics = shallowRef<InstanceMetrics | null>(null)
const proxyMetrics = shallowRef<ProxyMetrics | null>(null)
const isProxy = ref(false)
const loading = ref(true)
const stopping = ref(false)
const forceStopping = ref(false)
const confirmOpen = ref(false)
const deleting = ref(false)

const store = useInstancesStore()
const tsi = useMetricsTimeseries({ scope: { kind: 'instance', id: instanceId }, window: '1h', buckets: 60 })

// Local uptime ticker — increments every second between metric refreshes
const uptimeTick = ref(0)
let uptimeTimer: ReturnType<typeof setInterval>

async function fetchInstance() {
  loading.value = true
  try {
    const { data } = await useApiClient().GET('/api/v1/services/{id}', { params: { path: { id: instanceId } } })
    instance.value = data as ServerInstance
    uptimeTick.value = instance.value.uptimeMs
  } catch {
    toast.error(t('pages.instanceDetail.toast.loadFailedTitle'), {
      description: t('pages.instanceDetail.toast.loadFailedDesc', { id: instanceId }),
    })
    await router.push("/instances")
  } finally {
    loading.value = false
  }
}

const displayUptime = computed(() => uptimeTick.value)

const metricsResolved = ref(false)
let lastMetricsFetch = 0
let metricsFetchPending = false

async function fetchMetrics() {
  // Throttle: skip if last fetch was <8s ago
  const now = Date.now()
  if (metricsResolved.value && now - lastMetricsFetch < 8000) return
  if (metricsFetchPending) return
  metricsFetchPending = true
  lastMetricsFetch = now

  try {
    const client = useApiClient()
    if (metricsResolved.value) {
      if (isProxy.value) {
        const { data } = await client.GET('/api/v1/services/{id}/proxy-metrics', { params: { path: { id: instanceId } } })
        proxyMetrics.value = data as ProxyMetrics
      } else {
        const { data } = await client.GET('/api/v1/services/{id}/metrics', { params: { path: { id: instanceId } } })
        metrics.value = data as InstanceMetrics
      }
      return
    }
    // First call: try both in parallel to avoid unnecessary 404s
    const [gameResult, proxyResult] = await Promise.allSettled([
      client.GET('/api/v1/services/{id}/metrics', { params: { path: { id: instanceId } } }),
      client.GET('/api/v1/services/{id}/proxy-metrics', { params: { path: { id: instanceId } } }),
    ])
    if (gameResult.status === 'fulfilled') {
      metrics.value = (gameResult.value.data ?? null) as InstanceMetrics | null
      isProxy.value = false
      metricsResolved.value = true
    } else if (proxyResult.status === 'fulfilled') {
      proxyMetrics.value = (proxyResult.value.data ?? null) as ProxyMetrics | null
      isProxy.value = true
      metricsResolved.value = true
    }
  } catch { /* no metrics yet */ }
  finally { metricsFetchPending = false }
}

onMounted(() => {
  fetchInstance()
  fetchMetrics()
  fetchComposition()
  store.fetchInstances()
  store.connectSse()
  uptimeTimer = setInterval(() => {
    if (instance.value && (instance.value.state === 'RUNNING' || instance.value.state === 'STARTING' || instance.value.state === 'DRAINING')) {
      uptimeTick.value += 1000
    }
  }, 1000)
})

onUnmounted(() => {
  clearInterval(uptimeTimer)
  store.disconnectSse()
})

// Live-update instance state from SSE — use a getter that only tracks this instance
watch(
  () => {
    const inst = store.instances.find(i => i.id === instanceId)
    // Return a snapshot of the fields we care about to avoid spurious triggers
    return inst ? `${inst.state}:${inst.playerCount}` : null
  },
  () => {
    const updated = store.instances.find(i => i.id === instanceId)
    if (updated && instance.value) {
      instance.value = { ...updated } as ServerInstance
      if (updated.uptimeMs) uptimeTick.value = updated.uptimeMs
    }
  },
)

// Game-server metrics are inlined in the SSE event — apply them directly instead
// of refetching on every tick. Proxy metrics aren't on the bus yet, so the proxy
// branch still falls back to a fetch (rate-limited inside fetchMetrics).
watch(() => store.lastInstanceMetrics, (ev) => {
  if (!ev || ev.instanceId !== instanceId) return
  if (isProxy.value) {
    fetchMetrics()
    return
  }
  metrics.value = {
    instanceId: ev.instanceId,
    tps1m: ev.tps1m,
    tps5m: ev.tps5m,
    tps15m: ev.tps15m,
    msptAvg: ev.msptAvg,
    heapUsedMb: ev.heapUsedMb,
    heapMaxMb: ev.heapMaxMb,
    heapCommittedMb: metrics.value?.heapCommittedMb ?? 0,
    gcCollections: ev.gcCollections,
    gcTimeMs: ev.gcTimeMs,
    threadCount: ev.threadCount,
    daemonThreadCount: metrics.value?.daemonThreadCount ?? 0,
    playerCount: ev.playerCount,
    maxPlayers: ev.maxPlayers,
    worldCount: ev.worldCount,
    totalEntities: ev.totalEntities,
    totalChunks: ev.totalChunks,
    worlds: ev.worlds ?? [],
    serverVersion: ev.serverVersion,
    pluginCount: ev.pluginCount,
    uptimeMs: metrics.value?.uptimeMs ?? 0,
    collectedAt: ev.timestamp ?? new Date().toISOString(),
  }
  metricsResolved.value = true
})

const isAlive = computed(() =>
  instance.value && (instance.value.state === "RUNNING" || instance.value.state === "STARTING" || instance.value.state === "DRAINING"),
)

function tpsColor(tps: number): string {
  if (tps >= 19.5) return "text-success"
  if (tps >= 17) return "text-warning"
  return "text-destructive"
}

const heapPercent = computed(() => {
  if (!metrics.value || !metrics.value.heapMaxMb) return 0
  return Math.round((metrics.value.heapUsedMb / metrics.value.heapMaxMb) * 100)
})

const proxyMemPercent = computed(() => {
  if (!proxyMetrics.value || !proxyMetrics.value.proxyMemoryMaxMb) return 0
  return Math.round((proxyMetrics.value.proxyMemoryUsedMb / proxyMetrics.value.proxyMemoryMaxMb) * 100)
})

const proxyPingEntries = computed(() => {
  if (!proxyMetrics.value?.playerPings) return []
  return [...proxyMetrics.value.playerPings].sort((a, b) => b.ping - a.ping)
})

const avgPing = computed(() => {
  if (proxyPingEntries.value.length === 0) return 0
  const total = proxyPingEntries.value.reduce((sum, p) => sum + p.ping, 0)
  return Math.round(total / proxyPingEntries.value.length)
})

async function stopInstance() {
  stopping.value = true
  try {
    await store.stopInstance(instanceId)
    await fetchInstance()
  } catch {
    toast.error(t('pages.instanceDetail.toast.stopFailedTitle'), {
      description: t('pages.instanceDetail.toast.stopFailedDesc', { id: instanceId }),
    })
  } finally {
    stopping.value = false
  }
}

async function forceStopInstance() {
  forceStopping.value = true
  try {
    await store.forceStopInstance(instanceId)
    await fetchInstance()
  } catch {
    toast.error(t('pages.instanceDetail.toast.forceStopFailedTitle'), {
      description: t('pages.instanceDetail.toast.forceStopFailedDesc', { id: instanceId, node: instance.value?.node }),
    })
  } finally {
    forceStopping.value = false
  }
}

async function onConfirmDelete() {
  deleting.value = true
  try {
    await store.deleteInstance(instanceId)
    await router.push("/instances")
  } catch {
    toast.error(t('pages.instanceDetail.toast.deleteFailedTitle'), {
      description: t('pages.instanceDetail.toast.deleteFailedDesc', { id: instanceId }),
    })
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <!-- Header -->
    <div class="flex items-center gap-4">
      <Button variant="ghost" size="icon" class="size-9 shrink-0" @click="router.push('/instances')">
        <ArrowLeft class="size-5" />
      </Button>
      <div class="flex-1 min-w-0">
        <p class="eyebrow mb-1">{{ t('pages.instanceDetail.instance') }}</p>
        <div class="flex items-center gap-3">
          <h1 class="truncate text-2xl font-bold tracking-tight text-gradient-title mono">{{ instanceId }}</h1>
          <StatusBadge v-if="instance" :state="instance.state" :pulse="instance.state === 'RUNNING'" />
        </div>
        <p v-if="instance" class="mt-0.5 text-sm text-muted-foreground">
          {{ t('pages.instanceDetail.on') }} <NuxtLink :to="`/nodes/${instance.node}`" class="text-foreground hover:underline">{{ instance.node }}</NuxtLink>
        </p>
      </div>
      <div v-if="instance" class="flex shrink-0 items-center gap-2">
        <Button
          v-if="instance.state === 'RUNNING' || instance.state === 'STARTING'"
          variant="outline"
          class="border-warning/50 text-warning hover:bg-warning/10"
          :disabled="stopping"
          @click="stopInstance"
        >
          <Square class="mr-2 size-4" />
          {{ stopping ? t('pages.instanceDetail.stopping') : t('pages.instanceDetail.stop') }}
        </Button>
        <Button
          v-if="instance.state === 'RUNNING' || instance.state === 'STARTING' || instance.state === 'STOPPING'"
          variant="outline"
          class="border-destructive/50 text-destructive hover:bg-destructive/10"
          :disabled="forceStopping"
          @click="forceStopInstance"
        >
          <Zap class="mr-2 size-4" />
          {{ forceStopping ? t('pages.instanceDetail.killing') : t('pages.instanceDetail.forceStop') }}
        </Button>
        <Button
          v-if="instance.state === 'STOPPED' || instance.state === 'CRASHED' || instance.state === 'SCHEDULED'"
          variant="outline"
          class="border-destructive/50 text-destructive hover:bg-destructive/10"
          :disabled="deleting"
          @click="confirmOpen = true"
        >
          <Trash2 class="mr-2 size-4" />
          {{ t('pages.instanceDetail.delete') }}
        </Button>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="grid grid-cols-1 lg:grid-cols-2 gap-5">
      <div v-for="i in 2" :key="i" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6 animate-pulse">
        <div class="h-5 bg-glass rounded w-32 mb-4" />
        <div class="flex flex-col gap-3"><div class="h-4 bg-glass rounded" /><div class="h-4 bg-glass rounded w-3/4" /><div class="h-4 bg-glass rounded w-1/2" /></div>
      </div>
    </div>

    <div v-else-if="instance" class="flex flex-col gap-5 flex-1 min-h-0">
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <!-- Instance Info -->
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
          <h2 class="mb-4 flex items-center gap-2 text-base font-semibold"><Box class="size-4" /> {{ t('pages.instanceDetail.instance') }}</h2>
          <div class="flex flex-col gap-3">
            <div class="flex justify-between">
              <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.info.group') }}</span>
              <NuxtLink :to="`/groups/${instance.group}`" class="text-sm font-medium text-primary hover:underline">{{ instance.group }}</NuxtLink>
            </div>
            <div class="flex justify-between">
              <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.info.node') }}</span>
              <NuxtLink :to="`/nodes/${instance.node}`" class="text-sm font-medium text-primary hover:underline">{{ instance.node }}</NuxtLink>
            </div>
            <div class="flex justify-between">
              <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.info.port') }}</span>
              <span class="text-sm text-foreground tabular-nums">{{ instance.port }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.info.players') }}</span>
              <span class="text-sm text-foreground tabular-nums">{{ instance.playerCount }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.info.uptime') }}</span>
              <span class="text-sm text-foreground tabular-nums">{{ formatUptime(displayUptime) }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.info.deployment') }}</span>
              <span class="text-sm text-foreground tabular-nums">{{ t('pages.instanceDetail.revLabel', { rev: instance.deploymentRevision }) }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.info.started') }}</span>
              <span class="text-sm text-foreground">{{ new Date(instance.startedAt).toLocaleString() }}</span>
            </div>
          </div>
        </div>

        <!-- Server Metrics (game servers) -->
        <div v-if="!isProxy" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
          <h2 class="mb-4 flex items-center gap-2 text-base font-semibold"><Activity class="size-4" /> {{ t('pages.instanceDetail.serverMetrics') }}</h2>
          <template v-if="metrics">
            <div class="flex flex-col gap-3">
              <div>
                <div class="flex justify-between items-center mb-1">
                  <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.metrics.tps') }}</span>
                  <div class="flex items-center gap-2">
                    <span :class="['text-sm font-bold tabular-nums', tpsColor(metrics.tps1m)]">{{ metrics.tps1m.toFixed(1) }}</span>
                    <span class="text-xs text-muted-foreground tabular-nums">{{ metrics.tps5m.toFixed(1) }} / {{ metrics.tps15m.toFixed(1) }}</span>
                  </div>
                </div>
                <Sparkline v-if="tsi.loaded.value" :data="tsi.series.tps1m ?? []" :height="20" tone="success" />
              </div>
              <div>
                <div class="flex justify-between mb-1">
                  <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.metrics.mspt') }}</span>
                  <span :class="['text-sm font-medium tabular-nums', metrics.msptAvg < 50 ? 'text-success' : metrics.msptAvg < 100 ? 'text-warning' : 'text-destructive']">{{ metrics.msptAvg.toFixed(1) }}ms</span>
                </div>
                <Sparkline v-if="tsi.loaded.value" :data="tsi.series.msptAvg ?? []" :height="20" tone="warning" />
              </div>
              <div>
                <div class="flex justify-between mb-1.5">
                  <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.metrics.heap') }}</span>
                  <span class="text-sm text-foreground tabular-nums">{{ metrics.heapUsedMb }} / {{ metrics.heapMaxMb }} MB</span>
                </div>
                <div class="h-2 rounded-full bg-glass overflow-hidden">
                  <div class="h-full rounded-full transition-all duration-500" :class="heapPercent > 85 ? 'bg-destructive' : heapPercent > 70 ? 'bg-warning' : 'bg-success'" :style="{ width: `${heapPercent}%` }" />
                </div>
                <Sparkline v-if="tsi.loaded.value" :data="tsi.series.heapUsedMb ?? []" :height="20" tone="primary" class="mt-1.5" />
              </div>
              <div class="flex justify-between">
                <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.metrics.gc') }}</span>
                <span class="text-sm text-foreground tabular-nums">{{ t('pages.instanceDetail.metrics.gcValue', { collections: metrics.gcCollections, ms: metrics.gcTimeMs }) }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.metrics.threads') }}</span>
                <span class="text-sm text-foreground tabular-nums">{{ metrics.threadCount }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.metrics.version') }}</span>
                <span class="text-sm text-foreground">{{ metrics.serverVersion }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.metrics.plugins') }}</span>
                <span class="text-sm text-foreground tabular-nums">{{ metrics.pluginCount }}</span>
              </div>
            </div>
          </template>
          <div v-else class="flex flex-col items-center justify-center text-center py-8">
            <Cpu class="size-8 text-muted-foreground/30 mb-2" />
            <p class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.noMetrics') }}</p>
            <p class="text-xs text-muted-foreground/60 mt-1">{{ t('pages.instanceDetail.noMetricsHintServer') }}</p>
          </div>
        </div>

        <!-- Proxy Metrics -->
        <div v-else class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
          <h2 class="mb-4 flex items-center gap-2 text-base font-semibold"><Network class="size-4" /> {{ t('pages.instanceDetail.proxyMetrics') }}</h2>
          <template v-if="proxyMetrics">
            <div class="flex flex-col gap-3">
              <div class="flex justify-between">
                <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.proxy.players') }}</span>
                <span class="text-sm font-bold text-foreground tabular-nums">{{ proxyMetrics.totalNetworkPlayers }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.proxy.avgPing') }}</span>
                <span :class="['text-sm font-medium tabular-nums', avgPing < 50 ? 'text-success' : avgPing < 150 ? 'text-warning' : 'text-destructive']">{{ avgPing }}ms</span>
              </div>
              <div>
                <div class="flex justify-between mb-1.5">
                  <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.proxy.memory') }}</span>
                  <span class="text-sm text-foreground tabular-nums">{{ proxyMetrics.proxyMemoryUsedMb }} / {{ proxyMetrics.proxyMemoryMaxMb }} MB</span>
                </div>
                <div class="h-2 rounded-full bg-glass overflow-hidden">
                  <div class="h-full rounded-full transition-all duration-500" :class="proxyMemPercent > 85 ? 'bg-destructive' : proxyMemPercent > 70 ? 'bg-warning' : 'bg-success'" :style="{ width: `${proxyMemPercent}%` }" />
                </div>
              </div>
              <div class="flex justify-between">
                <span class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.proxy.uptime') }}</span>
                <span class="text-sm text-foreground">{{ formatUptime(proxyMetrics.proxyUptimeMs) }}</span>
              </div>
              <!-- Top pings -->
              <div v-if="proxyPingEntries.length > 0">
                <p class="eyebrow mt-1 mb-2">{{ t('pages.instanceDetail.proxy.playerLatency') }}</p>
                <div class="flex flex-col gap-1 max-h-40 overflow-auto styled-scrollbar pr-1">
                  <div v-for="entry in proxyPingEntries.slice(0, 20)" :key="entry.uuid" class="flex justify-between items-center gap-2 px-2 py-1 rounded-lg">
                    <span class="text-xs text-foreground truncate" :title="entry.uuid">{{ entry.username }}</span>
                    <span :class="['text-xs tabular-nums font-medium shrink-0', entry.ping < 50 ? 'text-success' : entry.ping < 150 ? 'text-warning' : 'text-destructive']">{{ entry.ping }}ms</span>
                  </div>
                </div>
              </div>
            </div>
          </template>
          <div v-else class="flex flex-col items-center justify-center text-center py-8">
            <Network class="size-8 text-muted-foreground/30 mb-2" />
            <p class="text-sm text-muted-foreground">{{ t('pages.instanceDetail.noMetrics') }}</p>
            <p class="text-xs text-muted-foreground/60 mt-1">{{ t('pages.instanceDetail.noMetricsHintProxy') }}</p>
          </div>
        </div>
      </div>

      <!-- Worlds -->
      <div v-if="metrics && metrics.worlds.length > 0" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
        <h2 class="mb-4 flex items-center gap-2 text-base font-semibold"><Globe class="size-4" /> {{ t('pages.instanceDetail.worlds') }}</h2>
        <div class="overflow-hidden rounded-xl border border-glass-border">
          <div class="flex items-center h-9 px-4 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider">
            <div class="flex-1">{{ t('pages.instanceDetail.worldColumns.world') }}</div>
            <div class="w-28 text-center">{{ t('pages.instanceDetail.worldColumns.environment') }}</div>
            <div class="w-20 text-right">{{ t('pages.instanceDetail.worldColumns.entities') }}</div>
            <div class="w-20 text-right">{{ t('pages.instanceDetail.worldColumns.chunks') }}</div>
            <div class="w-20 text-right">{{ t('pages.instanceDetail.worldColumns.players') }}</div>
          </div>
          <div v-for="world in metrics.worlds" :key="world.name" class="flex items-center h-10 px-4 border-b border-glass-border/50 last:border-0">
            <div class="flex-1 text-sm font-medium text-foreground truncate">{{ world.name }}</div>
            <div class="w-28 text-center text-xs text-muted-foreground">{{ world.environment }}</div>
            <div class="w-20 text-right text-sm text-foreground tabular-nums">{{ world.entityCount.toLocaleString() }}</div>
            <div class="w-20 text-right text-sm text-foreground tabular-nums">{{ world.chunkCount.toLocaleString() }}</div>
            <div class="w-20 text-right text-sm text-foreground tabular-nums">{{ world.playerCount }}</div>
          </div>
        </div>
      </div>

      <!-- Composition — resolved templates + extensions for this instance. -->
      <div v-if="composition" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6 space-y-4">
        <h2 class="flex items-center gap-2 text-base font-semibold"><Puzzle class="size-4" /> {{ t('pages.instanceDetail.composition') }}</h2>

        <section v-if="composition.templates?.length" class="space-y-2">
          <Eyebrow><Layers class="mr-1 inline size-3" /> {{ t('pages.instanceDetail.templateChain') }}</Eyebrow>
          <div class="space-y-1">
            <div
              v-for="tpl in composition.templates"
              :key="tpl.name"
              class="flex items-center justify-between rounded-md border border-glass-border bg-glass/40 px-3 py-2 text-sm"
            >
              <div class="flex items-center gap-2">
                <NuxtLink :to="`/templates/${tpl.name}`" class="mono text-primary hover:underline">{{ tpl.name }}</NuxtLink>
                <StatusBadge v-if="tpl.source === 'primary'" tone="primary" :label="t('pages.instanceDetail.primary')" />
                <StatusBadge v-else tone="muted" :label="t('pages.instanceDetail.inherited')" />
              </div>
              <span v-if="tpl.hash" class="mono text-[10px] text-muted-foreground">{{ tpl.hash }}</span>
            </div>
          </div>
        </section>

        <section v-if="composition.extensions?.length" class="space-y-2">
          <Eyebrow>{{ t('pages.instanceDetail.extensions') }}</Eyebrow>
          <div class="flex flex-wrap gap-1.5">
            <span
              v-for="x in composition.extensions"
              :key="x.id"
              class="inline-flex items-center gap-1.5 rounded-md border border-glass-border bg-glass px-2 py-0.5 mono text-[10px] text-muted-foreground"
            >
              <Puzzle class="size-3" /> {{ x.id }}
              <span v-if="x.module" class="text-muted-foreground/60">· {{ x.module }}</span>
            </span>
          </div>
        </section>

        <section v-if="composition.jvmArgs?.length" class="space-y-2">
          <Eyebrow>{{ t('pages.instanceDetail.jvmArgs') }}</Eyebrow>
          <CodeBlock :code="composition.jvmArgs.join(' ')" :show-line-numbers="false" />
        </section>

        <section v-if="composition.env && Object.keys(composition.env).length" class="space-y-2">
          <Eyebrow>{{ t('pages.instanceDetail.environment') }}</Eyebrow>
          <div class="space-y-1">
            <div v-for="(v, k) in composition.env" :key="k" class="flex justify-between rounded-md border border-glass-border bg-glass/40 px-3 py-1.5 mono text-xs">
              <span class="text-muted-foreground">{{ k }}</span>
              <span>{{ v }}</span>
            </div>
          </div>
        </section>
      </div>

      <!-- Console -->
      <InstancesInstanceConsole v-if="isAlive" :instance-id="instanceId" />
    </div>

    <ConfirmDialog
      :open="confirmOpen"
      :title="t('pages.instanceDetail.confirmDeleteTitle')"
      :description="t('pages.instanceDetail.confirmDeleteDesc', { id: instanceId })"
      :confirm-label="t('pages.instanceDetail.delete')"
      :loading="deleting"
      @update:open="confirmOpen = $event"
      @confirm="onConfirmDelete"
    />
  </div>
</template>
