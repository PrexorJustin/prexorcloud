<script setup lang="ts">
import { Box, Play, Square, AlertTriangle, Clock, Pause, Plus, Loader2, Zap, Trash2, Layers } from "lucide-vue-next"
import { useVirtualList } from "@vueuse/core"
import type { ComputedRef } from "vue"
import type { ServerInstance } from "~/types/api"
import { Button } from "~/components/ui/button"
import { Checkbox } from "~/components/ui/checkbox"
import { BulkActionBar } from "~/components/ui/bulk-action-bar"
import { DetailSheet } from "~/components/ui/detail-sheet"
import { StatusBadge } from "~/components/ui/status-badge"
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogTitle,
} from "~/components/ui/dialog"
import { Badge } from "~/components/ui/badge"
import { Slider } from "~/components/ui/slider"
import { formatUptime } from "~/lib/utils"
import { INSTANCE_STATE_CONFIG } from "~/lib/constants"
import { toast } from "vue-sonner"

const store = useInstancesStore()
const groupsStore = useGroupsStore()
const { t } = useI18n()

const startOpen = ref(false)
const startGroup = ref("")
const startCount = ref(1)
const starting = ref(false)

onMounted(() => { groupsStore.fetchGroups() })

const availableGroups = computed(() => groupsStore.groups.filter(g => !g.maintenance))

async function startInstance() {
  if (!startGroup.value) return
  starting.value = true
  try {
    const count = startCount.value
    await useApiClient().POST('/api/v1/groups/{name}/start', { params: { path: { name: startGroup.value } }, body: count > 1 ? { count } : {} })
    toast.success(t('pages.instances.toast.scheduled', { count }, count), { description: t('pages.instances.toast.scheduledDesc', { group: startGroup.value }) })
    startOpen.value = false
    startGroup.value = ""
    startCount.value = 1
  } catch {
    toast.error(t('pages.instances.toast.startFailed'), { description: t('pages.instances.toast.startFailedDesc') })
  } finally {
    starting.value = false
  }
}

const stateFilters = computed(() => [
  { key: "ALL", label: t("pages.instances.filters.all"), icon: Box },
  { key: "RUNNING", label: t("pages.instances.filters.running"), icon: Play },
  { key: "STARTING", label: t("pages.instances.filters.starting"), icon: Clock },
  { key: "STOPPING", label: t("pages.instances.filters.stopping"), icon: Pause },
  { key: "STOPPED", label: t("pages.instances.filters.stopped"), icon: Square },
  { key: "CRASHED", label: t("pages.instances.filters.crashed"), icon: AlertTriangle },
])

const { search, activeFilters, viewMode, filteredItems: filteredInstances, toggleFilter } = useFilteredList(
  () => store.instances,
  {
    searchFields: i => [i.id, i.group, i.node],
    filterField: i => i.state,
  },
)

onMounted(() => { store.fetchInstances(); store.connectSse() })
onUnmounted(() => { store.disconnectSse() })

const CARD_HEIGHT = 148; const CARD_GAP = 16
const gridRows = computed(() => { const rows = []; for (let i = 0; i < filteredInstances.value.length; i += 3) rows.push(filteredInstances.value.slice(i, i + 3)); return rows })
const { list: virtualGridRows, containerProps: gridContainerProps, wrapperProps: gridWrapperProps } = useVirtualList(gridRows, { itemHeight: CARD_HEIGHT + CARD_GAP, overscan: 5 })
const ROW_HEIGHT = 49
const { list: virtualTableRows, containerProps: tableContainerProps, wrapperProps: tableWrapperProps } = useVirtualList(filteredInstances, { itemHeight: ROW_HEIGHT, overscan: 15 })

// Bulk selection — actions live in the BulkActionBar at the bottom of the
// viewport when count > 0. Selection persists across virtua scrolls because
// useSelection keys by id, not row index.
// Cast through ComputedRef<ServerInstance[]> because the SDK-typed list
// has a wider state field than the local ServerInstance interface (handled
// in the table by string fall-throughs).
const { selected, count: selectedCount, isAll, has: isSelected, toggle: toggleSelected, toggleAll, clear: clearSelection } =
  useSelection(filteredInstances as unknown as ComputedRef<ServerInstance[]>, i => i.id)

