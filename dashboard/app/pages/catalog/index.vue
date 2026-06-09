<script setup lang="ts">
import { Package, Server, Network } from "lucide-vue-next"
import { useVirtualList } from "@vueuse/core"
import { Badge } from "~/components/ui/badge"
import type { FilterOption } from "~/composables/useFilteredList"

const { t } = useI18n()
const store = useCatalogStore()

const search = ref("")
const activeFilters = ref<Set<string>>(new Set(["ALL"]))
const viewMode = ref<"grid" | "table">("grid")

onMounted(() => {
  store.fetchCatalog()
})

const categoryFilters = computed<FilterOption[]>(() => [
  { key: "ALL", label: t("pages.catalog.filters.all"), icon: Package },
  { key: "SERVER", label: t("pages.catalog.filters.servers"), icon: Server },
  { key: "PROXY", label: t("pages.catalog.filters.proxies"), icon: Network },
])

const categoryConfig = computed<Record<string, { label: string; color: string }>>(() => ({
  SERVER: { label: t("pages.catalog.category.server"), color: "text-success" },
  PROXY: { label: t("pages.catalog.category.proxy"), color: "text-primary" },
}))

function toggleFilter(key: string) {
  if (key === "ALL") {
    activeFilters.value = new Set(["ALL"])
    return
  }
  const next = new Set(activeFilters.value)
  next.delete("ALL")
  if (next.has(key)) next.delete(key)
  else next.add(key)
  if (next.size === 0) activeFilters.value = new Set(["ALL"])
  else activeFilters.value = next
}

const filteredEntries = computed(() => {
  let result = store.entries

  if (!activeFilters.value.has("ALL")) {
    result = result.filter(e => activeFilters.value.has(e.category))
  }

  const q = search.value.toLowerCase().trim()
  if (q) {
    result = result.filter(e =>
      e.platform.toLowerCase().includes(q)
      || (e.configFormat?.toLowerCase().includes(q) ?? false),
    )
  }

  return result
})

// Virtual list for grid (3-column rows)
const CARD_HEIGHT = 148
const CARD_GAP = 16
const gridColumns = ref(3)

const gridRows = computed(() => {
  const rows = []
  for (let i = 0; i < filteredEntries.value.length; i += gridColumns.value) {
    rows.push(filteredEntries.value.slice(i, i + gridColumns.value))
  }
  return rows
})

const { list: virtualGridRows, containerProps: gridContainerProps, wrapperProps: gridWrapperProps } = useVirtualList(
  gridRows,
  { itemHeight: CARD_HEIGHT + CARD_GAP, overscan: 5 },
)

// Virtual list for table
const ROW_HEIGHT = 49

const { list: virtualTableRows, containerProps: tableContainerProps, wrapperProps: tableWrapperProps } = useVirtualList(
  filteredEntries,
  { itemHeight: ROW_HEIGHT, overscan: 15 },
)

