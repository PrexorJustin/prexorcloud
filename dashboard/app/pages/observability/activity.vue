<script setup lang="ts">
import { computed, onMounted } from "vue"
import { Activity, AlertOctagon, AlertTriangle, CheckCircle2, Info } from "lucide-vue-next"
import { VList } from "virtua/vue"
import { StatusDot } from "~/components/ui/status-dot"
import { Eyebrow } from "~/components/ui/eyebrow"
import type { StatusDotTone } from "~/components/ui/status-dot"
import { timeAgo } from "~/lib/utils"
import type { ActivityEvent } from "~/stores/activity"

const store = useActivityStore()
const { t } = useI18n()

onMounted(() => store.fetchEvents())

const { search, filteredItems: filteredEvents } = useFilteredList(
  () => store.events,
  {
    searchFields: e => [e.type, e.actor ?? "", e.message],
    defaultView: "table",
  },
)

function eventTone(type: string): StatusDotTone {
  if (type.includes("CRASH") || type.includes("FAILED")) return "destructive"
  if (type.includes("DRAIN") || type.includes("MAINTENANCE")) return "warning"
  if (type.includes("DISCONNECTED")) return "warning"
  if (type.includes("STARTED") || type.includes("CONNECTED") || type.includes("COMPLETED") || type.includes("CREATED")) return "success"
  if (type.includes("UPDATED") || type.includes("ROLLED_BACK")) return "primary"
  return "muted"
}

function eventIcon(type: string) {
  if (type.includes("CRASH") || type.includes("FAILED")) return AlertOctagon
  if (type.includes("DRAIN") || type.includes("MAINTENANCE") || type.includes("DISCONNECTED")) return AlertTriangle
  if (type.includes("COMPLETED") || type.includes("CONNECTED") || type.includes("STARTED")) return CheckCircle2
  return Info
}

function dayKey(iso: string) {
  const d = new Date(iso)
  const today = new Date()
  const yesterday = new Date(); yesterday.setDate(today.getDate() - 1)
  if (d.toDateString() === today.toDateString()) return t("pages.common.today")
  if (d.toDateString() === yesterday.toDateString()) return t("pages.common.yesterday")
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" })
}

interface TimelineRow {
  kind: "header" | "event"
  day?: string
  event?: ActivityEvent
  key: string
}

const timelineRows = computed<TimelineRow[]>(() => {
  const rows: TimelineRow[] = []
  let lastDay = ""
  for (const ev of filteredEvents.value) {
    const day = dayKey(ev.timestamp)
    if (day !== lastDay) {
      rows.push({ kind: "header", day, key: `h-${day}` })
      lastDay = day
    }
    rows.push({ kind: "event", event: ev, key: `e-${ev.id}` })
  }
  return rows
})
</script>

<template>
  <div
    class="flex min-h-[420px] flex-col gap-5 overflow-hidden"
    style="height: calc(100svh - 6.5rem)"
  >
    <PageHeader :title="t('pages.activity.title')" :description="t('pages.activity.description')" />

    <FilterToolbar
      v-model:search="search"
      :filters="[]"
      :active-filters="new Set(['ALL'])"
      :search-placeholder="t('pages.activity.searchPlaceholder')"
      :show-view-toggle="false"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="8" />

    <EmptyState
      v-else-if="timelineRows.length === 0"
      :icon="Activity"
      :title="search ? t('pages.activity.emptyMatchesTitle') : t('pages.activity.emptyTitle')"
      :description="search ? t('pages.activity.emptySearchHint') : t('pages.activity.emptyHint')"
    />

    <div
      v-else
      class="flex min-h-0 flex-1 flex-col overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl"
    >
      <!--
        Virtualized vertical timeline. Day-headers are inline rows with
        item-size 32; events are taller (item-size 64). VList uses an
        item-size hint of 56 for stable scrollbar geometry.
      -->
      <VList
        v-slot="{ item: row }"
        :data="timelineRows"
        :item-size="56"
        :overscan="6"
        class="styled-scrollbar min-h-0 flex-1"
      >
        <div v-if="row.kind === 'header'" class="sticky top-0 z-10 flex items-center gap-2 border-b border-glass-border bg-sidebar/95 px-5 py-2 backdrop-blur">
          <Eyebrow>{{ row.day }}</Eyebrow>
        </div>
        <NuxtLink
          v-else-if="row.event"
          :to="row.event.route ?? '#'"
          class="flex items-start gap-3 border-b border-glass-border/50 px-5 py-3 transition-colors hover:bg-glass-hover"
        >
          <component :is="eventIcon(row.event.type)" :class="['mt-0.5 size-4 shrink-0', `text-${eventTone(row.event.type) === 'destructive' ? 'destructive' : eventTone(row.event.type) === 'warning' ? 'warning' : eventTone(row.event.type) === 'success' ? 'success' : eventTone(row.event.type) === 'primary' ? 'primary' : 'muted-foreground'}`]" />
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2">
              <span class="text-sm font-medium">{{ row.event.message }}</span>
              <StatusDot :tone="eventTone(row.event.type)" size="sm" />
            </div>
            <div class="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
              <span class="mono">{{ row.event.type }}</span>
              <span v-if="row.event.actor">·</span>
              <span v-if="row.event.actor" class="mono">{{ row.event.actor }}</span>
            </div>
          </div>
          <span class="shrink-0 tabular text-xs text-muted-foreground">{{ timeAgo(row.event.timestamp) }}</span>
        </NuxtLink>
      </VList>
      <div v-if="store.hasMore" class="flex shrink-0 items-center justify-center border-t border-glass-border px-4 py-3">
        <button
          type="button"
          class="rounded-md px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-glass-hover hover:text-foreground disabled:opacity-50 disabled:hover:bg-transparent"
          :disabled="store.loading"
          @click="store.loadMore()"
        >
          {{ store.loading ? t('pages.activity.loadingMore') : t('pages.activity.loadOlder') }}
        </button>
      </div>
    </div>
  </div>
</template>
