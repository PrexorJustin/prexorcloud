<script setup lang="ts">
import { ArrowLeft, Activity, Settings, Clock, Server, Zap, RefreshCw, Rocket, Box, Pause, Play, RotateCcw, Trash2, Loader2, Paintbrush } from "lucide-vue-next"
import type { Deployment, ServerGroup } from "~/types/api"
import type { Schema } from "@prexorcloud/api-sdk"
import { Badge } from "~/components/ui/badge"
import { StatusBadge } from "~/components/ui/status-badge"
import { Button } from "~/components/ui/button"
import { CodeBlock } from "~/components/ui/code-block"
import { Eyebrow } from "~/components/ui/eyebrow"
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle,
} from "~/components/ui/dialog"
import { Slider } from "~/components/ui/slider"
import { SCALING_MODE_CONFIG, DEPLOY_STATE_CONFIG, INSTANCE_STATE_CONFIG } from "~/lib/constants"
import { formatUptime } from "~/lib/utils"
import { toast } from "vue-sonner"
import MotdEditor from "~/components/groups/MotdEditor.vue";

interface ResolvedData {
  templateChain?: string[]
  resolvedFiles?: number
  resolvedJvmArgs?: string[]
  resolvedEnv?: Record<string, string>
  resolvedConfigPatches?: number
}
const resolved = ref<ResolvedData | null>(null)
async function fetchResolved() {
  try {
    type Loose = { GET: (p: string) => Promise<{ data: unknown }> }
    const c = useApiClient() as unknown as Loose
    const { data } = await c.GET(`/api/v1/groups/${encodeURIComponent(groupName)}/resolved`)
    resolved.value = data as ResolvedData
  } catch { /* leave empty */ }
}

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const groupName = route.params.name as string

const group = ref<ServerGroup | null>(null)
const deployments = ref<Deployment[]>([])
const loading = ref(true)

const instancesStore = useInstancesStore()
const groupsStore = useGroupsStore()

const groupInstances = computed(() =>
  instancesStore.instances.filter(i => i.group === groupName),
)

const isProxyGroup = computed(() => ['velocity', 'bungeecord'].includes(group.value?.platform?.toLowerCase() ?? ''))
const activeTab = ref<'overview' | 'appearance'>('overview')

/** Mandatory + extra templates for this group */
const allTemplates = computed(() => {
  if (!group.value) return []
  const mandatory = ['base', `base-${group.value.platform.toLowerCase()}`, group.value.name]
  return [...mandatory, ...group.value.templates]
})

async function fetchGroup() {
  loading.value = true
  try {
    const client = useApiClient()
    const { data: g } = await client.GET('/api/v1/groups/{name}', { params: { path: { name: groupName } } })
    group.value = (g ?? null) as unknown as ServerGroup | null
    const { data: deps } = await client.GET('/api/v1/groups/{name}/deployments', { params: { path: { name: groupName } } })
    deployments.value = (deps?.data ?? []) as unknown as Deployment[]

  } catch {
    toast.error(t('pages.groupDetail.toast.loadFailedTitle'), { description: t('pages.groupDetail.toast.loadFailedDesc', { name: groupName }) })
    await router.push("/groups")
  } finally {
    loading.value = false
  }
}

async function fetchDeployments() {
  try {
    const { data: deps } = await useApiClient().GET('/api/v1/groups/{name}/deployments', { params: { path: { name: groupName } } })
    deployments.value = (deps?.data ?? []) as unknown as Deployment[]

  } catch { /* ignore */ }
}

onMounted(() => {
  fetchGroup()
  fetchResolved()
  instancesStore.fetchInstances()
  instancesStore.connectSse()
  groupsStore.connectSse()
})

onUnmounted(() => {
  instancesStore.disconnectSse()
  groupsStore.disconnectSse()
})

// Re-fetch group data when store detects GROUP_UPDATED
watch(() => groupsStore.groups.find(g => g.name === groupName), (updated) => {
  if (updated && group.value) {
    group.value = { ...updated } as unknown as ServerGroup
  }
})

