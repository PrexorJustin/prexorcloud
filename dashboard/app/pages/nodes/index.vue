<script setup lang="ts">
import { Server, ServerOff, Clock, Shield, Wifi, WifiOff, Power } from "lucide-vue-next"
import { useVirtualList } from "@vueuse/core"
import type { ComputedRef } from "vue"
import type { ConnectedNode, NodeEntry } from "~/types/api"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import { Checkbox } from "~/components/ui/checkbox"
import { BulkActionBar } from "~/components/ui/bulk-action-bar"
import { DetailSheet } from "~/components/ui/detail-sheet"
import { StatusBadge } from "~/components/ui/status-badge"
import { formatMemory } from "~/lib/utils"
import { NODE_STATUS_CONFIG } from "~/lib/constants"
import { toast } from "vue-sonner"

const store = useNodesStore()
const { t } = useI18n()

const statusFilters = computed(() => [
  { key: "ALL", label: t("pages.nodes.filters.all"), icon: Server },
  { key: "ONLINE", label: t("pages.nodes.filters.online"), icon: Wifi },
  { key: "DRAINING", label: t("pages.nodes.filters.draining"), icon: Clock },
  { key: "CORDONED", label: t("pages.nodes.filters.cordoned"), icon: Shield },
  { key: "OFFLINE", label: t("pages.nodes.filters.offline"), icon: WifiOff },
  { key: "PENDING", label: t("pages.nodes.filters.pending"), icon: ServerOff },
])

const { search, activeFilters, viewMode, filteredItems: filteredNodes, toggleFilter } = useFilteredList(
  () => store.nodes,
  {
    searchFields: n => [n.id, n.type === 'CONNECTED' ? (n as ConnectedNode).address : ''],
    filterField: n => n.status,
  },
)

onMounted(() => { store.fetchNodes(); store.connectSse() })
onUnmounted(() => { store.disconnectSse() })

const CARD_HEIGHT = 148; const CARD_GAP = 16
const gridRows = computed(() => { const rows = []; for (let i = 0; i < filteredNodes.value.length; i += 3) rows.push(filteredNodes.value.slice(i, i + 3)); return rows })
const { list: virtualGridRows, containerProps: gridContainerProps, wrapperProps: gridWrapperProps } = useVirtualList(gridRows, { itemHeight: CARD_HEIGHT + CARD_GAP, overscan: 5 })
const ROW_HEIGHT = 49
const { list: virtualTableRows, containerProps: tableContainerProps, wrapperProps: tableWrapperProps } = useVirtualList(filteredNodes, { itemHeight: ROW_HEIGHT, overscan: 15 })

// Bulk select for drain / cordon. Only ONLINE / DRAINING nodes accept the
// actions; the controller will reject others with 409 — Promise.allSettled
// surfaces the partial outcome to the user via the toast.
const { count: selectedCount, isAll, has: isSelected, toggle: toggleSelected, toggleAll, clear: clearSelection, selected } =
  useSelection(filteredNodes as unknown as ComputedRef<NodeEntry[]>, n => n.id)

const bulkBusy = ref(false)
async function bulkDrain() {
  bulkBusy.value = true
  try {
    const ids = Array.from(selected.value)
    await Promise.allSettled(ids.map(id =>
      useApiClient().POST('/api/v1/nodes/{id}/drain', { params: { path: { id } } }),
    ))
    toast.success(t('toast.nodes.draining', { count: ids.length }, ids.length))
    clearSelection()
    await store.fetchNodes()
  } catch {
    toast.error(t('toast.nodes.bulkDrainFailed'), { description: t('toast.nodes.bulkDrainFailedDesc') })
  } finally {
    bulkBusy.value = false
  }
}
async function bulkCordon() {
  bulkBusy.value = true
  try {
    const ids = Array.from(selected.value)
    await Promise.allSettled(ids.map(id =>
      useApiClient().POST('/api/v1/nodes/{id}/cordon', { params: { path: { id } } }),
    ))
    toast.success(t('toast.nodes.cordoned', { count: ids.length }, ids.length))
    clearSelection()
    await store.fetchNodes()
  } catch {
    toast.error(t('toast.nodes.bulkCordonFailed'), { description: t('toast.nodes.bulkCordonFailedDesc') })
  } finally {
    bulkBusy.value = false
  }
}

// Row click → DetailSheet.
const sheetNode = ref<NodeEntry | null>(null)
const sheetOpen = computed({
  get: () => sheetNode.value !== null,
  set: (v) => { if (!v) sheetNode.value = null },
})
function openSheet(n: NodeEntry) { sheetNode.value = n }

