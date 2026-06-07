<script setup lang="ts">
import {
  Activity,
  AlertTriangle,
  Boxes,
  CheckCircle2,
  Database,
  Download,
  ExternalLink,
  GitBranch,
  PackageSearch,
  Puzzle,
  RefreshCw,
  Server,
  Trash2,
  Upload,
  XCircle,
} from 'lucide-vue-next'
import { Badge } from '~/components/ui/badge'
import { StatusBadge } from '~/components/ui/status-badge'
import { StatusDot } from '~/components/ui/status-dot'
import type { StatusDotTone } from '~/components/ui/status-dot'
import { Button } from '~/components/ui/button'
import { toast } from 'vue-sonner'
import { resolveIcon } from '~/lib/icons'
import type { ModuleHealthInfo, ModuleResourceInfo, PlatformCloudModule, RegistryModuleEntry } from '~/types/api'
import { getAuthToken } from '~/lib/auth-storage'

const { t } = useI18n()
const moduleStore = useModuleStore()

const uploading = ref(false)
const refreshing = ref(false)
const deleting = ref(false)
const installingDep = ref<string | null>(null)
const deleteTarget = ref<PlatformCloudModule | null>(null)
const showDeleteDialog = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const activeView = ref<'modules' | 'capabilities' | 'extensions'>('modules')
const resolverTarget = ref('server/paper')
const resolverVersion = ref('1.20.4')

onMounted(async () => {
  await refreshAll()
  await resolveVariants()
})

const { search, filteredItems: filteredModules } = useFilteredList(
  () => moduleStore.platformModules,
  {
    searchFields: m => [
      m.moduleId,
      m.version,
      m.state,
      m.backend.entrypoint,
      ...m.capabilities.provides.map(capability => capability.id),
      ...m.capabilities.requires.map(capability => capability.id),
    ],
  },
)

const activeCount = computed(() =>
  moduleStore.platformModules.filter(mod => mod.state === 'ACTIVE').length,
)

const unresolvedCount = computed(() =>
  moduleStore.platformModules.reduce((count, mod) => count + mod.unresolvedRequirements.length, 0),
)

const capabilityRows = computed(() =>
  moduleStore.capabilityGraph?.modules.flatMap(mod => [
    ...mod.provides.map(capability => ({
      moduleId: mod.moduleId,
      type: 'Provides',
      id: capability.id,
      version: capability.version,
      peer: capability.active ? t('pages.modules.peer.activeBinding') : t('pages.modules.peer.inactive'),
      problem: null as string | null,
    })),
    ...mod.requires.map(capability => ({
      moduleId: mod.moduleId,
      type: 'Requires',
      id: capability.id,
      version: capability.versionRange,
      peer: capability.binding ? `${capability.binding.moduleId}@${capability.binding.version}` : t('pages.modules.peer.unbound'),
      problem: null as string | null,
    })),
    ...mod.unresolvedRequirements.map(requirement => ({
      moduleId: mod.moduleId,
      type: 'Unresolved',
      id: requirement.capabilityId,
      version: requirement.versionRange,
      peer: t('pages.modules.peer.unbound'),
      problem: requirement.reason,
    })),
  ]) ?? [],
)

const extensionRows = computed(() =>
  moduleStore.platformExtensions.flatMap(extension =>
    extension.variants.map(variant => ({
      moduleId: extension.moduleId,
      extensionId: extension.id,
      target: extension.target,
      activation: extension.activation,
      conflicts: extension.conflicts,
      ...variant,
    })),
  ),
)

async function refreshAll() {
  refreshing.value = true
  try {
    await moduleStore.refreshPlatformState()
    // The registry catalog backs the dependency-resolution hint: when a module has an
    // unresolved requirement, we look for a catalog module that provides that capability.
    await Promise.all([moduleStore.fetchModuleDiagnostics(), moduleStore.fetchRegistryCatalog()])
  }
  finally {
    refreshing.value = false
  }
}

async function uploadModule() {
  fileInputRef.value?.click()
}

