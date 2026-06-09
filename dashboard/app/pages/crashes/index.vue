<script setup lang="ts">
import { AlertTriangle, Clock, Box, Server, Layers, Hash, Share2 } from "lucide-vue-next"
import { StatusBadge } from "~/components/ui/status-badge"
import { DetailSheet } from "~/components/ui/detail-sheet"
import { TerminalBlock } from "~/components/ui/terminal-block"
import { Eyebrow } from "~/components/ui/eyebrow"
import { Sparkline, type SparklineTone } from "~/components/ui/sparkline"
import type { CrashRecord } from "~/types/api"
import { formatUptime } from "~/lib/utils"

const store = useCrashesStore()
const systemStore = useSystemStore()
const { t } = useI18n()
const sharing = ref(false)
const shareEnabled = computed(() => (systemStore.settings as { shareEnabled?: boolean })?.shareEnabled === true)

async function shareSelectedCrash() {
  if (!selectedCrash.value || sharing.value) return
  sharing.value = true
  try {
    await store.shareCrash(selectedCrash.value.id)
  } finally {
    sharing.value = false
  }
}

const classFilters = computed(() => [
  { key: "ALL", label: t("pages.crashes.filters.all"), icon: AlertTriangle },
  { key: "OOM", label: t("pages.crashes.filters.oom"), icon: AlertTriangle },
  { key: "ERROR", label: t("pages.crashes.filters.error"), icon: AlertTriangle },
  { key: "SIGKILL", label: t("pages.crashes.filters.sigkill"), icon: AlertTriangle },
  { key: "SIGTERM", label: t("pages.crashes.filters.sigterm"), icon: AlertTriangle },
])

const classConfig = computed<Record<string, { label: string; tone: "destructive" | "warning" | "muted" }>>(() => ({
  OOM:     { label: t("pages.crashes.class.oom"),     tone: "destructive" },
  ERROR:   { label: t("pages.crashes.class.error"),   tone: "destructive" },
  SIGKILL: { label: t("pages.crashes.class.sigkill"), tone: "warning" },
  SIGTERM: { label: t("pages.crashes.class.sigterm"), tone: "warning" },
}))

const { search, activeFilters, filteredItems: filteredCrashes, toggleFilter } = useFilteredList(
  () => store.crashes,
  {
    searchFields: c => [c.instanceId, c.group, c.node],
    filterField: c => c.classification ?? 'UNKNOWN',
    defaultView: 'table',
  },
)

onMounted(() => {
  store.fetchCrashes()
  store.fetchTrend('24h', 24)
  if (Object.keys(systemStore.settings).length === 0) systemStore.fetchAll()
})

const trendCounts = computed(() => store.trend?.buckets.map(b => b.count) ?? [])
const trendTotal = computed(() => store.trend?.total ?? 0)
const trendTone = computed<SparklineTone>(() => trendTotal.value > 0 ? 'destructive' : 'muted')

const currentPage = computed(() => Math.floor(store.offset / store.pageSize))
const canPrev = computed(() => store.offset > 0)
const canNext = computed(() => store.hasMore)

function prevPage() {
  if (canPrev.value) store.fetchCrashes(store.offset - store.pageSize)
}
function nextPage() {
  if (canNext.value) store.fetchCrashes(store.offset + store.pageSize)
}

// Drawer pattern (replaces the legacy Dialog).
const selectedCrash = ref<CrashRecord | null>(null)
const sheetOpen = computed({
  get: () => selectedCrash.value !== null,
  set: (v) => { if (!v) selectedCrash.value = null },
})
const loadingDetail = ref(false)

async function openCrash(crash: CrashRecord) {
  selectedCrash.value = crash
  if (!crash.logTail) {
    loadingDetail.value = true
    const full = await store.fetchCrash(crash.id)
    if (full) selectedCrash.value = full
    loadingDetail.value = false
  }
}