const bulkBusy = ref(false)
async function bulkStop() {
  bulkBusy.value = true
  try {
    await Promise.allSettled(Array.from(selected.value).map(id => store.stopInstance(id)))
    toast.success(t('pages.instances.toast.bulkStopping', { count: selected.value.size }))
    clearSelection()
  } catch {
    toast.error(t('pages.instances.toast.bulkStopFailed'), { description: t('pages.instances.toast.bulkStopFailedDesc') })
  } finally {
    bulkBusy.value = false
  }
}
async function bulkForceStop() {
  bulkBusy.value = true
  try {
    await Promise.allSettled(Array.from(selected.value).map(id => store.forceStopInstance(id)))
    toast.success(t('pages.instances.toast.bulkKilled', { count: selected.value.size }))
    clearSelection()
  } catch {
    toast.error(t('pages.instances.toast.bulkForceStopFailed'), { description: t('pages.instances.toast.bulkForceStopFailedDesc') })
  } finally {
    bulkBusy.value = false
  }
}
async function bulkDelete() {
  bulkBusy.value = true
  try {
    await Promise.allSettled(Array.from(selected.value).map(id => store.deleteInstance(id)))
    toast.success(t('pages.instances.toast.bulkDeleted', { count: selected.value.size }))
    clearSelection()
  } catch {
    toast.error(t('pages.instances.toast.bulkDeleteFailed'), { description: t('pages.instances.toast.bulkDeleteFailedDesc') })
  } finally {
    bulkBusy.value = false
  }
}

// Row click → DetailSheet (full-page route still reachable via the drawer's
// "Open full page" link).
const sheetInstance = ref<ServerInstance | null>(null)
const sheetOpen = computed({
  get: () => sheetInstance.value !== null,
  set: (v) => { if (!v) sheetInstance.value = null },
})
function openSheet(inst: ServerInstance) { sheetInstance.value = inst }

