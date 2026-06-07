<script setup lang="ts">
import { onMounted, computed } from "vue"
import { HeartPulse, Database, Settings as SettingsIcon, GitCommit, Share2, Waypoints } from "lucide-vue-next"
import { Callout, CalloutTitle } from "~/components/ui/callout"
import { StatusBadge } from "~/components/ui/status-badge"
import type { StatusDotTone } from "~/components/ui/status-dot"
import { Eyebrow } from "~/components/ui/eyebrow"
import { CodeBlock } from "~/components/ui/code-block"

const store = useSystemStore()
const shareStore = useShareStore()
const { t } = useI18n()
const sharing = ref(false)
const shareEnabled = computed(() => (store.settings as { shareEnabled?: boolean })?.shareEnabled === true)

async function shareDiagnostics() {
  if (sharing.value) return
  sharing.value = true
  try {
    await shareStore.shareDiagnostics({})
  } finally {
    sharing.value = false
  }
}

onMounted(() => store.fetchAll())

const overallTone = computed<StatusDotTone>(() => {
  const s = store.health?.status
  if (s === "UP") return "success"
  if (s === "DEGRADED") return "warning"
  if (s === "DOWN") return "destructive"
  return "muted"
})

function componentTone(s: string): StatusDotTone {
  if (s === "UP") return "success"
  if (s === "DEGRADED") return "warning"
  if (s === "DOWN") return "destructive"
  return "muted"
}

function calloutVariant(sev: "info" | "warning" | "error") {
  if (sev === "error") return "error" as const
  if (sev === "warning") return "warning" as const
  return "info" as const
}

const settingsAsJson = computed(() => JSON.stringify(store.settings, null, 2))
</script>