// Re-fetch deployments when store signals a deployment event for this group
watch(() => groupsStore.lastDeploymentEvent, (event) => {
  if (event && event.groupName === groupName) fetchDeployments()
})

const startOpen = ref(false)
const startCount = ref(1)
const startLoading = ref(false)

async function startGroup() {
  startLoading.value = true
  try {
    const count = startCount.value
    await useApiClient().POST('/api/v1/groups/{name}/start', { params: { path: { name: groupName } }, body: count > 1 ? { count } : {} })
    toast.success(t('pages.groupDetail.toast.scheduled', { count }, count), { description: t('pages.groupDetail.toast.scheduledDesc', { name: groupName }) })
    startOpen.value = false
    startCount.value = 1
  } catch {
    toast.error(t('pages.groupDetail.toast.startFailedTitle'), { description: t('pages.groupDetail.toast.startFailedDesc', { name: groupName }) })
  } finally {
    startLoading.value = false
  }
}

async function restartGroup() {
  await useApiClient().POST('/api/v1/groups/{name}/restart', { params: { path: { name: groupName } } })
  toast.success(t('pages.groupDetail.toast.restartTitle'), { description: t('pages.groupDetail.toast.restartDesc', { name: groupName }) })
  await fetchGroup()
}

async function deployGroup() {
  const client = useApiClient()
  await client.POST('/api/v1/groups/{name}/deploy', { params: { path: { name: groupName } } })
  toast.success(t('pages.groupDetail.toast.deployTitle'), { description: t('pages.groupDetail.toast.deployDesc', { name: groupName }) })
  const { data: deps } = await client.GET('/api/v1/groups/{name}/deployments', { params: { path: { name: groupName } } })
  deployments.value = (deps?.data ?? []) as unknown as Deployment[]
}

async function pauseDeployment(rev: number) {
  const client = useApiClient()
  await client.POST('/api/v1/groups/{name}/deployments/{rev}/pause', { params: { path: { name: groupName, rev } } })
  toast.success(t('pages.groupDetail.toast.deployPaused'))
  const { data: deps } = await client.GET('/api/v1/groups/{name}/deployments', { params: { path: { name: groupName } } })
  deployments.value = (deps?.data ?? []) as unknown as Deployment[]
}

async function resumeDeployment(rev: number) {
  const client = useApiClient()
  await client.POST('/api/v1/groups/{name}/deployments/{rev}/resume', { params: { path: { name: groupName, rev } } })
  toast.success(t('pages.groupDetail.toast.deployResumed'))
  const { data: deps } = await client.GET('/api/v1/groups/{name}/deployments', { params: { path: { name: groupName } } })
  deployments.value = (deps?.data ?? []) as unknown as Deployment[]
}

async function rollbackDeployment(rev: number) {
  const client = useApiClient()
  await client.POST('/api/v1/groups/{name}/deployments/{rev}/rollback', { params: { path: { name: groupName, rev } } })
  toast.success(t('pages.groupDetail.toast.deployRolledBack'))
  const { data: deps } = await client.GET('/api/v1/groups/{name}/deployments', { params: { path: { name: groupName } } })
  deployments.value = (deps?.data ?? []) as unknown as Deployment[]
}

const confirmOpen = ref(false)
const confirmLoading = ref(false)