const sheetActing = ref(false)
async function sheetStop() {
  if (!sheetInstance.value) return
  sheetActing.value = true
  try { await store.stopInstance(sheetInstance.value.id) } finally { sheetActing.value = false }
}
async function sheetForceStop() {
  if (!sheetInstance.value) return
  sheetActing.value = true
  try { await store.forceStopInstance(sheetInstance.value.id) } finally { sheetActing.value = false }
}
async function sheetDelete() {
  if (!sheetInstance.value) return
  sheetActing.value = true
  try {
    await store.deleteInstance(sheetInstance.value.id)
    sheetInstance.value = null
  } finally { sheetActing.value = false }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <PageHeader :title="t('pages.instances.title')" :description="t('pages.instances.description')">
      <template #actions>
        <Button @click="startOpen = true">
          <Plus class="mr-2 size-4" /> {{ t('pages.instances.startButton') }}
        </Button>
      </template>
    </PageHeader>

    <!-- Start Instance Dialog -->
    <Dialog :open="startOpen" @update:open="(v: boolean) => { startOpen = v; if (!v) startGroup = '' }">
      <DialogContent class="bg-popover backdrop-blur-xl border-glass-border rounded-2xl sm:max-w-md [&>button:last-child]:hidden overflow-hidden p-0">
        <div class="relative h-28 bg-glass/40 overflow-hidden">
          <div class="absolute inset-0 bg-dot-pattern" />
          <div class="absolute inset-0 bg-gradient-to-b from-transparent via-transparent to-popover" />
          <div class="absolute inset-0 flex flex-col items-center justify-center gap-2">
            <div class="size-12 rounded-2xl bg-primary/10 border border-primary/20 flex items-center justify-center">
              <Zap class="size-6 text-primary" />
            </div>
            <div class="text-center">
              <DialogTitle class="text-base font-bold text-foreground">{{ t('pages.instances.startDialog.title') }}</DialogTitle>
              <DialogDescription class="text-xs text-muted-foreground mt-0.5">
                {{ t('pages.instances.startDialog.description') }}
              </DialogDescription>
            </div>
          </div>
        </div>
        <div class="px-6 pb-6 flex flex-col gap-4 pt-4">
          <div v-if="availableGroups.length === 0" class="text-sm text-muted-foreground text-center py-6">
            {{ t('pages.instances.startDialog.noGroups') }}
          </div>
          <div v-else class="flex flex-col gap-2">
            <span class="text-xs font-medium text-muted-foreground uppercase tracking-wider">{{ t('pages.instances.startDialog.selectGroup') }}</span>
            <div class="flex flex-col gap-1.5 max-h-56 overflow-y-auto styled-scrollbar">
              <button
                v-for="g in availableGroups"
                :key="g.name"
                class="flex items-center gap-3 w-full rounded-xl px-3 py-2.5 text-left transition-all duration-150 border cursor-pointer"
                :class="startGroup === g.name
                  ? 'bg-primary/10 border-primary/30 ring-1 ring-primary/20'
                  : 'bg-glass/40 border-glass-border hover:bg-glass-hover hover:border-glass-border-hover'"
                @click="startGroup = g.name"
              >
                <div
class="size-9 rounded-lg flex items-center justify-center shrink-0"
                     :class="startGroup === g.name ? 'bg-primary/15 text-primary' : 'bg-glass text-muted-foreground'">
                  <Layers class="size-4" />
                </div>
                <div class="flex-1 min-w-0">
                  <div class="text-sm font-medium text-foreground truncate">{{ g.name }}</div>
                  <div class="text-xs text-muted-foreground">{{ g.platform }} {{ g.platformVersion }}</div>
                </div>
                <div v-if="startGroup === g.name" class="size-5 rounded-full bg-primary flex items-center justify-center shrink-0">
                  <svg class="size-3 text-primary-foreground" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="3"><path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" /></svg>
                </div>
              </button>
            </div>
          </div>
          <!-- Count slider -->
          <div v-if="availableGroups.length > 0" class="flex flex-col gap-1">
            <span class="text-xs font-medium text-muted-foreground uppercase tracking-wider">{{ t('pages.instances.startDialog.count') }}</span>
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
            <Button variant="outline" class="border-glass-border" @click="startOpen = false">{{ t('pages.instances.startDialog.cancel') }}</Button>
            <Button class="bg-primary hover:bg-primary/90 text-primary-foreground" :disabled="!startGroup || starting" @click="startInstance">
              <Loader2 v-if="starting" class="size-4 mr-1.5 animate-spin" />
              {{ starting ? t('pages.instances.startDialog.starting') : startCount > 1 ? t('pages.instances.startDialog.submitMany', { count: startCount }) : t('pages.instances.startDialog.submitOne') }}
            </Button>
          </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>

    <FilterToolbar
      v-model:search="search"
      :filters="stateFilters"
      :active-filters="activeFilters"
      :view-mode="viewMode"
      :search-placeholder="t('pages.instances.searchPlaceholder')"
      @toggle-filter="toggleFilter"
      @update:view-mode="viewMode = $event"
    />

    <div>
      <LoadingSkeleton v-if="store.loading" />

      <EmptyState
        v-else-if="filteredInstances.length === 0"
        :icon="Box"
        :title="t('pages.instances.emptyTitle')"
        :description="search || !activeFilters.has('ALL') ? t('pages.instances.emptyFilterHint') : t('pages.instances.emptyStartHint')"
      />

      <template v-else-if="viewMode === 'grid'">
        <div v-if="filteredInstances.length <= 30" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <InstancesInstanceCard v-for="inst in filteredInstances" :key="inst.id" :instance="(inst as unknown as ServerInstance)" />
        </div>
        <div v-else v-bind="gridContainerProps" class="h-[calc(100vh-280px)] overflow-auto styled-scrollbar pr-3">
          <div v-bind="gridWrapperProps">
            <div v-for="{ data: row, index } in virtualGridRows" :key="index" class="grid grid-cols-3 gap-4" :style="{ height: `${CARD_HEIGHT}px`, marginBottom: `${CARD_GAP}px` }">
              <InstancesInstanceCard v-for="inst in row" :key="inst.id" :instance="(inst as unknown as ServerInstance)" />
            </div>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border overflow-hidden">
          <div class="flex items-center h-10 px-4 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider">
            <div class="w-8 shrink-0 -ml-1">
              <Checkbox :model-value="isAll" :aria-label="t('pages.instances.selectAll')" @update:model-value="toggleAll" />
            </div>
            <div class="w-44 shrink-0">{{ t('pages.instances.columns.instance') }}</div>
            <div class="w-24 shrink-0 text-center">{{ t('pages.instances.columns.state') }}</div>
            <div class="w-32 shrink-0">{{ t('pages.instances.columns.group') }}</div>
            <div class="w-32 shrink-0">{{ t('pages.instances.columns.node') }}</div>
            <div class="w-20 shrink-0 text-right">{{ t('pages.instances.columns.players') }}</div>
            <div class="flex-1 text-right">{{ t('pages.instances.columns.uptime') }}</div>
            <div class="w-10 shrink-0" />
          </div>
          <div v-if="filteredInstances.length <= 50">
            <div v-for="inst in filteredInstances" :key="inst.id" class="flex items-center h-12 px-4 border-b border-glass-border/50 last:border-0 cursor-pointer transition-colors select-none hover:bg-glass-hover group/row" :class="isSelected(inst.id) ? 'bg-glass/40' : ''" @click="openSheet(inst as unknown as ServerInstance)">
              <div class="w-8 shrink-0 -ml-1" @click.stop>
                <Checkbox :model-value="isSelected(inst.id)" :aria-label="t('pages.instances.selectOne', { id: inst.id })" @update:model-value="toggleSelected(inst.id)" />
              </div>
              <div class="w-44 shrink-0 flex items-center gap-2"><div :class="['size-2 rounded-full shrink-0', INSTANCE_STATE_CONFIG[inst.state]?.dot ?? 'bg-muted-foreground']" /><span class="text-sm font-medium text-foreground truncate">{{ inst.id }}</span></div>
              <div class="w-24 shrink-0 text-center"><Badge variant="outline" :class="['text-xs', INSTANCE_STATE_CONFIG[inst.state]?.color]">{{ INSTANCE_STATE_CONFIG[inst.state]?.label ?? inst.state }}</Badge></div>
              <div class="w-32 shrink-0 text-sm text-muted-foreground truncate">{{ inst.group }}</div>
              <div class="w-32 shrink-0 text-sm text-muted-foreground truncate">{{ inst.node }}</div>
              <div class="w-20 shrink-0 text-right text-sm text-foreground tabular-nums">{{ inst.playerCount }}</div>
              <div class="flex-1 text-right text-sm text-muted-foreground tabular-nums">{{ formatUptime(inst.uptimeMs) }}</div>
              <div class="w-10 shrink-0 flex justify-end">
                <button v-if="inst.state === 'RUNNING' || inst.state === 'STARTING'" class="size-7 rounded-lg flex items-center justify-center text-muted-foreground/0 group-hover/row:text-muted-foreground hover:text-warning! hover:bg-warning/10 transition-all" :title="t('pages.instances.rowStop')" @click.stop="store.stopInstance(inst.id)">
                  <Square class="size-3.5" />
                </button>
                <button v-else-if="inst.state === 'STOPPED' || inst.state === 'CRASHED' || inst.state === 'SCHEDULED'" class="size-7 rounded-lg flex items-center justify-center text-muted-foreground/0 group-hover/row:text-muted-foreground hover:text-destructive! hover:bg-destructive/10 transition-all" :title="t('pages.instances.rowDelete')" @click.stop="store.deleteInstance(inst.id)">
                  <Trash2 class="size-3.5" />
                </button>
              </div>
            </div>
          </div>
          <div v-else v-bind="tableContainerProps" class="h-[calc(100vh-320px)] overflow-auto styled-scrollbar mr-1">
            <div v-bind="tableWrapperProps">
              <div v-for="{ data: inst } in virtualTableRows" :key="inst.id" class="flex items-center h-12 px-4 border-b border-glass-border/50 cursor-pointer transition-colors select-none hover:bg-glass-hover group/row" :class="isSelected(inst.id) ? 'bg-glass/40' : ''" @click="openSheet(inst as unknown as ServerInstance)">
                <div class="w-8 shrink-0 -ml-1" @click.stop>
                  <Checkbox :model-value="isSelected(inst.id)" :aria-label="t('pages.instances.selectOne', { id: inst.id })" @update:model-value="toggleSelected(inst.id)" />
                </div>
                <div class="w-44 shrink-0 flex items-center gap-2"><div :class="['size-2 rounded-full shrink-0', INSTANCE_STATE_CONFIG[inst.state]?.dot ?? 'bg-muted-foreground']" /><span class="text-sm font-medium text-foreground truncate">{{ inst.id }}</span></div>
                <div class="w-24 shrink-0 text-center"><Badge variant="outline" :class="['text-xs', INSTANCE_STATE_CONFIG[inst.state]?.color]">{{ INSTANCE_STATE_CONFIG[inst.state]?.label ?? inst.state }}</Badge></div>
                <div class="w-32 shrink-0 text-sm text-muted-foreground truncate">{{ inst.group }}</div>
                <div class="w-32 shrink-0 text-sm text-muted-foreground truncate">{{ inst.node }}</div>
                <div class="w-20 shrink-0 text-right text-sm text-foreground tabular-nums">{{ inst.playerCount }}</div>
                <div class="flex-1 text-right text-sm text-muted-foreground tabular-nums">{{ formatUptime(inst.uptimeMs) }}</div>
                <div class="w-10 shrink-0 flex justify-end">
                  <button v-if="inst.state === 'RUNNING' || inst.state === 'STARTING'" class="size-7 rounded-lg flex items-center justify-center text-muted-foreground/0 group-hover/row:text-muted-foreground hover:text-warning! hover:bg-warning/10 transition-all" :title="t('pages.instances.rowStop')" @click.stop="store.stopInstance(inst.id)">
                    <Square class="size-3.5" />
                  </button>
                  <button v-else-if="inst.state === 'STOPPED' || inst.state === 'CRASHED' || inst.state === 'SCHEDULED'" class="size-7 rounded-lg flex items-center justify-center text-muted-foreground/0 group-hover/row:text-muted-foreground hover:text-destructive! hover:bg-destructive/10 transition-all" :title="t('pages.instances.rowDelete')" @click.stop="store.deleteInstance(inst.id)">
                    <Trash2 class="size-3.5" />
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <BulkActionBar
      :count="selectedCount"
      singular="instance"
      plural="instances"
      @clear="clearSelection"
    >
      <Button variant="outline" size="sm" :disabled="bulkBusy" class="border-warning/50 text-warning hover:bg-warning/10" @click="bulkStop">
        <Square class="mr-1.5 size-3.5" /> {{ t('pages.instances.actionStop') }}
      </Button>
      <Button variant="outline" size="sm" :disabled="bulkBusy" class="border-destructive/50 text-destructive hover:bg-destructive/10" @click="bulkForceStop">
        <Zap class="mr-1.5 size-3.5" /> {{ t('pages.instances.actionForceStop') }}
      </Button>
      <Button variant="outline" size="sm" :disabled="bulkBusy" class="border-destructive/50 text-destructive hover:bg-destructive/10" @click="bulkDelete">
        <Trash2 class="mr-1.5 size-3.5" /> {{ t('pages.instances.actionDelete') }}
      </Button>
    </BulkActionBar>

    <DetailSheet
      :open="sheetOpen"
      :title="sheetInstance?.id"
      :eyebrow="t('pages.instances.eyebrow')"
      :full-page-path="sheetInstance ? `/instances/${sheetInstance.id}` : undefined"
      @update:open="sheetOpen = $event"
    >
      <template v-if="sheetInstance" #status>
        <StatusBadge :state="sheetInstance.state" :pulse="sheetInstance.state === 'RUNNING'" />
      </template>
      <template v-if="sheetInstance" #actions>
        <Button
v-if="sheetInstance.state === 'RUNNING' || sheetInstance.state === 'STARTING'"
          variant="outline" size="sm" :disabled="sheetActing"
          class="border-warning/50 text-warning hover:bg-warning/10" @click="sheetStop">
          <Square class="mr-1 size-3.5" /> {{ t('pages.instances.actionStop') }}
        </Button>
        <Button
v-if="sheetInstance.state === 'RUNNING' || sheetInstance.state === 'STARTING' || sheetInstance.state === 'STOPPING'"
          variant="outline" size="sm" :disabled="sheetActing"
          class="border-destructive/50 text-destructive hover:bg-destructive/10" @click="sheetForceStop">
          <Zap class="mr-1 size-3.5" /> {{ t('pages.instances.actionForceStop') }}
        </Button>
        <Button
v-if="sheetInstance.state === 'STOPPED' || sheetInstance.state === 'CRASHED' || sheetInstance.state === 'SCHEDULED'"
          variant="outline" size="sm" :disabled="sheetActing"
          class="border-destructive/50 text-destructive hover:bg-destructive/10" @click="sheetDelete">
          <Trash2 class="mr-1 size-3.5" /> {{ t('pages.instances.actionDelete') }}
        </Button>
      </template>

      <div v-if="sheetInstance" class="space-y-4 text-sm">
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.instances.detail.group') }}</span>
          <NuxtLink :to="`/groups/${sheetInstance.group}`" class="mono text-primary hover:underline">{{ sheetInstance.group }}</NuxtLink>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.instances.detail.node') }}</span>
          <NuxtLink :to="`/nodes/${sheetInstance.node}`" class="mono text-primary hover:underline">{{ sheetInstance.node }}</NuxtLink>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.instances.detail.port') }}</span>
          <span class="tabular">{{ sheetInstance.port }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.instances.detail.players') }}</span>
          <span class="tabular">{{ sheetInstance.playerCount }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.instances.detail.uptime') }}</span>
          <span class="tabular">{{ formatUptime(sheetInstance.uptimeMs) }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.instances.detail.started') }}</span>
          <span class="text-xs">{{ new Date(sheetInstance.startedAt).toLocaleString() }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.instances.detail.deployment') }}</span>
          <span class="tabular">{{ t('pages.instances.detail.deploymentValue', { revision: sheetInstance.deploymentRevision }) }}</span>
        </div>
      </div>
    </DetailSheet>
  </div>
</template>
