<script setup lang="ts">
import { ChevronRight, ScrollText } from "lucide-vue-next"
import { StatusBadge } from "~/components/ui/status-badge"
import type { StatusDotTone } from "~/components/ui/status-dot"
import DiffViewer from "~/components/ui/diff-viewer/DiffViewer.vue"

const store = useAuditStore()
const { t } = useI18n()

const { search, filteredItems: filteredEntries } = useFilteredList(
  () => store.entries,
  {
    searchFields: e => [e.username, e.action, e.resourceType, e.resourceId, e.details],
    defaultView: 'table',
  },
)

onMounted(() => { store.fetchEntries() })

const canPrev = computed(() => store.canPrev)
const canNext = computed(() => store.hasMore)

function prevPage() {
  store.prevPage()
}
function nextPage() {
  store.nextPage()
}

const expandedIds = ref<Set<number>>(new Set())

function toggleExpanded(id: number) {
  if (expandedIds.value.has(id)) expandedIds.value.delete(id)
  else expandedIds.value.add(id)
  expandedIds.value = new Set(expandedIds.value)
}

function hasDiff(entry: { before?: unknown, after?: unknown }) {
  return entry.before != null || entry.after != null
}

function stringify(value: unknown): string {
  if (value == null) return ""
  return JSON.stringify(value, null, 2)
}

/**
 * Map common audit verbs to design-system tones. Unknowns fall back to muted.
 * Kept as a substring scan because the controller emits action codes like
 * `INSTANCE_DELETED`, `GROUP_UPDATED`, `USER_LOGGED_IN`, etc.
 */
function actionTone(action: string): StatusDotTone {
  const upper = action.toUpperCase()
  if (upper.includes('CREATE'))      return 'success'
  if (upper.includes('DELETE'))      return 'destructive'
  if (upper.includes('UPDATE'))      return 'primary'
  if (upper.includes('LOGIN'))       return 'success'
  if (upper.includes('LOGOUT'))      return 'muted'
  if (upper.includes('FAIL'))        return 'destructive'
  if (upper.includes('DRAIN'))       return 'warning'
  return 'muted'
}
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader :title="t('pages.audit.title')" :description="t('pages.audit.description')" />

    <FilterToolbar
      v-model:search="search"
      :filters="[]"
      :active-filters="new Set(['ALL'])"
      :search-placeholder="t('pages.audit.searchPlaceholder')"
      :show-view-toggle="false"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="8" />

    <EmptyState
      v-else-if="filteredEntries.length === 0"
      :icon="ScrollText"
      :title="t('pages.audit.emptyTitle')"
      :description="search ? t('pages.audit.emptySearchHint') : t('pages.audit.emptyHint')"
    />

    <template v-else>
      <div class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
        <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
          <div class="w-6 shrink-0"/>
          <div class="w-44 shrink-0">{{ t('pages.audit.columns.timestamp') }}</div>
          <div class="w-32 shrink-0">{{ t('pages.audit.columns.user') }}</div>
          <div class="w-28 shrink-0">{{ t('pages.audit.columns.action') }}</div>
          <div class="w-28 shrink-0">{{ t('pages.audit.columns.resourceType') }}</div>
          <div class="w-36 shrink-0">{{ t('pages.audit.columns.resourceId') }}</div>
          <div class="w-28 shrink-0">{{ t('pages.audit.columns.ip') }}</div>
          <div class="flex-1">{{ t('pages.audit.columns.details') }}</div>
        </div>
        <!--
          Rows are expandable when an entry carries a before/after snapshot
          (controller-side opt-in for mutator routes). Variable row height
          rules out virtua VList here; the store caps pages at 50 entries so
          a plain v-for is the right tool.
        -->
        <div class="styled-scrollbar overflow-auto" style="max-height: calc(100vh - 360px); min-height: 280px;">
          <template v-for="entry in filteredEntries" :key="entry.id">
            <div
              class="flex h-12 select-none items-center border-b border-glass-border/50 px-4 transition-colors"
              :class="hasDiff(entry) ? 'cursor-pointer hover:bg-glass-hover' : 'hover:bg-glass-hover/50'"
              @click="hasDiff(entry) && toggleExpanded(entry.id)"
            >
              <div class="w-6 shrink-0 text-muted-foreground">
                <ChevronRight
                  v-if="hasDiff(entry)"
                  class="h-4 w-4 transition-transform"
                  :class="{ 'rotate-90': expandedIds.has(entry.id) }"
                />
              </div>
              <div class="w-44 shrink-0 text-sm tabular text-muted-foreground">{{ new Date(entry.createdAt).toLocaleString() }}</div>
              <div class="w-32 shrink-0 truncate text-sm font-medium">{{ entry.username }}</div>
              <div class="w-28 shrink-0">
                <StatusBadge :tone="actionTone(entry.action)" :label="entry.action" />
              </div>
              <div class="w-28 shrink-0 truncate text-sm text-muted-foreground">{{ entry.resourceType }}</div>
              <div class="w-36 shrink-0 truncate mono text-xs text-muted-foreground">{{ entry.resourceId }}</div>
              <div class="w-28 shrink-0 mono tabular text-xs text-muted-foreground">{{ entry.ipAddress }}</div>
              <div class="flex-1 truncate text-sm text-muted-foreground">{{ entry.details }}</div>
            </div>
            <div
              v-if="hasDiff(entry) && expandedIds.has(entry.id)"
              class="border-b border-glass-border/50 bg-glass/30 px-4 py-3"
            >
              <DiffViewer
                :original="stringify(entry.before)"
                :modified="stringify(entry.after)"
                language="json"
                :original-label="t('pages.audit.diffBefore')"
                :modified-label="t('pages.audit.diffAfter')"
              />
            </div>
          </template>
        </div>
      </div>

      <div class="flex items-center justify-between">
        <span class="text-sm text-muted-foreground">
          {{ t('pages.common.showing', { from: store.offset + 1, to: store.offset + filteredEntries.length }) }}
        </span>
        <div class="flex gap-2">
          <button
            type="button"
            :disabled="!canPrev"
            class="rounded-lg border border-glass-border bg-glass/60 px-3 py-1.5 text-sm backdrop-blur-xl transition-colors hover:bg-glass-hover disabled:cursor-not-allowed disabled:opacity-40"
            @click="prevPage"
          >
            {{ t('pages.common.previous') }}
          </button>
          <button
            type="button"
            :disabled="!canNext"
            class="rounded-lg border border-glass-border bg-glass/60 px-3 py-1.5 text-sm backdrop-blur-xl transition-colors hover:bg-glass-hover disabled:cursor-not-allowed disabled:opacity-40"
            @click="nextPage"
          >
            {{ t('pages.common.next') }}
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
