<script setup lang="ts">
import { Layers, Activity, Box, Rocket, RefreshCw, Zap } from "lucide-vue-next"
import { useVirtualList } from "@vueuse/core"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import { DetailSheet } from "~/components/ui/detail-sheet"
import { StatusBadge } from "~/components/ui/status-badge"
import { SCALING_MODE_CONFIG } from "~/lib/constants"
import type { ServerGroup } from "~/types/api"
import { toast } from "vue-sonner"
import CreateGroupDialog from "~/components/groups/CreateGroupDialog.vue"

const store = useGroupsStore()
const { t } = useI18n()

const scalingFilters = computed(() => [
  { key: "ALL", label: t("pages.groups.filters.all"), icon: Layers },
  { key: "DYNAMIC", label: t("pages.groups.filters.dynamic"), icon: Activity },
  { key: "STATIC", label: t("pages.groups.filters.static"), icon: Box },
  { key: "MANUAL", label: t("pages.groups.filters.manual"), icon: Box },
])

const { search, activeFilters, viewMode, filteredItems: filteredGroups, toggleFilter } = useFilteredList(
  () => store.groups,
  {
    searchFields: g => [g.name, g.platform],
    filterField: g => g.scalingMode,
  },
)

onMounted(() => { store.fetchGroups(); store.connectSse() })
onUnmounted(() => { store.disconnectSse() })

const CARD_HEIGHT = 148; const CARD_GAP = 16
const gridRows = computed(() => { const rows = []; for (let i = 0; i < filteredGroups.value.length; i += 3) rows.push(filteredGroups.value.slice(i, i + 3)); return rows })
const { list: virtualGridRows, containerProps: gridContainerProps, wrapperProps: gridWrapperProps } = useVirtualList(gridRows, { itemHeight: CARD_HEIGHT + CARD_GAP, overscan: 5 })
const ROW_HEIGHT = 49
const { list: virtualTableRows, containerProps: tableContainerProps, wrapperProps: tableWrapperProps } = useVirtualList(filteredGroups, { itemHeight: ROW_HEIGHT, overscan: 15 })

// Row click → DetailSheet.
const sheetGroup = ref<ServerGroup | null>(null)
const sheetOpen = computed({
  get: () => sheetGroup.value !== null,
  set: (v) => { if (!v) sheetGroup.value = null },
})
function openSheet(g: ServerGroup) { sheetGroup.value = g }

