<script setup lang="ts">
import { onMounted, ref } from "vue"
import { KeyRound, Trash2, ShieldOff } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Checkbox } from "~/components/ui/checkbox"
import { StatusBadge } from "~/components/ui/status-badge"
import { BulkActionBar } from "~/components/ui/bulk-action-bar"
import { toast } from "vue-sonner"
import { timeAgo } from "~/lib/utils"

const { t } = useI18n()
const store = useWorkloadCredentialsStore()

onMounted(() => store.fetchCredentials())

const { search, filteredItems: filteredCreds } = useFilteredList(
  () => store.credentials,
  {
    searchFields: c => [c.tokenId, c.instanceId, c.group ?? "", c.node ?? ""],
    defaultView: "table",
  },
)

const { count: selectedCount, isAll, has: isSelected, toggle: toggleSelected, toggleAll, clear: clearSelection, selected } =
  useSelection(filteredCreds, c => c.tokenId)

const bulkBusy = ref(false)
async function bulkRevoke() {
  bulkBusy.value = true
  try {
    const ids = Array.from(selected.value)
    await Promise.allSettled(ids.map(id => store.revokeCredential(id)))
    toast.success(t('pages.credentials.revokedToast', { count: ids.length }, ids.length))
    clearSelection()
  } finally { bulkBusy.value = false }
}

async function revokeAllForInstance(instanceId: string) {
  await store.revokeAllForInstance(instanceId)
}

function expiryLabel(expiresAt: string | null | undefined) {
  if (!expiresAt) return t('pages.credentials.expiry.never')
  const ms = new Date(expiresAt).getTime() - Date.now()
  if (ms < 0) return t('pages.credentials.expiry.expired')
  const hrs = Math.floor(ms / 3600 / 1000)
  if (hrs < 24) return t('pages.credentials.expiry.hours', { hours: hrs })
  const days = Math.floor(hrs / 24)
  return t('pages.credentials.expiry.days', { days })
}
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader
      :title="t('pages.credentials.title')"
      :description="t('pages.credentials.description')"
    />

    <FilterToolbar
      v-model:search="search"
      :filters="[]"
      :active-filters="new Set(['ALL'])"
      :search-placeholder="t('pages.credentials.searchPlaceholder')"
      :show-view-toggle="false"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="6" />

    <EmptyState
      v-else-if="filteredCreds.length === 0"
      :icon="KeyRound"
      :title="search ? t('pages.credentials.emptyMatchesTitle') : t('pages.credentials.emptyTitle')"
      :description="search ? t('pages.credentials.emptyMatchesHint') : t('pages.credentials.emptyHint')"
    />

    <div v-else class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
      <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
        <div class="w-8 shrink-0 -ml-1">
          <Checkbox :model-value="isAll" :aria-label="t('pages.credentials.selectAll')" @update:model-value="toggleAll" />
        </div>
        <div class="w-40 shrink-0">{{ t('pages.credentials.columns.token') }}</div>
        <div class="w-44 shrink-0">{{ t('pages.credentials.columns.instance') }}</div>
        <div class="w-32 shrink-0">{{ t('pages.credentials.columns.group') }}</div>
        <div class="w-32 shrink-0">{{ t('pages.credentials.columns.node') }}</div>
        <div class="w-32 shrink-0 text-right">{{ t('pages.credentials.columns.issued') }}</div>
        <div class="w-32 shrink-0 text-right">{{ t('pages.credentials.columns.expiry') }}</div>
        <div class="flex-1 text-right">{{ t('pages.credentials.columns.actions') }}</div>
      </div>
      <div
        v-for="c in filteredCreds"
        :key="c.tokenId"
        class="flex h-12 select-none items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover group/row"
        :class="isSelected(c.tokenId) ? 'bg-glass/40' : ''"
      >
        <div class="w-8 shrink-0 -ml-1">
          <Checkbox :model-value="isSelected(c.tokenId)" :aria-label="t('pages.credentials.selectOne', { id: c.tokenId })" @update:model-value="toggleSelected(c.tokenId)" />
        </div>
        <div class="w-40 shrink-0 truncate mono text-xs">{{ c.tokenId }}</div>
        <NuxtLink :to="`/instances/${c.instanceId}`" class="w-44 shrink-0 truncate text-sm font-medium mono text-primary hover:underline">{{ c.instanceId }}</NuxtLink>
        <div class="w-32 shrink-0 truncate text-sm text-muted-foreground">{{ c.group || '—' }}</div>
        <div class="w-32 shrink-0 truncate text-sm text-muted-foreground mono">{{ c.node || '—' }}</div>
        <div class="w-32 shrink-0 text-right text-sm text-muted-foreground tabular">{{ timeAgo(c.issuedAt) }}</div>
        <div class="w-32 shrink-0 text-right">
          <StatusBadge :tone="c.expiresAt ? 'warning' : 'muted'" :label="expiryLabel(c.expiresAt)" />
        </div>
        <div class="flex flex-1 justify-end gap-1">
          <button
            type="button"
            :title="t('pages.credentials.revokeAllFor', { instance: c.instanceId })"
            class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-warning/10 hover:text-warning"
            @click.stop="revokeAllForInstance(c.instanceId)"
          >
            <ShieldOff class="size-3.5" />
          </button>
          <button
            type="button"
            :aria-label="t('pages.credentials.revokeCredential')"
            class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
            @click.stop="store.revokeCredential(c.tokenId)"
          >
            <Trash2 class="size-3.5" />
          </button>
        </div>
      </div>
    </div>

    <BulkActionBar :count="selectedCount" singular="credential" plural="credentials" @clear="clearSelection">
      <Button variant="outline" size="sm" :disabled="bulkBusy" class="border-destructive/50 text-destructive hover:bg-destructive/10" @click="bulkRevoke">
        <Trash2 class="mr-1.5 size-3.5" /> {{ t('pages.credentials.revoke') }}
      </Button>
    </BulkActionBar>
  </div>
</template>