const sheetActing = ref(false)
async function sheetDrain() {
  if (!sheetNode.value) return
  sheetActing.value = true
  try {
    await useApiClient().POST('/api/v1/nodes/{id}/drain', { params: { path: { id: sheetNode.value.id } } })
    toast.success(t('toast.nodes.drainingOne', { id: sheetNode.value.id }))
    await store.fetchNodes()
  } catch {
    toast.error(t('toast.nodes.drainFailed'), { description: t('toast.nodes.drainFailedDesc') })
  } finally { sheetActing.value = false }
}
async function sheetCordon() {
  if (!sheetNode.value) return
  sheetActing.value = true
  try {
    await useApiClient().POST('/api/v1/nodes/{id}/cordon', { params: { path: { id: sheetNode.value.id } } })
    toast.success(t('toast.nodes.cordonedOne', { id: sheetNode.value.id }))
    await store.fetchNodes()
  } catch {
    toast.error(t('toast.nodes.cordonFailed'), { description: t('toast.nodes.cordonFailedDesc') })
  } finally { sheetActing.value = false }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <PageHeader :title="t('pages.nodes.title')" :description="t('pages.nodes.description')">
      <template #actions>
        <NodesAddNodeDialog />
      </template>
    </PageHeader>

    <FilterToolbar
      v-model:search="search"
      :filters="statusFilters"
      :active-filters="activeFilters"
      :view-mode="viewMode"
      :search-placeholder="t('pages.nodes.searchPlaceholder')"
      @toggle-filter="toggleFilter"
      @update:view-mode="viewMode = $event"
    />

    <div>
      <LoadingSkeleton v-if="store.loading" />

      <EmptyState
        v-else-if="filteredNodes.length === 0"
        :icon="Server"
        :title="t('pages.nodes.emptyTitle')"
        :description="search || !activeFilters.has('ALL') ? t('pages.nodes.emptyFilterHint') : t('pages.nodes.emptyAddHint')"
      >
        <NodesAddNodeDialog v-if="!search && activeFilters.has('ALL')" />
      </EmptyState>

      <template v-else-if="viewMode === 'grid'">
        <div v-if="filteredNodes.length <= 30" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <NodesNodeCard v-for="node in filteredNodes" :key="node.id" :node="(node as unknown as NodeEntry)" :exploding="store.explodingNodeId === node.id" />
        </div>
        <div v-else v-bind="gridContainerProps" class="h-[calc(100vh-280px)] overflow-auto styled-scrollbar pr-3">
          <div v-bind="gridWrapperProps">
            <div v-for="{ data: row, index } in virtualGridRows" :key="index" class="grid grid-cols-3 gap-4" :style="{ height: `${CARD_HEIGHT}px`, marginBottom: `${CARD_GAP}px` }">
              <NodesNodeCard v-for="node in row" :key="node.id" :node="(node as unknown as NodeEntry)" :exploding="store.explodingNodeId === node.id" />
            </div>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border overflow-hidden">
          <div class="flex items-center h-10 px-4 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider">
            <div class="w-8 shrink-0 -ml-1">
              <Checkbox :model-value="isAll" :aria-label="t('pages.nodes.selectAll')" @update:model-value="toggleAll" />
            </div>
            <div class="w-52 shrink-0">{{ t('pages.nodes.columns.node') }}</div>
            <div class="w-24 shrink-0 text-center">{{ t('pages.nodes.columns.status') }}</div>
            <div class="w-20 shrink-0 text-right">{{ t('pages.nodes.columns.cpu') }}</div>
            <div class="w-28 shrink-0 text-right">{{ t('pages.nodes.columns.memory') }}</div>
            <div class="w-28 shrink-0 text-right">{{ t('pages.nodes.columns.diskFree') }}</div>
            <div class="w-20 shrink-0 text-right">{{ t('pages.nodes.columns.instances') }}</div>
            <div class="flex-1 text-right">{{ t('pages.nodes.columns.address') }}</div>
          </div>
          <div v-if="filteredNodes.length <= 50">
            <div v-for="node in filteredNodes" :key="node.id" class="flex items-center h-12 px-4 border-b border-glass-border/50 last:border-0 cursor-pointer transition-colors hover:bg-glass-hover" :class="isSelected(node.id) ? 'bg-glass/40' : ''" @click="openSheet(node as unknown as NodeEntry)">
              <div class="w-8 shrink-0 -ml-1" @click.stop>
                <Checkbox :model-value="isSelected(node.id)" :aria-label="t('pages.nodes.selectOne', { id: node.id })" @update:model-value="toggleSelected(node.id)" />
              </div>
              <div class="w-52 shrink-0 flex items-center gap-2">
                <div :class="['size-2 rounded-full shrink-0', NODE_STATUS_CONFIG[node.status]?.dot ?? 'bg-muted-foreground']" />
                <span class="text-sm font-medium text-foreground truncate">{{ node.id }}</span>
              </div>
              <div class="w-24 shrink-0 text-center"><Badge variant="outline" :class="['text-xs', NODE_STATUS_CONFIG[node.status]?.color ?? 'text-muted-foreground']">{{ NODE_STATUS_CONFIG[node.status]?.label ?? node.status }}</Badge></div>
              <template v-if="node.type === 'CONNECTED'">
                <div class="w-20 shrink-0 text-right text-sm text-foreground tabular-nums">{{ (node as ConnectedNode).cpuUsage.toFixed(0) }}%</div>
                <div class="w-28 shrink-0 text-right text-sm text-foreground tabular-nums">{{ formatMemory((node as ConnectedNode).usedMemoryMb) }}</div>
                <div class="w-28 shrink-0 text-right text-sm text-muted-foreground tabular-nums">{{ formatMemory((node as ConnectedNode).freeDiskMb) }}</div>
                <div class="w-20 shrink-0 text-right text-sm text-foreground tabular-nums">{{ (node as ConnectedNode).instanceCount }}</div>
                <div class="flex-1 text-right text-sm text-muted-foreground font-mono truncate">{{ (node as ConnectedNode).address }}</div>
              </template>
              <template v-else>
                <div class="w-20 shrink-0 text-right text-sm text-muted-foreground">&mdash;</div>
                <div class="w-28 shrink-0 text-right text-sm text-muted-foreground">&mdash;</div>
                <div class="w-28 shrink-0 text-right text-sm text-muted-foreground">&mdash;</div>
                <div class="w-20 shrink-0 text-right text-sm text-muted-foreground">&mdash;</div>
                <div class="flex-1 text-right text-sm text-muted-foreground">
                  <template v-if="node.type === 'PENDING'">{{ t('pages.nodes.awaitingConnection') }}</template>
                  <template v-else>{{ t('pages.nodes.disconnected') }}</template>
                </div>
              </template>
            </div>
          </div>
          <div v-else v-bind="tableContainerProps" class="h-[calc(100vh-320px)] overflow-auto styled-scrollbar mr-1">
            <div v-bind="tableWrapperProps">
              <div v-for="{ data: node } in virtualTableRows" :key="node.id" class="flex items-center h-12 px-4 border-b border-glass-border/50 cursor-pointer transition-colors hover:bg-glass-hover" :class="isSelected(node.id) ? 'bg-glass/40' : ''" @click="openSheet(node as unknown as NodeEntry)">
                <div class="w-8 shrink-0 -ml-1" @click.stop>
                  <Checkbox :model-value="isSelected(node.id)" :aria-label="t('pages.nodes.selectOne', { id: node.id })" @update:model-value="toggleSelected(node.id)" />
                </div>
                <div class="w-52 shrink-0 flex items-center gap-2">
                  <div :class="['size-2 rounded-full shrink-0', NODE_STATUS_CONFIG[node.status]?.dot ?? 'bg-muted-foreground']" />
                  <span class="text-sm font-medium text-foreground truncate">{{ node.id }}</span>
                </div>
                <div class="w-24 shrink-0 text-center"><Badge variant="outline" :class="['text-xs', NODE_STATUS_CONFIG[node.status]?.color ?? 'text-muted-foreground']">{{ NODE_STATUS_CONFIG[node.status]?.label ?? node.status }}</Badge></div>
                <template v-if="node.type === 'CONNECTED'">
                  <div class="w-20 shrink-0 text-right text-sm text-foreground tabular-nums">{{ (node as ConnectedNode).cpuUsage.toFixed(0) }}%</div>
                  <div class="w-28 shrink-0 text-right text-sm text-foreground tabular-nums">{{ formatMemory((node as ConnectedNode).usedMemoryMb) }}</div>
                  <div class="w-28 shrink-0 text-right text-sm text-muted-foreground tabular-nums">{{ formatMemory((node as ConnectedNode).freeDiskMb) }}</div>
                  <div class="w-20 shrink-0 text-right text-sm text-foreground tabular-nums">{{ (node as ConnectedNode).instanceCount }}</div>
                  <div class="flex-1 text-right text-sm text-muted-foreground font-mono truncate">{{ (node as ConnectedNode).address }}</div>
                </template>
                <template v-else>
                  <div class="w-20 shrink-0 text-right text-sm text-muted-foreground">&mdash;</div>
                  <div class="w-28 shrink-0 text-right text-sm text-muted-foreground">&mdash;</div>
                  <div class="w-28 shrink-0 text-right text-sm text-muted-foreground">&mdash;</div>
                  <div class="w-20 shrink-0 text-right text-sm text-muted-foreground">&mdash;</div>
                  <div class="flex-1 text-right text-sm text-muted-foreground">
                    <template v-if="node.type === 'PENDING'">{{ t('pages.nodes.awaitingConnection') }}</template>
                    <template v-else>{{ t('pages.nodes.disconnected') }}</template>
                  </div>
                </template>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <BulkActionBar
      :count="selectedCount"
      singular="node"
      plural="nodes"
      @clear="clearSelection"
    >
      <Button variant="outline" size="sm" :disabled="bulkBusy" class="border-warning/50 text-warning hover:bg-warning/10" @click="bulkDrain">
        <Power class="mr-1.5 size-3.5" /> {{ t('pages.nodes.drain') }}
      </Button>
      <Button variant="outline" size="sm" :disabled="bulkBusy" class="border-warning/50 text-warning hover:bg-warning/10" @click="bulkCordon">
        <Shield class="mr-1.5 size-3.5" /> {{ t('pages.nodes.cordon') }}
      </Button>
    </BulkActionBar>

    <DetailSheet
      :open="sheetOpen"
      :title="sheetNode?.id"
      :eyebrow="t('pages.nodes.eyebrow')"
      :full-page-path="sheetNode ? `/nodes/${sheetNode.id}` : undefined"
      @update:open="sheetOpen = $event"
    >
      <template v-if="sheetNode" #status>
        <StatusBadge :state="sheetNode.status" :pulse="sheetNode.status === 'ONLINE'" />
      </template>
      <template v-if="sheetNode && sheetNode.type === 'CONNECTED'" #actions>
        <Button
variant="outline" size="sm" :disabled="sheetActing"
          class="border-warning/50 text-warning hover:bg-warning/10" @click="sheetDrain">
          <Power class="mr-1 size-3.5" /> {{ t('pages.nodes.drain') }}
        </Button>
        <Button
variant="outline" size="sm" :disabled="sheetActing"
          class="border-warning/50 text-warning hover:bg-warning/10" @click="sheetCordon">
          <Shield class="mr-1 size-3.5" /> {{ t('pages.nodes.cordon') }}
        </Button>
      </template>

      <div v-if="sheetNode && sheetNode.type === 'CONNECTED'" class="space-y-4 text-sm">
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.nodes.detail.address') }}</span>
          <span class="mono text-xs">{{ (sheetNode as ConnectedNode).address }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.nodes.detail.cpu') }}</span>
          <span class="tabular">{{ Math.round((sheetNode as ConnectedNode).cpuUsage * 100) }}%</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.nodes.detail.memory') }}</span>
          <span class="tabular">
            {{ formatMemory((sheetNode as ConnectedNode).usedMemoryMb) }} /
            {{ formatMemory((sheetNode as ConnectedNode).totalMemoryMb) }}
          </span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.nodes.detail.diskFree') }}</span>
          <span class="tabular">{{ formatMemory((sheetNode as ConnectedNode).freeDiskMb) }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.nodes.detail.instances') }}</span>
          <span class="tabular">{{ (sheetNode as ConnectedNode).instanceCount }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.nodes.detail.connectedSince') }}</span>
          <span class="text-xs">{{ new Date((sheetNode as ConnectedNode).connectedSince).toLocaleString() }}</span>
        </div>
      </div>
      <div v-else-if="sheetNode" class="rounded-md border border-dashed border-glass-border bg-glass/30 px-4 py-6 text-center text-sm text-muted-foreground">
        <span v-if="sheetNode.type === 'PENDING'">{{ t('pages.nodes.detail.awaitingDaemon') }}</span>
        <span v-else>{{ t('pages.nodes.detail.daemonOffline') }}</span>
      </div>
    </DetailSheet>
  </div>
</template>