function classOf(c: string | null | undefined) {
  const key = c ?? ''
  return classConfig.value[key] ?? { label: key || t("pages.crashes.class.unknown"), tone: "muted" as const }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <PageHeader :title="t('pages.crashes.title')" :description="t('pages.crashes.description')">
      <template #actions>
        <div
          v-if="trendCounts.length > 0"
          class="flex items-center gap-3 px-3 py-2 rounded-xl border border-glass-border bg-glass/60 backdrop-blur-xl"
          :title="t('pages.crashes.trendTooltip', { count: trendTotal }, trendTotal)"
        >
          <div class="text-right">
            <div class="text-xs uppercase tracking-wider text-muted-foreground">{{ t('pages.crashes.last24h') }}</div>
            <div class="text-lg font-semibold tabular-nums leading-tight">{{ trendTotal }}</div>
          </div>
          <Sparkline :data="trendCounts" :tone="trendTone" :height="40" class="w-32" />
        </div>
      </template>
    </PageHeader>

    <FilterToolbar
      v-model:search="search"
      :filters="classFilters"
      :active-filters="activeFilters"
      :search-placeholder="t('pages.crashes.searchPlaceholder')"
      :show-view-toggle="false"
      @toggle-filter="toggleFilter"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="5" />

    <EmptyState
      v-else-if="filteredCrashes.length === 0"
      :icon="AlertTriangle"
      :title="t('pages.crashes.emptyTitle')"
      :description="search || !activeFilters.has('ALL') ? t('pages.crashes.emptyFilterHint') : t('pages.crashes.emptyHint')"
    />

    <template v-else>
      <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border overflow-hidden">
        <div class="flex items-center h-10 px-4 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider">
          <div class="w-44 shrink-0">{{ t('pages.crashes.columns.instance') }}</div>
          <div class="w-32 shrink-0">{{ t('pages.crashes.columns.group') }}</div>
          <div class="w-32 shrink-0">{{ t('pages.crashes.columns.node') }}</div>
          <div class="w-28 shrink-0 text-center">{{ t('pages.crashes.columns.classification') }}</div>
          <div class="w-20 shrink-0 text-right">{{ t('pages.crashes.columns.exit') }}</div>
          <div class="w-24 shrink-0 text-right">{{ t('pages.crashes.columns.uptime') }}</div>
          <div class="flex-1 text-right">{{ t('pages.crashes.columns.time') }}</div>
        </div>
        <div
          v-for="crash in filteredCrashes"
          :key="crash.id"
          class="flex items-center h-12 px-4 border-b border-glass-border/50 last:border-0 cursor-pointer transition-colors hover:bg-glass-hover select-none"
          @click="openCrash(crash)"
        >
          <div class="w-44 shrink-0 pr-2 min-w-0">
            <div class="text-sm font-medium text-foreground truncate">{{ crash.instanceId }}</div>
            <div class="text-xs text-muted-foreground truncate" :title="crash.causeSummary">{{ crash.causeSummary }}</div>
          </div>
          <div class="w-32 shrink-0 text-sm text-muted-foreground truncate">{{ crash.group }}</div>
          <div class="w-32 shrink-0 text-sm text-muted-foreground truncate">{{ crash.node }}</div>
          <div class="w-28 shrink-0 text-center">
            <StatusBadge
              :tone="classConfig[crash.classification ?? '']?.tone ?? 'muted'"
              :label="classConfig[crash.classification ?? '']?.label ?? (crash.classification ?? t('pages.crashes.class.unknown'))"
            />
          </div>
          <div class="w-20 shrink-0 text-right text-sm text-foreground tabular-nums">{{ crash.exitCode }}</div>
          <div class="w-24 shrink-0 text-right text-sm text-muted-foreground tabular-nums">{{ formatUptime(crash.uptimeMs) }}</div>
          <div class="flex-1 text-right text-sm text-muted-foreground">{{ new Date(crash.crashedAt).toLocaleString() }}</div>
        </div>
      </div>

      <div class="flex items-center justify-between">
        <span class="text-sm text-muted-foreground">
          {{ t('pages.common.showing', { from: store.offset + 1, to: store.offset + filteredCrashes.length }) }}
        </span>
        <div class="flex gap-2">
          <button
            :disabled="!canPrev"
            class="px-3 py-1.5 text-sm rounded-lg border border-glass-border bg-glass/60 backdrop-blur-xl transition-colors hover:bg-glass-hover disabled:opacity-40 disabled:cursor-not-allowed"
            @click="prevPage"
          >
            {{ t('pages.common.previous') }}
          </button>
          <button
            :disabled="!canNext"
            class="px-3 py-1.5 text-sm rounded-lg border border-glass-border bg-glass/60 backdrop-blur-xl transition-colors hover:bg-glass-hover disabled:opacity-40 disabled:cursor-not-allowed"
            @click="nextPage"
          >
            {{ t('pages.common.next') }}
          </button>
        </div>
      </div>
    </template>

    <DetailSheet
      :open="sheetOpen"
      :title="selectedCrash?.instanceId"
      :eyebrow="t('pages.crashes.eyebrow')"
      size="lg"
      @update:open="sheetOpen = $event"
    >
      <template v-if="selectedCrash" #status>
        <StatusBadge :tone="classOf(selectedCrash.classification).tone" :label="classOf(selectedCrash.classification).label" />
      </template>

      <template v-if="selectedCrash && shareEnabled" #actions>
        <button
          :disabled="sharing"
          class="inline-flex items-center gap-2 px-3 py-1.5 text-sm rounded-lg border border-glass-border bg-glass/60 backdrop-blur-xl transition-colors hover:bg-glass-hover disabled:opacity-50"
          @click="shareSelectedCrash"
        >
          <Share2 class="size-4" />
          {{ t('pages.crashes.share') }}
        </button>
      </template>

      <div v-if="selectedCrash" class="space-y-5 text-sm">
        <section class="grid grid-cols-2 gap-3">
          <div class="flex items-center justify-between rounded-md border border-glass-border bg-glass/40 px-3 py-2">
            <span class="flex items-center gap-2 text-muted-foreground"><Layers class="size-3.5" /> {{ t('pages.crashes.detail.group') }}</span>
            <NuxtLink :to="`/groups/${selectedCrash.group}`" class="mono text-primary hover:underline">{{ selectedCrash.group }}</NuxtLink>
          </div>
          <div class="flex items-center justify-between rounded-md border border-glass-border bg-glass/40 px-3 py-2">
            <span class="flex items-center gap-2 text-muted-foreground"><Server class="size-3.5" /> {{ t('pages.crashes.detail.node') }}</span>
            <NuxtLink :to="`/nodes/${selectedCrash.node}`" class="mono text-primary hover:underline">{{ selectedCrash.node }}</NuxtLink>
          </div>
          <div class="flex items-center justify-between rounded-md border border-glass-border bg-glass/40 px-3 py-2">
            <span class="flex items-center gap-2 text-muted-foreground"><Hash class="size-3.5" /> {{ t('pages.crashes.detail.exitCode') }}</span>
            <span class="tabular">{{ selectedCrash.exitCode }}</span>
          </div>
          <div class="flex items-center justify-between rounded-md border border-glass-border bg-glass/40 px-3 py-2">
            <span class="flex items-center gap-2 text-muted-foreground"><Clock class="size-3.5" /> {{ t('pages.crashes.detail.uptime') }}</span>
            <span class="tabular">{{ formatUptime(selectedCrash.uptimeMs) }}</span>
          </div>
          <div class="col-span-2 flex items-center justify-between rounded-md border border-glass-border bg-glass/40 px-3 py-2">
            <span class="flex items-center gap-2 text-muted-foreground"><Box class="size-3.5" /> {{ t('pages.crashes.detail.crashedAt') }}</span>
            <span>{{ new Date(selectedCrash.crashedAt).toLocaleString() }}</span>
          </div>
          <div class="col-span-2 flex items-start justify-between gap-3 rounded-md border border-glass-border bg-glass/40 px-3 py-2">
            <span class="flex items-center gap-2 text-muted-foreground shrink-0"><AlertTriangle class="size-3.5" /> {{ t('pages.crashes.detail.cause') }}</span>
            <span class="text-right text-foreground break-words">{{ selectedCrash.causeSummary }}</span>
          </div>
          <div class="col-span-2 flex items-center justify-between rounded-md border border-glass-border bg-glass/40 px-3 py-2">
            <span class="flex items-center gap-2 text-muted-foreground"><Hash class="size-3.5" /> {{ t('pages.crashes.detail.signature') }}</span>
            <span class="mono text-xs text-muted-foreground" :title="t('pages.crashes.detail.signatureTooltip')">{{ selectedCrash.signature }}</span>
          </div>
        </section>

        <section v-if="selectedCrash.logTail" class="space-y-2">
          <Eyebrow>{{ t('pages.crashes.logTail') }}</Eyebrow>
          <TerminalBlock :title="selectedCrash.instanceId" :copy="selectedCrash.logTail" class="max-h-[60vh] overflow-auto">{{ selectedCrash.logTail }}</TerminalBlock>
        </section>
        <p v-else-if="loadingDetail" class="text-sm text-muted-foreground">{{ t('pages.crashes.loadingLogTail') }}</p>
      </div>
    </DetailSheet>
  </div>
</template>