async function deleteGroup() {
  confirmLoading.value = true
  try {
    await useApiClient().DELETE('/api/v1/groups/{name}', { params: { path: { name: groupName } } })
    toast.success(t('pages.groupDetail.toast.deletedTitle'), { description: t('pages.groupDetail.toast.deletedDesc', { name: groupName }) })
    await router.push("/groups")
  } catch {
    toast.error(t('pages.groupDetail.toast.deleteFailedTitle'), { description: t('pages.groupDetail.toast.deleteFailedDesc', { name: groupName }) })
  } finally {
    confirmLoading.value = false
    confirmOpen.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <!-- Header -->
    <div class="flex items-center gap-4">
      <Button variant="ghost" size="icon" class="size-9 shrink-0" @click="router.push('/groups')">
        <ArrowLeft class="size-5" />
      </Button>
      <div class="flex-1 min-w-0">
        <p class="eyebrow mb-1">{{ t('pages.groupDetail.group') }}</p>
        <div class="flex items-center gap-3">
          <h1 class="truncate text-2xl font-bold tracking-tight text-gradient-title">{{ groupName }}</h1>
          <StatusBadge v-if="group?.maintenance" tone="warning" :label="t('pages.groupDetail.maintenance')" />
        </div>
        <p v-if="group" class="mt-0.5 text-sm text-muted-foreground">{{ group.platform }} {{ group.platformVersion }}</p>
      </div>
      <div v-if="group" class="flex items-center gap-2 shrink-0">
        <Button variant="outline" class="border-glass-border" @click="startOpen = true">
          <Zap class="size-4 mr-2" /> {{ t('pages.groupDetail.start') }}
        </Button>
        <Button variant="outline" class="border-glass-border" @click="restartGroup">
          <RefreshCw class="size-4 mr-2" /> {{ t('pages.groupDetail.restart') }}
        </Button>
        <Button variant="outline" class="border-glass-border" @click="deployGroup">
          <Rocket class="size-4 mr-2" /> {{ t('pages.groupDetail.deploy') }}
        </Button>
      </div>
    </div>

    <!-- Tab bar (proxy groups only, shown once loaded) -->
    <div v-if="!loading && isProxyGroup" class="flex gap-1 p-1 rounded-xl border border-glass-border bg-glass/60 backdrop-blur-xl w-fit">
      <button
        v-for="tab in [{ id: 'overview', label: t('pages.groupDetail.tabs.overview'), Icon: Activity }, { id: 'appearance', label: t('pages.groupDetail.tabs.appearance'), Icon: Paintbrush }]"
        :key="tab.id"
        class="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition-colors"
        :class="activeTab === tab.id ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground'"
        @click="activeTab = tab.id as 'overview' | 'appearance'"
      >
        <component :is="tab.Icon" class="size-3.5" />{{ tab.label }}
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="grid grid-cols-1 lg:grid-cols-2 gap-5">
      <div v-for="i in 4" :key="i" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6 animate-pulse">
        <div class="h-5 bg-glass rounded w-32 mb-4" />
        <div class="flex flex-col gap-3"><div class="h-4 bg-glass rounded" /><div class="h-4 bg-glass rounded w-3/4" /><div class="h-4 bg-glass rounded w-1/2" /></div>
      </div>
    </div>

    <template v-else-if="group">
      <template v-if="activeTab === 'overview'">
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <!-- Configuration -->
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
          <h3 class="text-base font-semibold text-foreground flex items-center gap-2 mb-4"><Settings class="size-4" /> {{ t('pages.groupDetail.configuration') }}</h3>
          <div class="flex flex-col gap-3">
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.platform') }}</span><span class="text-sm text-foreground">{{ group.platform }} {{ group.platformVersion }}</span></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.templates') }}</span><span class="text-sm text-foreground"><NuxtLink v-for="(tpl, i) in allTemplates" :key="tpl" :to="`/templates/${tpl}`" class="text-primary hover:underline">{{ tpl }}<span v-if="i < allTemplates.length - 1">, </span></NuxtLink></span></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.routing') }}</span><span class="text-sm text-foreground">{{ group.routing }}</span></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.portRange') }}</span><span class="text-sm text-foreground tabular-nums">{{ group.portRangeStart }}–{{ group.portRangeEnd }}</span></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.updateStrategy') }}</span><span class="text-sm text-foreground">{{ group.updateStrategy }}</span></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.memory') }}</span><span class="text-sm text-foreground tabular-nums">{{ group.memoryMb }} MB</span></div>
            <div v-if="group.jvmArgs.length" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.jvmArgs') }}</span><span class="text-sm text-foreground font-mono text-xs truncate ml-4">{{ group.jvmArgs.join(' ') }}</span></div>
            <div v-if="group.maxLifetimeSeconds" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.maxLifetime') }}</span><span class="text-sm text-foreground tabular-nums">{{ group.maxLifetimeSeconds }}s</span></div>
            <div v-if="group.static" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.static') }}</span><span class="text-sm text-foreground">{{ t('pages.groupDetail.yes') }}</span></div>
            <div v-if="group.staticInstanceNames?.length" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.staticInstances') }}</span><span class="text-sm text-foreground">{{ group.staticInstanceNames.join(', ') }}</span></div>
            <div v-if="group.fallbackGroup" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.fallback') }}</span><NuxtLink :to="`/groups/${group.fallbackGroup}`" class="text-sm font-medium text-primary hover:underline">{{ group.fallbackGroup }}</NuxtLink></div>
            <div v-if="group.defaultGroup" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.defaultGroup') }}</span><span class="text-sm text-foreground">{{ t('pages.groupDetail.yes') }}</span></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.priority') }}</span><span class="text-sm text-foreground tabular-nums">{{ group.priority }}</span></div>
            <div v-if="group.maintenance" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.config.status') }}</span><Badge variant="outline" class="text-xs text-warning">{{ t('pages.groupDetail.maintenance') }}</Badge></div>
          </div>
        </div>

        <!-- Scaling -->
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
          <h3 class="text-base font-semibold text-foreground flex items-center gap-2 mb-4"><Activity class="size-4" /> {{ t('pages.groupDetail.scaling') }}</h3>
          <div class="flex flex-col gap-3">
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.scalingFields.mode') }}</span><Badge variant="outline" :class="['text-xs', SCALING_MODE_CONFIG[group.scalingMode]?.color]">{{ SCALING_MODE_CONFIG[group.scalingMode]?.label }}</Badge></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.scalingFields.instances') }}</span><span class="text-sm text-foreground tabular-nums">{{ t('pages.groupDetail.scalingFields.instancesValue', { running: group.runningInstances, min: group.minInstances, max: group.maxInstances }) }}</span></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.scalingFields.players') }}</span><span class="text-sm text-foreground tabular-nums">{{ group.totalPlayers }} / {{ group.maxPlayers }}</span></div>
            <div v-if="group.scalingMode === 'DYNAMIC'" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.scalingFields.scaleUpThreshold') }}</span><span class="text-sm text-foreground tabular-nums">{{ group.scaleUpThreshold }}%</span></div>
            <div v-if="group.scalingMode === 'DYNAMIC'" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.scalingFields.scaleDownAfter') }}</span><span class="text-sm text-foreground tabular-nums">{{ group.scaleDownAfterSeconds }}s</span></div>
            <div v-if="group.scalingMode === 'DYNAMIC'" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.scalingFields.cooldown') }}</span><span class="text-sm text-foreground tabular-nums">{{ group.scaleCooldownSeconds }}s</span></div>
            <div v-if="group.predictiveScaling" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.scalingFields.predictive') }}</span><span class="text-sm text-foreground">{{ t('pages.groupDetail.scalingFields.predictiveValue', { margin: group.scaleUpMargin }) }}</span></div>
            <div v-if="group.predictiveScaling && group.burstCeiling" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.scalingFields.burstCeiling') }}</span><span class="text-sm text-foreground tabular-nums">{{ group.burstCeiling }}</span></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.scalingFields.startupTimeout') }}</span><span class="text-sm text-foreground tabular-nums">{{ group.startupTimeoutSeconds }}s</span></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.scalingFields.shutdownGrace') }}</span><span class="text-sm text-foreground tabular-nums">{{ group.shutdownGraceSeconds }}s</span></div>
          </div>
        </div>
      </div>

      <!-- Live Instances -->
      <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
        <h3 class="text-base font-semibold text-foreground flex items-center gap-2 mb-4"><Box class="size-4" /> {{ t('pages.groupDetail.instances') }}</h3>
        <div v-if="groupInstances.length === 0" class="text-sm text-muted-foreground text-center py-6">{{ t('pages.groupDetail.noInstances') }}</div>
        <div v-else class="overflow-hidden rounded-xl border border-glass-border">
          <div class="flex items-center h-9 px-4 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider">
            <div class="w-44 shrink-0">{{ t('pages.groupDetail.instColumns.instance') }}</div>
            <div class="w-32 shrink-0">{{ t('pages.groupDetail.instColumns.node') }}</div>
            <div class="w-24 shrink-0 text-center">{{ t('pages.groupDetail.instColumns.state') }}</div>
            <div class="w-20 shrink-0 text-right">{{ t('pages.groupDetail.instColumns.players') }}</div>
            <div class="flex-1 text-right">{{ t('pages.groupDetail.instColumns.uptime') }}</div>
          </div>
          <div v-for="inst in groupInstances" :key="inst.id" class="flex items-center h-10 px-4 border-b border-glass-border/50 last:border-0 cursor-pointer hover:bg-glass-hover transition-colors" @click="navigateTo(`/instances/${inst.id}`)">
            <div class="w-44 shrink-0 flex items-center gap-2">
              <span class="text-sm font-medium text-foreground truncate mono">{{ inst.id }}</span>
            </div>
            <div class="w-32 shrink-0">
              <NuxtLink :to="`/nodes/${inst.node}`" class="text-sm text-primary hover:underline" @click.stop>{{ inst.node }}</NuxtLink>
            </div>
            <div class="w-24 shrink-0 text-center">
              <StatusBadge :state="inst.state" />
            </div>
            <div class="w-20 shrink-0 text-right text-sm text-foreground tabular-nums">{{ inst.playerCount }}</div>
            <div class="flex-1 text-right text-sm text-muted-foreground tabular-nums">{{ formatUptime(inst.uptimeMs) }}</div>
          </div>
        </div>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <!-- Node Affinity -->
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
          <h3 class="text-base font-semibold text-foreground flex items-center gap-2 mb-4"><Server class="size-4" /> {{ t('pages.groupDetail.nodeAffinity') }}</h3>
          <div class="flex flex-col gap-3">
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.affinity.affinity') }}</span><span class="text-sm text-foreground">{{ group.nodeAffinity.join(', ') || t('pages.groupDetail.any') }}</span></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.affinity.antiAffinity') }}</span><span class="text-sm text-foreground">{{ group.nodeAntiAffinity.join(', ') || t('pages.groupDetail.none') }}</span></div>
            <div class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.affinity.spread') }}</span><span class="text-sm text-foreground">{{ group.spreadConstraint || t('pages.groupDetail.none') }}</span></div>
            <div v-if="group.dependsOn.length" class="flex justify-between"><span class="text-sm text-muted-foreground">{{ t('pages.groupDetail.affinity.dependsOn') }}</span><span class="text-sm text-foreground">{{ group.dependsOn.join(', ') }}</span></div>
          </div>
        </div>

        <!-- Deployments -->
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
          <h3 class="text-base font-semibold text-foreground flex items-center gap-2 mb-4"><Clock class="size-4" /> {{ t('pages.groupDetail.deployments') }}</h3>
          <div v-if="deployments.length === 0" class="text-sm text-muted-foreground text-center py-6">{{ t('pages.groupDetail.noDeployments') }}</div>
          <div v-else class="flex flex-col gap-2 max-h-80 overflow-auto styled-scrollbar pr-1">
            <div v-for="d in deployments.slice(0, 15)" :key="d.id" class="p-3 rounded-xl border border-glass-border">
              <div class="flex items-center justify-between mb-1">
                <div class="flex items-center gap-2">
                  <span class="text-sm font-medium text-foreground">{{ t('pages.groupDetail.revLabel', { rev: d.revision }) }}</span>
                  <Badge variant="outline" :class="['text-[10px]', DEPLOY_STATE_CONFIG[d.state]?.color ?? 'text-muted-foreground']">
                    {{ DEPLOY_STATE_CONFIG[d.state]?.label ?? d.state }}
                  </Badge>
                </div>
                <span class="text-xs text-muted-foreground tabular-nums">{{ d.updatedInstances }}/{{ d.totalInstances }}</span>
              </div>
              <div class="flex items-center justify-between">
                <span class="text-xs text-muted-foreground">{{ d.trigger }} &middot; {{ d.strategy }}</span>
                <div class="flex items-center gap-1">
                  <button
                    v-if="d.state === 'IN_PROGRESS'"
                    class="p-1 rounded text-muted-foreground hover:text-warning hover:bg-warning/10 transition-colors"
                    :title="t('pages.groupDetail.pause')"
                    @click="pauseDeployment(d.revision)"
                  >
                    <Pause class="size-3.5" />
                  </button>
                  <button
                    v-if="d.state === 'PAUSED'"
                    class="p-1 rounded text-muted-foreground hover:text-success hover:bg-success/10 transition-colors"
                    :title="t('pages.groupDetail.resume')"
                    @click="resumeDeployment(d.revision)"
                  >
                    <Play class="size-3.5" />
                  </button>
                  <button
                    v-if="d.state === 'COMPLETED' || d.state === 'FAILED'"
                    class="p-1 rounded text-muted-foreground hover:text-warning hover:bg-warning/10 transition-colors"
                    :title="t('pages.groupDetail.rollback')"
                    @click="rollbackDeployment(d.revision)"
                  >
                    <RotateCcw class="size-3.5" />
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Resolved configuration — flattened template + JVM + env after inheritance -->
      <div v-if="resolved" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6 space-y-4">
        <h2 class="flex items-center gap-2 text-base font-semibold"><Activity class="size-4" /> {{ t('pages.groupDetail.resolvedConfig') }}</h2>

        <section v-if="resolved.templateChain?.length" class="space-y-2">
          <Eyebrow>{{ t('pages.groupDetail.templateChain') }}</Eyebrow>
          <div class="flex flex-wrap items-center gap-1.5">
            <template v-for="(tpl, i) in resolved.templateChain" :key="tpl">
              <NuxtLink :to="`/templates/${tpl}`" class="inline-flex items-center rounded-md border border-glass-border bg-glass px-2 py-0.5 mono text-[11px] text-primary hover:bg-glass-hover">{{ tpl }}</NuxtLink>
              <span v-if="i < resolved.templateChain.length - 1" class="text-muted-foreground/50">→</span>
            </template>
          </div>
        </section>

        <div class="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <div class="rounded-md border border-glass-border bg-glass/40 px-3 py-2">
            <p class="eyebrow mb-1">{{ t('pages.groupDetail.resolved.files') }}</p>
            <p class="text-lg font-semibold tabular">{{ resolved.resolvedFiles ?? 0 }}</p>
          </div>
          <div class="rounded-md border border-glass-border bg-glass/40 px-3 py-2">
            <p class="eyebrow mb-1">{{ t('pages.groupDetail.resolved.configPatches') }}</p>
            <p class="text-lg font-semibold tabular">{{ resolved.resolvedConfigPatches ?? 0 }}</p>
          </div>
          <div class="rounded-md border border-glass-border bg-glass/40 px-3 py-2">
            <p class="eyebrow mb-1">{{ t('pages.groupDetail.resolved.jvmArgs') }}</p>
            <p class="text-lg font-semibold tabular">{{ resolved.resolvedJvmArgs?.length ?? 0 }}</p>
          </div>
        </div>

        <section v-if="resolved.resolvedJvmArgs?.length" class="space-y-2">
          <Eyebrow>{{ t('pages.groupDetail.resolved.jvmArgsResolved') }}</Eyebrow>
          <CodeBlock :code="resolved.resolvedJvmArgs.join(' ')" :show-line-numbers="false" />
        </section>

        <section v-if="resolved.resolvedEnv && Object.keys(resolved.resolvedEnv).length" class="space-y-2">
          <Eyebrow>{{ t('pages.groupDetail.resolved.envResolved') }}</Eyebrow>
          <div class="space-y-1">
            <div v-for="(v, k) in resolved.resolvedEnv" :key="k" class="flex justify-between rounded-md border border-glass-border bg-glass/40 px-3 py-1.5 mono text-xs">
              <span class="text-muted-foreground">{{ k }}</span>
              <span>{{ v }}</span>
            </div>
          </div>
        </section>
      </div>

      <!-- Danger Zone -->
      <div class="rounded-2xl border border-destructive/30 bg-destructive/5 p-6">
        <div class="flex items-center justify-between">
          <div class="flex items-start gap-3">
            <div class="size-10 rounded-xl bg-destructive/20 flex items-center justify-center shrink-0 mt-0.5">
              <Trash2 class="size-5 text-destructive" />
            </div>
            <div>
              <h2 class="text-base font-semibold">{{ t('pages.groupDetail.dangerTitle') }}</h2>
              <p class="mt-1 text-sm text-muted-foreground">{{ t('pages.groupDetail.dangerBody') }}</p>
            </div>
          </div>
          <Button
            variant="outline"
            class="shrink-0 ml-4 border-destructive/50 text-destructive hover:bg-destructive/10"
            @click="confirmOpen = true"
          >
            <Trash2 class="size-4 mr-2" />
            {{ t('pages.groupDetail.delete') }}
          </Button>
        </div>
      </div>
      </template>
      <MotdEditor v-else-if="activeTab === 'appearance'" :group="group" @saved="fetchGroup" />
    </template>

    <!-- Start Instances Dialog -->
    <Dialog :open="startOpen" @update:open="(v: boolean) => { startOpen = v; if (!v) startCount = 1 }">
      <DialogContent class="bg-popover backdrop-blur-xl border-glass-border rounded-2xl sm:max-w-sm [&>button:last-child]:hidden overflow-hidden p-0">
        <div class="relative h-24 bg-glass/40 overflow-hidden">
          <div class="absolute inset-0 bg-dot-pattern" />
          <div class="absolute inset-0 bg-linear-to-b from-transparent via-transparent to-popover" />
          <div class="absolute inset-0 flex flex-col items-center justify-center gap-2">
            <div class="size-10 rounded-xl bg-primary/10 border border-primary/20 flex items-center justify-center">
              <Zap class="size-5 text-primary" />
            </div>
            <div class="text-center">
              <DialogTitle class="text-sm font-bold text-foreground">{{ t('pages.groupDetail.startDialog.title') }}</DialogTitle>
              <DialogDescription class="text-xs text-muted-foreground mt-0.5">{{ groupName }}</DialogDescription>
            </div>
          </div>
        </div>
        <div class="px-6 pb-6 flex flex-col gap-5 pt-4">
          <div class="flex flex-col gap-1">
            <span class="text-xs font-medium text-muted-foreground uppercase tracking-wider">{{ t('pages.groupDetail.startDialog.count') }}</span>
            <Slider
              :model-value="[startCount]"
              :min="1"
              :max="50"
              :step="1"
              show-tooltip
              :format-tooltip="(v: number) => `${v}`"
              @update:model-value="(v: number[] | undefined) => { if (v?.[0] !== undefined) startCount = v[0] }"
            />
          </div>
          <DialogFooter class="flex-row! gap-2 pt-4 border-t border-glass-border">
            <div class="flex-1" />
            <Button variant="outline" class="border-glass-border" @click="startOpen = false">{{ t('common.cancel') }}</Button>
            <Button class="bg-primary hover:bg-primary/90 text-primary-foreground" :disabled="startLoading" @click="startGroup">
              <Loader2 v-if="startLoading" class="size-4 mr-1.5 animate-spin" />
              {{ startLoading ? t('pages.groupDetail.startDialog.starting') : startCount > 1 ? t('pages.groupDetail.startDialog.startN', { count: startCount }) : t('pages.groupDetail.startDialog.startOne') }}
            </Button>
          </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>

    <ConfirmDialog
      :open="confirmOpen"
      :title="t('pages.groupDetail.confirmDeleteTitle')"
      :description="t('pages.groupDetail.confirmDeleteDesc', { name: groupName })"
      :confirm-label="t('pages.groupDetail.delete')"
      :loading="confirmLoading"
      @update:open="confirmOpen = $event"
      @confirm="deleteGroup"
    />
  </div>
</template>