function getRecommended(entry: typeof store.entries[number]) {
  return entry.versions.find(v => v.recommended)?.version ?? "—"
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <PageHeader
      :title="t('pages.catalog.title')"
      :description="t('pages.catalog.description')"
    >
      <template #actions>
        <CatalogAddVersionDialog />
      </template>
    </PageHeader>

    <!-- Toolbar -->
    <FilterToolbar
      v-model:search="search"
      :search-placeholder="t('pages.catalog.searchPlaceholder')"
      :filters="categoryFilters"
      :active-filters="activeFilters"
      :view-mode="viewMode"
      :count="filteredEntries.length"
      :count-label="t('pages.catalog.countLabel')"
      @toggle-filter="toggleFilter"
      @update:view-mode="viewMode = $event"
    />

    <div>
      <!-- Loading -->
      <div v-if="store.loading" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        <div v-for="i in 6" :key="i" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-5 animate-pulse">
          <div class="flex items-center gap-3 mb-4"><div class="size-10 rounded-xl bg-glass" /><div class="flex-1"><div class="h-4 bg-glass rounded w-24 mb-2" /><div class="h-3 bg-glass rounded w-32" /></div></div>
          <div class="grid grid-cols-3 gap-3 mt-3"><div class="h-4 bg-glass rounded" /><div class="h-4 bg-glass rounded" /><div class="h-4 bg-glass rounded" /></div>
        </div>
      </div>

      <!-- Empty state -->
      <div v-else-if="filteredEntries.length === 0" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border py-48 flex flex-col items-center justify-center text-center">
        <Package class="size-16 text-muted-foreground/30 mb-4" />
        <p class="text-foreground font-semibold text-lg">{{ t('pages.catalog.emptyTitle') }}</p>
        <p class="text-muted-foreground mt-1">{{ search || !activeFilters.has('ALL') ? t('pages.catalog.emptyFilterHint') : t('pages.catalog.emptyHint') }}</p>
        <div v-if="!search && activeFilters.has('ALL')" class="mt-4"><CatalogAddVersionDialog /></div>
      </div>

      <!-- Grid view -->
      <template v-else-if="viewMode === 'grid'">
        <div v-if="filteredEntries.length <= 30" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <CatalogPlatformCard v-for="entry in filteredEntries" :key="entry.platform" :entry="entry" />
        </div>
        <div v-else v-bind="gridContainerProps" class="h-[calc(100vh-280px)] overflow-auto styled-scrollbar pr-3">
          <div v-bind="gridWrapperProps">
            <div v-for="{ data: row, index } in virtualGridRows" :key="index" class="grid grid-cols-3 gap-4" :style="{ height: `${CARD_HEIGHT}px`, marginBottom: `${CARD_GAP}px` }">
              <CatalogPlatformCard v-for="entry in row" :key="entry.platform" :entry="entry" />
            </div>
          </div>
        </div>
      </template>

      <!-- Table view -->
      <template v-else>
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border overflow-hidden">
          <div class="flex items-center h-10 px-4 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider">
            <div class="w-44 shrink-0">{{ t('pages.catalog.columns.platform') }}</div>
            <div class="w-24 shrink-0 text-center">{{ t('pages.catalog.columns.category') }}</div>
            <div class="w-28 shrink-0 text-center">{{ t('pages.catalog.columns.format') }}</div>
            <div class="w-20 shrink-0 text-right">{{ t('pages.catalog.columns.versions') }}</div>
            <div class="flex-1 text-right">{{ t('pages.catalog.columns.recommended') }}</div>
          </div>
          <div v-if="filteredEntries.length <= 50">
            <div v-for="entry in filteredEntries" :key="entry.platform" class="flex items-center h-12 px-4 border-b border-glass-border/50 last:border-0 cursor-pointer transition-colors select-none hover:bg-glass-hover" @click="navigateTo(`/catalog/${entry.platform}`)">
              <div class="w-44 shrink-0 flex items-center gap-2">
                <div :class="['size-2 rounded-full shrink-0', entry.category === 'SERVER' ? 'bg-success' : 'bg-primary']" />
                <span class="text-sm font-medium text-foreground truncate uppercase">{{ entry.platform }}</span>
              </div>
              <div class="w-24 shrink-0 text-center"><Badge variant="outline" :class="['text-xs', categoryConfig[entry.category]?.color ?? 'text-muted-foreground']">{{ categoryConfig[entry.category]?.label ?? entry.category }}</Badge></div>
              <div class="w-28 shrink-0 text-center text-sm text-muted-foreground">{{ entry.configFormat ?? '—' }}</div>
              <div class="w-20 shrink-0 text-right text-sm text-foreground tabular-nums">{{ entry.versions.length }}</div>
              <div class="flex-1 text-right text-sm text-primary font-medium">{{ getRecommended(entry) }}</div>
            </div>
          </div>
          <div v-else v-bind="tableContainerProps" class="h-[calc(100vh-320px)] overflow-auto styled-scrollbar mr-1">
            <div v-bind="tableWrapperProps">
              <div v-for="{ data: entry } in virtualTableRows" :key="entry.platform" class="flex items-center h-12 px-4 border-b border-glass-border/50 cursor-pointer transition-colors select-none hover:bg-glass-hover" @click="navigateTo(`/catalog/${entry.platform}`)">
                <div class="w-44 shrink-0 flex items-center gap-2">
                  <div :class="['size-2 rounded-full shrink-0', entry.category === 'SERVER' ? 'bg-success' : 'bg-primary']" />
                  <span class="text-sm font-medium text-foreground truncate uppercase">{{ entry.platform }}</span>
                </div>
                <div class="w-24 shrink-0 text-center"><Badge variant="outline" :class="['text-xs', categoryConfig[entry.category]?.color ?? 'text-muted-foreground']">{{ categoryConfig[entry.category]?.label ?? entry.category }}</Badge></div>
                <div class="w-28 shrink-0 text-center text-sm text-muted-foreground">{{ entry.configFormat ?? '—' }}</div>
                <div class="w-20 shrink-0 text-right text-sm text-foreground tabular-nums">{{ entry.versions.length }}</div>
                <div class="flex-1 text-right text-sm text-primary font-medium">{{ getRecommended(entry) }}</div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