async function handleFileUpload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  if (!file.name.endsWith('.jar')) {
    toast.error(t('pages.modules.toast.onlyJar'))
    return
  }

  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)

    const config = useRuntimeConfig()
    const apiBase = config.public.apiBase as string
    const token = getAuthToken()

    const response = await fetch(`${apiBase}/api/v1/modules/platform/upload`, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData,
    })

    if (!response.ok) {
      const err = await response.json().catch(() => ({ error: { message: t('pages.modules.toast.uploadFailed') } }))
      throw new Error(err.error?.message || err.message || t('pages.modules.toast.uploadFailedStatus', { status: response.status }))
    }

    const installed = await response.json().catch(() => null) as PlatformCloudModule | null
    toast.success(t('pages.modules.toast.installedTitle', { id: installed?.moduleId ?? file.name.replace('.jar', '') }))
    await refreshAll()
  }
  catch (e) {
    toast.error(t('pages.modules.toast.installFailedTitle'), { description: e instanceof Error ? e.message : t('pages.modules.toast.installFailedDesc') })
  }
  finally {
    uploading.value = false
    input.value = ''
  }
}

function confirmDelete(mod: PlatformCloudModule) {
  deleteTarget.value = mod
  showDeleteDialog.value = true
}

async function deleteModule() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    const moduleId = deleteTarget.value.moduleId
    await moduleStore.uninstallPlatformModule(moduleId)
    toast.success(t('pages.modules.toast.removedTitle', { id: moduleId }))
    showDeleteDialog.value = false
    deleteTarget.value = null
  }
  catch (e) {
    toast.error(t('pages.modules.toast.removeFailedTitle'), { description: e instanceof Error ? e.message : t('pages.modules.toast.removeFailedDesc') })
  }
  finally {
    deleting.value = false
  }
}

async function resolveVariants() {
  await moduleStore.resolvePlatformExtensions(resolverTarget.value, resolverVersion.value)
}

function frontendFor(mod: PlatformCloudModule) {
  return moduleStore.frontendByModuleId.get(mod.moduleId)?.frontend ?? null
}

function storageLimitLabel(limit: number, unit: string) {
  return limit > 0 ? `${limit.toLocaleString()} ${unit}` : t('pages.modules.unlimited')
}

function displayName(mod: PlatformCloudModule) {
  return frontendFor(mod)?.displayName ?? mod.moduleId
}

function stateTone(state: string): StatusDotTone {
  if (state === 'ACTIVE') return 'success'
  if (state === 'FAILED') return 'destructive'
  return 'muted'
}

function stateLabel(state: string): string {
  return state.charAt(0) + state.slice(1).toLowerCase()
}

function healthFor(moduleId: string): ModuleHealthInfo | undefined {
  return moduleStore.moduleHealth[moduleId]
}

function resourcesFor(moduleId: string): ModuleResourceInfo | undefined {
  return moduleStore.moduleResources[moduleId]
}

// Only show a health dot when the module reports an actual signal — a module that doesn't
// implement healthCheck() reports UNKNOWN, which we suppress to avoid a sea of grey dots.
function showHealthDot(moduleId: string): boolean {
  const health = healthFor(moduleId)
  return !!health && health.monitoringEnabled && health.status !== 'UNKNOWN'
}

function healthTone(status?: string): StatusDotTone {
  if (status === 'HEALTHY') return 'success'
  if (status === 'DEGRADED') return 'warning'
  if (status === 'UNHEALTHY') return 'destructive'
  return 'muted'
}

function healthTitle(moduleId: string): string {
  const health = healthFor(moduleId)
  if (!health) return ''
  const label = health.status.charAt(0) + health.status.slice(1).toLowerCase()
  const detail = health.detail ? ` — ${health.detail}` : ''
  return t('pages.modules.healthTitle', { label, detail })
}

// Dependency resolution: find a not-yet-installed catalog module that provides the missing
// capability. Best-effort matching by capability id — the controller's resolver decides the
// actual version compatibility once the suggested module is installed.
function providerFor(capabilityId: string): RegistryModuleEntry | undefined {
  return moduleStore.registryModules.find(
    entry => !entry.installed && (entry.provides ?? []).some(capability => capability.id === capabilityId),
  )
}