<template>
  <div class="flex flex-1 flex-col gap-6">
    <PageHeader :title="t('pages.system.title')" :description="t('pages.system.description')">
      <template #actions>
        <button
          v-if="shareEnabled"
          :disabled="sharing"
          class="inline-flex items-center gap-2 px-3 py-1.5 text-sm rounded-lg border border-glass-border bg-glass/60 backdrop-blur-xl transition-colors hover:bg-glass-hover disabled:opacity-50"
          @click="shareDiagnostics"
        >
          <Share2 class="size-4" />
          {{ t('pages.system.shareDiagnostics') }}
        </button>
        <StatusBadge :tone="overallTone" :label="store.health?.status ?? t('pages.system.statusUnknown')" :pulse="overallTone === 'success'" />
      </template>
    </PageHeader>

    <!-- Diagnostics -->
    <section v-if="store.diagnostics.length > 0" class="space-y-3">
      <Eyebrow>{{ t('pages.system.diagnostics') }}</Eyebrow>
      <div class="space-y-3">
        <Callout v-for="d in store.diagnostics" :key="d.id" :variant="calloutVariant(d.severity)">
          <CalloutTitle>{{ d.message }}</CalloutTitle>
          <template v-if="d.fix" #next>{{ d.fix }}</template>
        </Callout>
      </div>
    </section>

    <!-- Health components -->
    <section class="space-y-3">
      <Eyebrow>{{ t('pages.system.health') }}</Eyebrow>
      <div class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
        <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
          <div class="w-40 shrink-0">{{ t('pages.system.columns.component') }}</div>
          <div class="w-32 shrink-0">{{ t('pages.system.columns.status') }}</div>
          <div class="flex-1">{{ t('pages.system.columns.detail') }}</div>
        </div>
        <div v-for="c in (store.health?.components ?? [])" :key="c.id" class="flex h-12 items-center border-b border-glass-border/50 px-4 last:border-0">
          <div class="flex w-40 shrink-0 items-center gap-2">
            <HeartPulse class="size-3.5 text-muted-foreground" />
            <span class="text-sm font-medium mono">{{ c.id }}</span>
          </div>
          <div class="w-32 shrink-0">
            <StatusBadge :tone="componentTone(c.status)" :label="c.status" />
          </div>
          <div class="flex-1 truncate text-sm text-muted-foreground">{{ c.message ?? '—' }}</div>
        </div>
        <div v-if="!store.health?.components?.length" class="px-4 py-6 text-center text-sm text-muted-foreground">{{ t('pages.system.noComponents') }}</div>
      </div>
    </section>

    <div class="grid grid-cols-1 gap-6 lg:grid-cols-2">
      <!-- Version -->
      <section class="space-y-3">
        <Eyebrow>{{ t('pages.system.controller') }}</Eyebrow>
        <div class="space-y-3 rounded-2xl border border-glass-border bg-glass/60 p-5 backdrop-blur-xl">
          <div class="flex items-center justify-between">
            <span class="flex items-center gap-2 text-sm text-muted-foreground"><GitCommit class="size-4" /> {{ t('pages.system.version') }}</span>
            <span class="mono tabular">{{ store.version?.version ?? '—' }}</span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted-foreground">{{ t('pages.system.commit') }}</span>
            <span class="mono text-xs text-muted-foreground">{{ store.version?.commit?.slice(0, 12) ?? '—' }}</span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted-foreground">{{ t('pages.system.built') }}</span>
            <span class="text-sm">{{ store.version?.builtAt ? new Date(store.version.builtAt).toLocaleString() : '—' }}</span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted-foreground">{{ t('pages.system.java') }}</span>
            <span class="text-sm mono">{{ store.version?.javaVersion ?? '—' }}</span>
          </div>
        </div>
      </section>

      <!-- Redis -->
      <section class="space-y-3">
        <Eyebrow>{{ t('pages.system.redis') }}</Eyebrow>
        <div class="space-y-3 rounded-2xl border border-glass-border bg-glass/60 p-5 backdrop-blur-xl">
          <div class="flex items-center justify-between">
            <span class="flex items-center gap-2 text-sm text-muted-foreground"><Database class="size-4" /> {{ t('pages.system.keys') }}</span>
            <span class="tabular">{{ store.keyspace?.keys?.toLocaleString() ?? '—' }}</span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted-foreground">{{ t('pages.system.withTtl') }}</span>
            <span class="tabular">{{ store.keyspace?.expires?.toLocaleString() ?? '—' }}</span>
          </div>
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted-foreground">{{ t('pages.system.avgTtl') }}</span>
            <span class="tabular text-sm">{{ store.keyspace?.avgTtl ?? '—' }}s</span>
          </div>
          <div class="mt-3 space-y-1.5 border-t border-glass-border pt-3">
            <p class="eyebrow">{{ t('pages.system.byPrefix') }}</p>
            <div v-for="row in store.redisSchema" :key="row.prefix" class="flex items-center justify-between text-sm">
              <span class="mono text-xs text-muted-foreground">{{ row.prefix }}</span>
              <span class="tabular">{{ row.count.toLocaleString() }}</span>
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- Tracing (Track D.3) -->
    <section class="space-y-3">
      <Eyebrow><Waypoints class="mr-1 inline size-3" /> {{ t('pages.system.tracing') }}</Eyebrow>
      <div class="flex items-center justify-between gap-4 rounded-2xl border border-glass-border bg-glass/60 p-5 backdrop-blur-xl">
        <span class="text-sm text-muted-foreground">
          {{ store.tracingEnabled ? t('pages.system.tracingEnabled') : t('pages.system.tracingDisabled') }}
        </span>
        <a
          v-if="store.tracingEnabled && store.lastTraceUrl"
          :href="store.lastTraceUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-flex shrink-0 items-center gap-2 rounded-lg border border-glass-border bg-glass/60 px-3 py-1.5 text-sm backdrop-blur-xl transition-colors hover:bg-glass-hover"
        >
          <Waypoints class="size-4" />
          {{ t('pages.system.viewLastTrace') }}
        </a>
        <span v-else-if="store.tracingEnabled" class="shrink-0 text-xs text-muted-foreground">{{ t('pages.system.noTraceYet') }}</span>
      </div>
    </section>

    <!-- Settings -->
    <section class="space-y-3">
      <Eyebrow><SettingsIcon class="mr-1 inline size-3" /> {{ t('pages.system.settings') }}</Eyebrow>
      <CodeBlock :code="settingsAsJson" language="json" />
    </section>
  </div>
</template>
