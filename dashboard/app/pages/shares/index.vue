<script setup lang="ts">
import { Share2, ExternalLink, Trash2 } from "lucide-vue-next"
import { StatusBadge } from "~/components/ui/status-badge"

const store = useShareStore()
const { t } = useI18n()
const revokingId = ref<string | null>(null)

onMounted(() => { store.fetchRecords(1) })

async function revoke(id: string, kind: string) {
  if (revokingId.value) return
  if (!window.confirm(t("pages.shares.revokeConfirm", { id, kind }))) return
  revokingId.value = id
  try {
    await store.revoke(id)
  } finally {
    revokingId.value = null
  }
}

function relTime(iso: string | null | undefined): string {
  if (!iso) return ""
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const delta = Date.now() - d.getTime()
  const sec = Math.floor(delta / 1000)
  if (sec < 60)   return t("pages.shares.justNow")
  if (sec < 3600) return `${Math.floor(sec / 60)}m`
  if (sec < 86400) return `${Math.floor(sec / 3600)}h`
  return `${Math.floor(sec / 86400)}d`
}

const totalPages = computed(() => Math.max(1, Math.ceil(store.total / store.pageSize)))
const canPrev = computed(() => store.page > 1)
const canNext = computed(() => store.page < totalPages.value)
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader :title="t('pages.shares.title')" :description="t('pages.shares.description')" />

    <div class="flex items-center gap-3 px-4 py-2 rounded-xl border border-glass-border bg-glass/60 backdrop-blur-xl">
      <label class="text-sm text-muted-foreground">{{ t('pages.shares.filterKind') }}</label>
      <select
        v-model="store.activeFilter"
        class="bg-transparent border border-glass-border rounded-md px-2 py-1 text-sm"
        @change="store.fetchRecords(1)"
      >
        <option :value="null">{{ t('pages.shares.kindAll') }}</option>
        <option value="CRASH">CRASH</option>
        <option value="CONTROLLER_LOGS">CONTROLLER_LOGS</option>
        <option value="DAEMON_LOGS">DAEMON_LOGS</option>
        <option value="DIAGNOSTICS">DIAGNOSTICS</option>
        <option value="INSTANCE_CONSOLE">INSTANCE_CONSOLE</option>
      </select>

      <label class="flex items-center gap-1.5 text-sm text-muted-foreground ml-3">
        <input
          v-model="store.activeOnly"
          type="checkbox"
          class="accent-primary"
          @change="store.fetchRecords(1)"
        >
        {{ t('pages.shares.activeOnly') }}
      </label>
    </div>

    <LoadingSkeleton v-if="store.loading" mode="table" :count="6" />

    <EmptyState
      v-else-if="store.records.length === 0"
      :icon="Share2"
      :title="t('pages.shares.emptyTitle')"
      :description="t('pages.shares.emptyHint')"
    />

    <template v-else>
      <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border overflow-hidden">
        <div class="flex items-center h-10 px-4 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider">
          <div class="w-28 shrink-0">{{ t('pages.shares.columns.kind') }}</div>
          <div class="w-40 shrink-0">{{ t('pages.shares.columns.resource') }}</div>
          <div class="w-32 shrink-0">{{ t('pages.shares.columns.sharedBy') }}</div>
          <div class="w-20 shrink-0 text-right">{{ t('pages.shares.columns.size') }}</div>
          <div class="w-20 shrink-0 text-right">{{ t('pages.shares.columns.age') }}</div>
          <div class="flex-1 px-2">{{ t('pages.shares.columns.url') }}</div>
          <div class="w-32 shrink-0 text-right">{{ t('pages.shares.columns.actions') }}</div>
        </div>
        <div
          v-for="r in store.records"
          :key="r.id"
          class="flex items-center h-12 px-4 border-b border-glass-border/50 last:border-0"
        >
          <div class="w-28 shrink-0">
            <StatusBadge
              :tone="r.revokedAt ? 'muted' : 'primary'"
              :label="r.kind"
            />
          </div>
          <div class="w-40 shrink-0 text-sm mono text-muted-foreground truncate" :title="r.resourceId ?? ''">{{ r.resourceId || '—' }}</div>
          <div class="w-32 shrink-0 text-sm text-muted-foreground truncate" :title="r.sharedByUser">{{ r.sharedByUser }}</div>
          <div class="w-20 shrink-0 text-sm text-right tabular-nums">{{ r.sizeBytes }}</div>
          <div class="w-20 shrink-0 text-sm text-right tabular-nums text-muted-foreground">{{ relTime(r.sharedAt) }}</div>
          <div class="flex-1 px-2 text-sm truncate">
            <a :href="r.url" target="_blank" rel="noopener noreferrer" class="text-primary hover:underline inline-flex items-center gap-1">
              {{ r.url }} <ExternalLink class="size-3" />
            </a>
          </div>
          <div class="w-32 shrink-0 flex justify-end">
            <button
              v-if="r.revocable && !r.revokedAt"
              :disabled="revokingId === r.id"
              class="inline-flex items-center gap-1.5 px-2.5 py-1 text-xs rounded-md border border-glass-border bg-glass/60 hover:bg-glass-hover disabled:opacity-50"
              @click="revoke(r.id, r.kind)"
            >
              <Trash2 class="size-3.5" /> {{ t('pages.shares.revoke') }}
            </button>
            <span v-else-if="r.revokedAt" class="text-xs text-muted-foreground">{{ t('pages.shares.revoked') }}</span>
            <span v-else class="text-xs text-muted-foreground">{{ t('pages.shares.notRevocable') }}</span>
          </div>
        </div>
      </div>

      <div class="flex items-center justify-between">
        <span class="text-sm text-muted-foreground">
          {{ t('pages.shares.pageInfo', { page: store.page, total: totalPages, count: store.total }) }}
        </span>
        <div class="flex gap-2">
          <button
            :disabled="!canPrev"
            class="px-3 py-1.5 text-sm rounded-lg border border-glass-border bg-glass/60 backdrop-blur-xl transition-colors hover:bg-glass-hover disabled:opacity-40"
            @click="store.fetchRecords(store.page - 1)"
          >
            {{ t('pages.common.previous') }}
          </button>
          <button
            :disabled="!canNext"
            class="px-3 py-1.5 text-sm rounded-lg border border-glass-border bg-glass/60 backdrop-blur-xl transition-colors hover:bg-glass-hover disabled:opacity-40"
            @click="store.fetchRecords(store.page + 1)"
          >
            {{ t('pages.common.next') }}
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