async function installDependency(capabilityId: string, provider: RegistryModuleEntry) {
  installingDep.value = capabilityId
  try {
    await moduleStore.installFromRegistry(provider.moduleId, undefined, provider.registryUrl)
    toast.success(t('pages.modules.toast.installingDepTitle', { id: provider.moduleId }), { description: t('pages.modules.toast.installingDepDesc', { capability: capabilityId }) })
    await refreshAll()
  }
  catch (e) {
    toast.error(t('pages.modules.toast.installFailedTitle'), { description: e instanceof Error ? e.message : t('pages.modules.toast.installDepFailedDesc') })
  }
  finally {
    installingDep.value = null
  }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <PageHeader :title="t('pages.modules.title')" :description="t('pages.modules.description')">
      <template #actions>
        <input ref="fileInputRef" type="file" accept=".jar" class="hidden" @change="handleFileUpload" >
        <Button variant="outline" :disabled="refreshing" @click="refreshAll">
          <RefreshCw class="mr-2 size-4" :class="refreshing ? 'animate-spin' : ''" />
          {{ t('pages.modules.refresh') }}
        </Button>
        <Button :disabled="uploading" @click="uploadModule">
          <Upload class="mr-2 size-4" />
          {{ uploading ? t('pages.modules.installing') : t('pages.modules.installModule') }}
        </Button>
      </template>
    </PageHeader>

    <div class="grid grid-cols-1 md:grid-cols-3 gap-3">
      <div class="rounded-lg border border-glass-border bg-glass/50 p-4">
        <div class="flex items-center justify-between">
          <span class="text-sm text-muted-foreground">{{ t('pages.modules.summary.installed') }}</span>
          <Puzzle class="size-4 text-muted-foreground" />
        </div>
        <p class="mt-2 text-2xl font-semibold tabular-nums">{{ moduleStore.platformModules.length }}</p>
      </div>
      <div class="rounded-lg border border-glass-border bg-glass/50 p-4">
        <div class="flex items-center justify-between">
          <span class="text-sm text-muted-foreground">{{ t('pages.modules.summary.active') }}</span>
          <CheckCircle2 class="size-4 text-success" />
        </div>
        <p class="mt-2 text-2xl font-semibold tabular-nums">{{ activeCount }}</p>
      </div>
      <div class="rounded-lg border border-glass-border bg-glass/50 p-4">
        <div class="flex items-center justify-between">
          <span class="text-sm text-muted-foreground">{{ t('pages.modules.summary.unresolved') }}</span>
          <AlertTriangle class="size-4 text-warning" />
        </div>
        <p class="mt-2 text-2xl font-semibold tabular-nums">{{ unresolvedCount }}</p>
      </div>
    </div>

    <div
      v-if="moduleStore.platformError"
      class="flex items-center gap-2 rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
    >
      <AlertTriangle class="size-4" />
      {{ moduleStore.platformError }}
    </div>

    <div class="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
      <FilterToolbar
        v-model:search="search"
        :search-placeholder="t('pages.modules.searchPlaceholder')"
        :show-view-toggle="false"
        :count="moduleStore.platformModules.length"
        :count-label="t('pages.modules.countLabel')"
        class="flex-1"
      />

      <div class="inline-flex h-10 rounded-lg border border-glass-border bg-glass/40 p-1">
        <button
          type="button"
          :class="[
            'inline-flex items-center gap-2 rounded-md px-3 text-sm transition-colors',
            activeView === 'modules' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground',
          ]"
          @click="activeView = 'modules'"
        >
          <Boxes class="size-4" /> {{ t('pages.modules.views.modules') }}
        </button>
        <button
          type="button"
          :class="[
            'inline-flex items-center gap-2 rounded-md px-3 text-sm transition-colors',
            activeView === 'capabilities' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground',
          ]"
          @click="activeView = 'capabilities'"
        >
          <GitBranch class="size-4" /> {{ t('pages.modules.views.capabilities') }}
        </button>
        <button
          type="button"
          :class="[
            'inline-flex items-center gap-2 rounded-md px-3 text-sm transition-colors',
            activeView === 'extensions' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground',
          ]"
          @click="activeView = 'extensions'"
        >
          <PackageSearch class="size-4" /> {{ t('pages.modules.views.extensions') }}
        </button>
      </div>
    </div>

    <template v-if="activeView === 'modules'">
      <div v-if="filteredModules.length > 0" class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        <div
          v-for="mod in filteredModules"
          :key="mod.moduleId"
          class="group rounded-lg border border-glass-border bg-glass/60 p-5 transition-colors hover:border-glass-border-hover"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="flex min-w-0 items-center gap-3">
              <div class="size-10 shrink-0 rounded-lg bg-primary/10 border border-primary/20 flex items-center justify-center">
                <component :is="(frontendFor(mod) && resolveIcon(frontendFor(mod)?.icon)) ?? Puzzle" class="size-5 text-primary" />
              </div>
              <div class="min-w-0">
                <h3 class="truncate font-semibold text-foreground">{{ displayName(mod) }}</h3>
                <p class="truncate text-xs text-muted-foreground font-mono">{{ mod.moduleId }}@{{ mod.version }}</p>
              </div>
            </div>
            <div class="flex shrink-0 items-center gap-2">
              <span
                v-if="showHealthDot(mod.moduleId)"
                :title="healthTitle(mod.moduleId)"
                :aria-label="healthTitle(mod.moduleId)"
                class="inline-flex"
              >
                <StatusDot :tone="healthTone(healthFor(mod.moduleId)?.status)" />
              </span>
              <StatusBadge :tone="stateTone(mod.state)" :label="stateLabel(mod.state)" :pulse="mod.state === 'ACTIVE'" />
            </div>
          </div>

          <div class="mt-4 grid grid-cols-2 gap-2 text-xs">
            <div class="rounded-md border border-glass-border bg-background/20 px-3 py-2">
              <span class="text-muted-foreground">{{ t('pages.modules.storage.mongo') }}</span>
              <p class="mt-1 font-medium" :class="mod.storage.mongoAvailable ? 'text-success' : 'text-muted-foreground'">
                {{ mod.storage.mongo ? (mod.storage.mongoAvailable ? t('pages.modules.storage.available') : t('pages.modules.storage.requested')) : t('pages.modules.storage.off') }}
              </p>
              <p v-if="mod.storage.mongo" class="mt-0.5 truncate text-[11px] text-muted-foreground">
                {{ storageLimitLabel(mod.storage.mongoDocumentLimit, t('pages.modules.storage.docs')) }}
              </p>
            </div>
            <div class="rounded-md border border-glass-border bg-background/20 px-3 py-2">
              <span class="text-muted-foreground">{{ t('pages.modules.storage.redis') }}</span>
              <p class="mt-1 font-medium" :class="mod.storage.redisAvailable ? 'text-success' : 'text-muted-foreground'">
                {{ mod.storage.redis ? (mod.storage.redisAvailable ? t('pages.modules.storage.available') : t('pages.modules.storage.requested')) : t('pages.modules.storage.off') }}
              </p>
              <p v-if="mod.storage.redis" class="mt-0.5 truncate text-[11px] text-muted-foreground">
                {{ storageLimitLabel(mod.storage.redisKeyLimit, t('pages.modules.storage.keys')) }}
              </p>
            </div>
          </div>

          <div
            v-if="resourcesFor(mod.moduleId)?.trackingEnabled"
            class="mt-3 rounded-md border border-glass-border bg-background/20 px-3 py-2 text-xs"
          >
            <div class="flex items-center justify-between">
              <span class="flex items-center gap-1.5 text-muted-foreground">
                <Activity class="size-3.5" /> {{ t('pages.modules.resources') }}
              </span>
              <Badge
                v-if="resourcesFor(mod.moduleId)?.quotaEvaluation?.anyExceeded"
                variant="destructive"
                class="gap-1"
              >
                <AlertTriangle class="size-3" /> {{ t('pages.modules.quotaExceeded') }}
              </Badge>
            </div>
            <div class="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 font-mono text-[11px] text-muted-foreground">
              <span>
                <span class="text-foreground">{{ resourcesFor(mod.moduleId)?.liveThreads ?? 0 }}</span> {{ t('pages.modules.threads') }}
              </span>
              <template v-if="resourcesFor(mod.moduleId)?.quotaEvaluation">
                <span :class="resourcesFor(mod.moduleId)?.quotaEvaluation?.cpuExceeded ? 'text-destructive' : ''">
                  <span :class="resourcesFor(mod.moduleId)?.quotaEvaluation?.cpuExceeded ? 'text-destructive font-medium' : 'text-foreground'">{{ resourcesFor(mod.moduleId)?.quotaEvaluation?.cpuMillisPerMinute }}</span>
                  {{ t('pages.modules.msCpuMin') }}
                </span>
                <span :class="resourcesFor(mod.moduleId)?.quotaEvaluation?.allocationExceeded ? 'text-destructive' : ''">
                  <span :class="resourcesFor(mod.moduleId)?.quotaEvaluation?.allocationExceeded ? 'text-destructive font-medium' : 'text-foreground'">{{ resourcesFor(mod.moduleId)?.quotaEvaluation?.allocatedMbPerMinute }}</span>
                  {{ t('pages.modules.mbMin') }}
                </span>
              </template>
            </div>
          </div>

          <div class="mt-4 space-y-2">
            <div class="flex flex-wrap gap-1.5">
              <Badge v-for="capability in mod.capabilities.provides" :key="`p-${capability.id}`" variant="outline">
                + {{ capability.id }} {{ capability.version }}
              </Badge>
              <Badge v-for="capability in mod.capabilities.requires" :key="`r-${capability.id}`" variant="secondary">
                {{ t('pages.modules.needs') }} {{ capability.id }} {{ capability.versionRange }}
              </Badge>
            </div>
            <div v-if="mod.unresolvedRequirements.length" class="space-y-1">
              <div
                v-for="requirement in mod.unresolvedRequirements"
                :key="requirement.capabilityId"
                class="rounded-md bg-destructive/10 px-2 py-1 text-xs"
              >
                <div class="flex items-center gap-2 text-destructive">
                  <XCircle class="size-3.5 shrink-0" />
                  <span class="min-w-0 truncate">{{ requirement.capabilityId }}: {{ requirement.reason }}</span>
                </div>
                <div
                  v-if="providerFor(requirement.capabilityId)"
                  class="mt-1 flex items-center justify-between gap-2 pl-5"
                >
                  <span class="min-w-0 truncate text-[11px] text-muted-foreground">
                    <span class="font-mono text-foreground">{{ providerFor(requirement.capabilityId)!.moduleId }}</span> {{ t('pages.modules.providesThis') }}
                  </span>
                  <Button
                    size="sm"
                    variant="outline"
                    class="h-6 shrink-0 px-2 text-[11px]"
                    :disabled="installingDep === requirement.capabilityId"
                    @click="installDependency(requirement.capabilityId, providerFor(requirement.capabilityId)!)"
                  >
                    <Download class="mr-1 size-3" :class="installingDep === requirement.capabilityId ? 'animate-pulse' : ''" />
                    {{ t('pages.modules.install') }}
                  </Button>
                </div>
              </div>
            </div>
          </div>

          <div v-if="frontendFor(mod)" class="mt-4 flex flex-wrap gap-1.5">
            <NuxtLink
              v-for="route in frontendFor(mod)!.routes.filter(r => r.nav)"
              :key="route.path"
              :to="`/modules/${mod.moduleId}${route.path === '/' ? '' : route.path}`"
              class="inline-flex items-center gap-1 rounded-md border border-glass-border bg-glass px-2 py-0.5 text-xs text-muted-foreground transition-colors hover:bg-glass-hover hover:text-foreground"
            >
              <ExternalLink class="size-2.5" />
              {{ route.title }}
            </NuxtLink>
          </div>
          <div v-else class="mt-4 flex items-center gap-2 text-xs text-muted-foreground">
            <Server class="size-3.5" />
            {{ t('pages.modules.backendOnly') }}
          </div>

          <div class="mt-4 flex items-center justify-between border-t border-glass-border pt-3">
            <span class="truncate text-xs text-muted-foreground font-mono">{{ mod.backend.entrypoint }}</span>
            <Button variant="ghost" size="sm" class="text-destructive hover:text-destructive" @click="confirmDelete(mod)">
              <Trash2 class="size-3.5 mr-1.5" />
              {{ t('pages.modules.remove') }}
            </Button>
          </div>
        </div>
      </div>

      <EmptyState
        v-else
        :icon="Puzzle"
        :title="search ? t('pages.modules.emptyMatchesTitle') : t('pages.modules.emptyTitle')"
        :description="search ? t('pages.modules.emptyMatchesHint') : t('pages.modules.emptyHint')"
      >
        <Button v-if="!search" variant="outline" @click="uploadModule">
          <Upload class="mr-2 size-4" /> {{ t('pages.modules.installModule') }}
        </Button>
      </EmptyState>
    </template>

    <template v-else-if="activeView === 'capabilities'">
      <div class="rounded-lg border border-glass-border overflow-hidden">
        <div class="grid grid-cols-12 gap-3 border-b border-glass-border bg-glass/50 px-4 py-2 text-xs font-medium text-muted-foreground">
          <span class="col-span-3">{{ t('pages.modules.capColumns.module') }}</span>
          <span class="col-span-2">{{ t('pages.modules.capColumns.type') }}</span>
          <span class="col-span-3">{{ t('pages.modules.capColumns.capability') }}</span>
          <span class="col-span-2">{{ t('pages.modules.capColumns.version') }}</span>
          <span class="col-span-2">{{ t('pages.modules.capColumns.binding') }}</span>
        </div>
        <div
          v-for="row in capabilityRows"
          :key="`${row.moduleId}-${row.type}-${row.id}-${row.version}`"
          class="grid grid-cols-12 gap-3 border-b border-glass-border/60 px-4 py-3 text-sm last:border-b-0"
        >
          <span class="col-span-3 truncate font-mono text-xs">{{ row.moduleId }}</span>
          <span class="col-span-2">
            <Badge :variant="row.type === 'Unresolved' ? 'destructive' : 'secondary'">{{ t(`pages.modules.capTypes.${row.type.toLowerCase()}`) }}</Badge>
          </span>
          <span class="col-span-3 truncate">{{ row.id }}</span>
          <span class="col-span-2 truncate font-mono text-xs text-muted-foreground">{{ row.version }}</span>
          <span class="col-span-2 truncate text-xs" :class="row.problem ? 'text-destructive' : 'text-muted-foreground'">
            {{ row.problem ?? row.peer }}
          </span>
        </div>
        <div v-if="capabilityRows.length === 0" class="px-4 py-8 text-center text-sm text-muted-foreground">
          {{ t('pages.modules.noCapabilities') }}
        </div>
      </div>
    </template>

    <template v-else>
      <div class="flex flex-col gap-3 rounded-lg border border-glass-border bg-glass/40 p-4 md:flex-row md:items-end">
        <div class="flex-1">
          <label class="text-xs text-muted-foreground" for="resolver-target">{{ t('pages.modules.target') }}</label>
          <select
            id="resolver-target"
            v-model="resolverTarget"
            class="mt-1 h-10 w-full rounded-md border border-glass-border bg-background px-3 text-sm text-foreground"
          >
            <option value="server/paper">server/paper</option>
            <option value="proxy/velocity">proxy/velocity</option>
          </select>
        </div>
        <div class="flex-1">
          <label class="text-xs text-muted-foreground" for="resolver-version">{{ t('pages.modules.runtimeVersion') }}</label>
          <input
            id="resolver-version"
            v-model="resolverVersion"
            class="mt-1 h-10 w-full rounded-md border border-glass-border bg-background px-3 text-sm text-foreground"
          >
        </div>
        <Button variant="outline" @click="resolveVariants">
          <GitBranch class="size-4 mr-2" />
          {{ t('pages.modules.resolve') }}
        </Button>
      </div>

      <div class="grid grid-cols-1 xl:grid-cols-2 gap-4">
        <div class="rounded-lg border border-glass-border overflow-hidden">
          <div class="flex items-center gap-2 border-b border-glass-border bg-glass/50 px-4 py-3">
            <PackageSearch class="size-4 text-muted-foreground" />
            <h2 class="font-medium">{{ t('pages.modules.extensionRegistry') }}</h2>
          </div>
          <div
            v-for="row in extensionRows"
            :key="`${row.extensionId}-${row.id}`"
            class="border-b border-glass-border/60 px-4 py-3 last:border-b-0"
          >
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <p class="truncate font-medium">{{ row.extensionId }}</p>
                <p class="truncate text-xs text-muted-foreground font-mono">{{ row.moduleId }} - {{ row.id }}</p>
              </div>
              <Badge variant="secondary">{{ row.target }}</Badge>
            </div>
            <div class="mt-2 grid grid-cols-2 gap-2 text-xs text-muted-foreground">
              <span class="truncate">{{ t('pages.modules.ext.mc', { range: row.mcVersionRange }) }}</span>
              <span class="truncate">{{ t('pages.modules.ext.runtime', { version: row.runtimeApiVersion }) }}</span>
              <span class="col-span-2 truncate font-mono">{{ row.installPath }}/{{ row.artifact }}</span>
            </div>
          </div>
          <div v-if="extensionRows.length === 0" class="px-4 py-8 text-center text-sm text-muted-foreground">
            {{ t('pages.modules.noExtensions') }}
          </div>
        </div>

        <div class="rounded-lg border border-glass-border overflow-hidden">
          <div class="flex items-center gap-2 border-b border-glass-border bg-glass/50 px-4 py-3">
            <Database class="size-4 text-muted-foreground" />
            <h2 class="font-medium">{{ t('pages.modules.resolvedVariants') }}</h2>
          </div>
          <div
            v-for="variant in moduleStore.resolvedExtensions"
            :key="`${variant.extensionId}-${variant.variantId}`"
            class="border-b border-glass-border/60 px-4 py-3 last:border-b-0"
          >
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <p class="truncate font-medium">{{ variant.extensionId }}</p>
                <p class="truncate text-xs text-muted-foreground font-mono">{{ variant.moduleId }} - {{ variant.variantId }}</p>
              </div>
              <Badge variant="outline">{{ variant.activation }}</Badge>
            </div>
            <div class="mt-2 grid grid-cols-2 gap-2 text-xs text-muted-foreground">
              <span class="truncate">{{ t('pages.modules.ext.mc', { range: variant.mcVersionRange }) }}</span>
              <span class="truncate">{{ t('pages.modules.ext.runtime', { version: variant.runtimeApiVersion }) }}</span>
              <span class="col-span-2 truncate font-mono">{{ variant.installPath }}/{{ variant.artifact }}</span>
            </div>
          </div>
          <div v-if="moduleStore.resolvedExtensions.length === 0" class="px-4 py-8 text-center text-sm text-muted-foreground">
            {{ t('pages.modules.noVariants') }}
          </div>
        </div>
      </div>
    </template>

    <ConfirmDialog
      :open="showDeleteDialog"
      :title="t('pages.modules.confirmRemoveTitle')"
      :description="t('pages.modules.confirmRemoveDesc', { id: deleteTarget?.moduleId })"
      :confirm-label="deleting ? t('pages.modules.removing') : t('pages.modules.remove')"
      @update:open="showDeleteDialog = $event"
      @confirm="deleteModule"
    />
  </div>
</template>
