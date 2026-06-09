<script setup lang="ts">
import { onMounted } from "vue"
import { Rocket } from "lucide-vue-next"
import { StatusBadge } from "~/components/ui/status-badge"
import type { StatusDotTone } from "~/components/ui/status-dot"
import { timeAgo } from "~/lib/utils"

const { t } = useI18n()
const store = useDeploymentsAggregateStore()

onMounted(() => store.fetchAll())

const { search, filteredItems: filteredDeployments } = useFilteredList(
  () => store.deployments,
  {
    searchFields: d => [d.groupName, d.strategy, d.state, d.trigger ?? ""],
    defaultView: "table",
  },
)

function stateTone(state: string): StatusDotTone {
  if (state === "COMPLETED") return "success"
  if (state === "IN_PROGRESS" || state === "PENDING") return "primary"
  if (state === "PAUSED") return "warning"
  if (state === "FAILED" || state === "ROLLED_BACK") return "destructive"
  return "muted"
}

function statePulse(state: string) {
  return state === "IN_PROGRESS" || state === "PENDING"
}
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader :title="t('pages.deployments.title')" :description="t('pages.deployments.description')" />

    <FilterToolbar
      v-model:search="search"
      :filters="[]"
      :active-filters="new Set(['ALL'])"
      :search-placeholder="t('pages.deployments.searchPlaceholder')"
      :show-view-toggle="false"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="5" />

    <EmptyState
      v-else-if="filteredDeployments.length === 0"
      :icon="Rocket"
      :title="search ? t('pages.deployments.emptyMatchesTitle') : t('pages.deployments.emptyTitle')"
      :description="search ? t('pages.deployments.emptyMatchesHint') : t('pages.deployments.emptyHint')"
    />

    <div v-else class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
      <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
        <div class="w-44 shrink-0">{{ t('pages.deployments.columns.group') }}</div>
        <div class="w-20 shrink-0 text-right">{{ t('pages.deployments.columns.rev') }}</div>
        <div class="w-32 shrink-0">{{ t('pages.deployments.columns.strategy') }}</div>
        <div class="w-32 shrink-0">{{ t('pages.deployments.columns.state') }}</div>
        <div class="w-32 shrink-0 text-right">{{ t('pages.deployments.columns.progress') }}</div>
        <div class="w-32 shrink-0">{{ t('pages.deployments.columns.trigger') }}</div>
        <div class="flex-1 text-right">{{ t('pages.deployments.columns.started') }}</div>
      </div>
      <NuxtLink
        v-for="d in filteredDeployments"
        :key="`${d.groupName}-${d.id}`"
        :to="`/groups/${d.groupName}`"
        class="flex h-12 items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover"
      >
        <div class="w-44 shrink-0 truncate text-sm font-medium mono text-primary">{{ d.groupName }}</div>
        <div class="w-20 shrink-0 text-right tabular text-sm">{{ d.revision }}</div>
        <div class="w-32 shrink-0 text-sm text-muted-foreground">{{ d.strategy }}</div>
        <div class="w-32 shrink-0">
          <StatusBadge :tone="stateTone(d.state)" :label="d.state" :pulse="statePulse(d.state)" />
        </div>
        <div class="w-32 shrink-0 text-right tabular text-sm">
          {{ d.updatedInstances }} / {{ d.totalInstances }}
        </div>
        <div class="w-32 shrink-0 text-sm text-muted-foreground mono">{{ d.trigger ?? '—' }}</div>
        <div class="flex-1 text-right text-sm text-muted-foreground tabular">{{ timeAgo(d.createdAt) }}</div>
      </NuxtLink>
    </div>
  </div>
</template>