const sheetActing = ref(false)
async function sheetStart() {
  if (!sheetGroup.value) return
  sheetActing.value = true
  try {
    await useApiClient().POST('/api/v1/groups/{name}/start', { params: { path: { name: sheetGroup.value.name } } })
    toast.success(t('toast.groups.instanceScheduled', { name: sheetGroup.value.name }))
  } catch {
    toast.error(t('toast.groups.startFailed'), { description: t('toast.groups.startFailedDesc') })
  } finally { sheetActing.value = false }
}
async function sheetRestart() {
  if (!sheetGroup.value) return
  sheetActing.value = true
  try {
    await useApiClient().POST('/api/v1/groups/{name}/restart', { params: { path: { name: sheetGroup.value.name } } })
    toast.success(t('toast.groups.rollingRestart', { name: sheetGroup.value.name }))
  } catch {
    toast.error(t('toast.groups.restartFailed'), { description: t('toast.groups.restartFailedDesc') })
  } finally { sheetActing.value = false }
}
async function sheetDeploy() {
  if (!sheetGroup.value) return
  sheetActing.value = true
  try {
    await useApiClient().POST('/api/v1/groups/{name}/deploy', { params: { path: { name: sheetGroup.value.name } } })
    toast.success(t('toast.groups.deploymentTriggered', { name: sheetGroup.value.name }))
  } catch {
    toast.error(t('toast.groups.deployFailed'), { description: t('toast.groups.deployFailedDesc') })
  } finally { sheetActing.value = false }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <PageHeader :title="t('pages.groups.title')" :description="t('pages.groups.description')">
      <template #actions>
        <CreateGroupDialog />
      </template>
    </PageHeader>

    <FilterToolbar
      v-model:search="search"
      :filters="scalingFilters"
      :active-filters="activeFilters"
      :view-mode="viewMode"
      :search-placeholder="t('pages.groups.searchPlaceholder')"
      @toggle-filter="toggleFilter"
      @update:view-mode="viewMode = $event"
    />

    <div>
      <LoadingSkeleton v-if="store.loading" />

      <EmptyState
        v-else-if="filteredGroups.length === 0"
        :icon="Layers"
        :title="t('pages.groups.emptyTitle')"
        :description="search || !activeFilters.has('ALL') ? t('pages.groups.emptyFilterHint') : t('pages.groups.emptyCreateHint')"
      />

      <template v-else-if="viewMode === 'grid'">
        <div v-if="filteredGroups.length <= 30" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <GroupsGroupCard v-for="g in filteredGroups" :key="g.name" :group="(g as unknown as ServerGroup)" />
        </div>
        <div v-else v-bind="gridContainerProps" class="h-[calc(100vh-280px)] overflow-auto styled-scrollbar pr-3">
          <div v-bind="gridWrapperProps">
            <div v-for="{ data: row, index } in virtualGridRows" :key="index" class="grid grid-cols-3 gap-4" :style="{ height: `${CARD_HEIGHT}px`, marginBottom: `${CARD_GAP}px` }">
              <GroupsGroupCard v-for="g in row" :key="g.name" :group="(g as unknown as ServerGroup)" />
            </div>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border overflow-hidden">
          <div class="flex items-center h-10 px-4 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider">
            <div class="w-44 shrink-0">{{ t('pages.groups.columns.group') }}</div>
            <div class="w-24 shrink-0 text-center">{{ t('pages.groups.columns.scaling') }}</div>
            <div class="w-28 shrink-0 text-center">{{ t('pages.groups.columns.platform') }}</div>
            <div class="w-24 shrink-0 text-right">{{ t('pages.groups.columns.instances') }}</div>
            <div class="w-24 shrink-0 text-right">{{ t('pages.groups.columns.players') }}</div>
            <div class="flex-1 text-right">{{ t('pages.groups.columns.memory') }}</div>
          </div>
          <div v-if="filteredGroups.length <= 50">
            <div v-for="g in filteredGroups" :key="g.name" class="flex items-center h-12 px-4 border-b border-glass-border/50 last:border-0 cursor-pointer transition-colors select-none hover:bg-glass-hover" @click="openSheet(g as unknown as ServerGroup)">
              <div class="w-44 shrink-0 flex items-center gap-2">
                <div :class="['size-2 rounded-full shrink-0', g.maintenance ? 'bg-warning' : 'bg-success']" />
                <span class="text-sm font-medium text-foreground truncate">{{ g.name }}</span>
              </div>
              <div class="w-24 shrink-0 text-center"><Badge variant="outline" :class="['text-xs', SCALING_MODE_CONFIG[g.scalingMode]?.color]">{{ SCALING_MODE_CONFIG[g.scalingMode]?.label ?? g.scalingMode }}</Badge></div>
              <div class="w-28 shrink-0 text-center text-sm text-muted-foreground">{{ g.platform }}</div>
              <div class="w-24 shrink-0 text-right text-sm text-foreground tabular-nums">{{ g.runningInstances }}/{{ g.maxInstances }}</div>
              <div class="w-24 shrink-0 text-right text-sm text-foreground tabular-nums">{{ g.totalPlayers }}/{{ g.maxPlayers }}</div>
              <div class="flex-1 text-right text-sm text-muted-foreground tabular-nums">{{ g.memoryMb }} MB</div>
            </div>
          </div>
          <div v-else v-bind="tableContainerProps" class="h-[calc(100vh-320px)] overflow-auto styled-scrollbar mr-1">
            <div v-bind="tableWrapperProps">
              <div v-for="{ data: g } in virtualTableRows" :key="g.name" class="flex items-center h-12 px-4 border-b border-glass-border/50 cursor-pointer transition-colors select-none hover:bg-glass-hover" @click="openSheet(g as unknown as ServerGroup)">
                <div class="w-44 shrink-0 flex items-center gap-2">
                  <div :class="['size-2 rounded-full shrink-0', g.maintenance ? 'bg-warning' : 'bg-success']" />
                  <span class="text-sm font-medium text-foreground truncate">{{ g.name }}</span>
                </div>
                <div class="w-24 shrink-0 text-center"><Badge variant="outline" :class="['text-xs', SCALING_MODE_CONFIG[g.scalingMode]?.color]">{{ SCALING_MODE_CONFIG[g.scalingMode]?.label ?? g.scalingMode }}</Badge></div>
                <div class="w-28 shrink-0 text-center text-sm text-muted-foreground">{{ g.platform }}</div>
                <div class="w-24 shrink-0 text-right text-sm text-foreground tabular-nums">{{ g.runningInstances }}/{{ g.maxInstances }}</div>
                <div class="w-24 shrink-0 text-right text-sm text-foreground tabular-nums">{{ g.totalPlayers }}/{{ g.maxPlayers }}</div>
                <div class="flex-1 text-right text-sm text-muted-foreground tabular-nums">{{ g.memoryMb }} MB</div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <DetailSheet
      :open="sheetOpen"
      :title="sheetGroup?.name"
      :eyebrow="t('pages.groups.eyebrow')"
      :full-page-path="sheetGroup ? `/groups/${sheetGroup.name}` : undefined"
      @update:open="sheetOpen = $event"
    >
      <template v-if="sheetGroup" #status>
        <StatusBadge
          :tone="sheetGroup.maintenance ? 'warning' : 'success'"
          :label="sheetGroup.maintenance ? t('pages.groups.statusMaintenance') : t('pages.groups.statusLive')"
        />
      </template>
      <template v-if="sheetGroup" #actions>
        <Button variant="outline" size="sm" :disabled="sheetActing" @click="sheetStart">
          <Zap class="mr-1 size-3.5" /> {{ t('pages.groups.actionStart') }}
        </Button>
        <Button variant="outline" size="sm" :disabled="sheetActing" @click="sheetRestart">
          <RefreshCw class="mr-1 size-3.5" /> {{ t('pages.groups.actionRestart') }}
        </Button>
        <Button variant="outline" size="sm" :disabled="sheetActing" @click="sheetDeploy">
          <Rocket class="mr-1 size-3.5" /> {{ t('pages.groups.actionDeploy') }}
        </Button>
      </template>

      <div v-if="sheetGroup" class="space-y-4 text-sm">
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.groups.detail.platform') }}</span>
          <span>{{ sheetGroup.platform }} {{ sheetGroup.platformVersion }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.groups.detail.scaling') }}</span>
          <Badge variant="outline" :class="['text-xs', SCALING_MODE_CONFIG[sheetGroup.scalingMode]?.color]">
            {{ SCALING_MODE_CONFIG[sheetGroup.scalingMode]?.label ?? sheetGroup.scalingMode }}
          </Badge>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.groups.detail.instances') }}</span>
          <span class="tabular">{{ t('pages.groups.detail.instancesValue', { running: sheetGroup.runningInstances, min: sheetGroup.minInstances, max: sheetGroup.maxInstances }) }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.groups.detail.players') }}</span>
          <span class="tabular">{{ sheetGroup.totalPlayers }} / {{ sheetGroup.maxPlayers }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.groups.detail.memoryPerInstance') }}</span>
          <span class="tabular">{{ sheetGroup.memoryMb }} MB</span>
        </div>
        <div v-if="sheetGroup.routing" class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.groups.detail.routing') }}</span>
          <span class="mono text-xs">{{ sheetGroup.routing }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-muted-foreground">{{ t('pages.groups.detail.updateStrategy') }}</span>
          <span class="mono text-xs">{{ sheetGroup.updateStrategy }}</span>
        </div>
      </div>
    </DetailSheet>
  </div>
</template>
