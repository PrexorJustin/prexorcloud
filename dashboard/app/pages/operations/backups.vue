<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { Database, Plus, Trash2, ShieldCheck, RotateCcw, Scissors } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Checkbox } from "~/components/ui/checkbox"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { Textarea } from "~/components/ui/textarea"
import { StatusBadge } from "~/components/ui/status-badge"
import { BulkActionBar } from "~/components/ui/bulk-action-bar"
import { Dialog, DialogContent, DialogFooter, DialogTitle } from "~/components/ui/dialog"
import { Callout, CalloutTitle } from "~/components/ui/callout"
import { toast } from "vue-sonner"
import { timeAgo, formatBytes } from "~/lib/utils"
import type { BackupRecord } from "~/stores/backups"

const { t } = useI18n()
const store = useBackupsStore()

onMounted(() => store.fetchBackups())

const { search, filteredItems: filteredBackups } = useFilteredList(
  () => store.backups,
  {
    searchFields: b => [b.id, b.notes ?? ""],
    defaultView: "table",
  },
)

const { count: selectedCount, isAll, has: isSelected, toggle: toggleSelected, toggleAll, clear: clearSelection, selected } =
  useSelection(filteredBackups, b => b.id)

function verifyTone(s?: string) {
  if (s === "OK") return "success" as const
  if (s === "FAILED") return "destructive" as const
  if (s === "PENDING") return "warning" as const
  return "muted" as const
}

function verifyLabel(b: BackupRecord) {
  if (!b.verifyStatus) return t('pages.backups.verify.notVerified')
  if (b.verifyStatus === "OK") return t('pages.backups.verify.verified', { ago: b.verifiedAt ? timeAgo(b.verifiedAt) : '' }).trim()
  if (b.verifyStatus === "FAILED") return t('pages.backups.verify.failed')
  return t('pages.backups.verify.verifying')
}

const bulkBusy = ref(false)
async function bulkDelete() {
  bulkBusy.value = true
  try {
    const ids = Array.from(selected.value)
    await Promise.allSettled(ids.map(id => store.deleteBackup(id)))
    toast.success(t('pages.backups.deletedToast', { count: ids.length }, ids.length))
    clearSelection()
  } finally { bulkBusy.value = false }
}

// Create
const createOpen = ref(false)
const createNotes = ref("")
const creating = ref(false)
async function submitCreate() {
  creating.value = true
  try {
    await store.createBackup(createNotes.value.trim() || undefined)
    createOpen.value = false
    createNotes.value = ""
  } finally { creating.value = false }
}

// Prune
const pruneOpen = ref(false)
const pruneKeep = ref(7)
const pruning = ref(false)
async function submitPrune() {
  pruning.value = true
  try {
    await store.pruneBackups(pruneKeep.value)
    pruneOpen.value = false
  } finally { pruning.value = false }
}

// Restore
const restoreTarget = ref<BackupRecord | null>(null)
const restoring = ref(false)
async function confirmRestore() {
  if (!restoreTarget.value) return
  restoring.value = true
  try {
    await store.restoreBackup(restoreTarget.value.id)
    restoreTarget.value = null
  } catch { /* toast handled */ }
  finally { restoring.value = false }
}

const totalSize = computed(() => store.backups.reduce((sum, b) => sum + b.sizeBytes, 0))
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader :title="t('pages.backups.title')" :description="t('pages.backups.description')">
      <template #actions>
        <Button variant="outline" @click="pruneOpen = true">
          <Scissors class="mr-2 size-4" /> {{ t('pages.backups.prune') }}
        </Button>
        <Button @click="createOpen = true">
          <Plus class="mr-2 size-4" /> {{ t('pages.backups.createBackup') }}
        </Button>
      </template>
    </PageHeader>

    <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
      <div class="rounded-xl border border-glass-border bg-glass/50 p-4">
        <p class="eyebrow mb-2">{{ t('pages.backups.summary.snapshots') }}</p>
        <p class="text-2xl font-semibold tabular">{{ store.backups.length }}</p>
      </div>
      <div class="rounded-xl border border-glass-border bg-glass/50 p-4">
        <p class="eyebrow mb-2">{{ t('pages.backups.summary.totalSize') }}</p>
        <p class="text-2xl font-semibold tabular">{{ formatBytes(totalSize) }}</p>
      </div>
      <div class="rounded-xl border border-glass-border bg-glass/50 p-4">
        <p class="eyebrow mb-2">{{ t('pages.backups.summary.latest') }}</p>
        <p class="text-sm">{{ store.backups[0]?.createdAt ? timeAgo(store.backups[0].createdAt) : '—' }}</p>
      </div>
    </div>

    <FilterToolbar
      v-model:search="search"
      :filters="[]"
      :active-filters="new Set(['ALL'])"
      :search-placeholder="t('pages.backups.searchPlaceholder')"
      :show-view-toggle="false"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="4" />

    <EmptyState
      v-else-if="filteredBackups.length === 0"
      :icon="Database"
      :title="search ? t('pages.backups.emptyMatchesTitle') : t('pages.backups.emptyTitle')"
      :description="search ? t('pages.backups.emptyMatchesHint') : t('pages.backups.emptyHint')"
    >
      <Button v-if="!search" @click="createOpen = true">
        <Plus class="mr-2 size-4" /> {{ t('pages.backups.createBackup') }}
      </Button>
    </EmptyState>

    <div v-else class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
      <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
        <div class="w-8 shrink-0 -ml-1">
          <Checkbox :model-value="isAll" :aria-label="t('pages.backups.selectAll')" @update:model-value="toggleAll" />
        </div>
        <div class="w-72 shrink-0">{{ t('pages.backups.columns.id') }}</div>
        <div class="w-40 shrink-0 text-right">{{ t('pages.backups.columns.size') }}</div>
        <div class="w-44 shrink-0 text-right">{{ t('pages.backups.columns.created') }}</div>
        <div class="w-48 shrink-0">{{ t('pages.backups.columns.verify') }}</div>
        <div class="flex-1 text-right">{{ t('pages.backups.columns.actions') }}</div>
      </div>
      <div
        v-for="b in filteredBackups"
        :key="b.id"
        class="flex h-12 select-none items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover group/row"
        :class="isSelected(b.id) ? 'bg-glass/40' : ''"
      >
        <div class="w-8 shrink-0 -ml-1" @click.stop>
          <Checkbox :model-value="isSelected(b.id)" :aria-label="t('pages.backups.selectOne', { id: b.id })" @update:model-value="toggleSelected(b.id)" />
        </div>
        <div class="w-72 shrink-0 truncate mono text-xs">{{ b.id }}</div>
        <div class="w-40 shrink-0 text-right tabular text-sm">{{ formatBytes(b.sizeBytes) }}</div>
        <div class="w-44 shrink-0 text-right tabular text-sm text-muted-foreground">{{ timeAgo(b.createdAt) }}</div>
        <div class="w-48 shrink-0">
          <StatusBadge :tone="verifyTone(b.verifyStatus)" :label="verifyLabel(b)" />
        </div>
        <div class="flex flex-1 justify-end gap-1">
          <button
            v-if="b.verifyStatus !== 'PENDING'"
            type="button"
            :title="t('pages.backups.verifyAction')"
            class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-primary/10 hover:text-primary"
            @click.stop="store.verifyBackup(b.id)"
          >
            <ShieldCheck class="size-3.5" />
          </button>
          <button
            type="button"
            :title="t('pages.backups.restoreAction')"
            class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-warning/10 hover:text-warning"
            @click.stop="restoreTarget = b"
          >
            <RotateCcw class="size-3.5" />
          </button>
          <button
            type="button"
            :aria-label="t('pages.backups.deleteAction')"
            class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
            @click.stop="store.deleteBackup(b.id)"
          >
            <Trash2 class="size-3.5" />
          </button>
        </div>
      </div>
    </div>

    <BulkActionBar :count="selectedCount" singular="backup" plural="backups" @clear="clearSelection">
      <Button variant="outline" size="sm" :disabled="bulkBusy" class="border-destructive/50 text-destructive hover:bg-destructive/10" @click="bulkDelete">
        <Trash2 class="mr-1.5 size-3.5" /> {{ t('pages.backups.delete') }}
      </Button>
    </BulkActionBar>

    <!-- Create -->
    <Dialog :open="createOpen" @update:open="createOpen = $event">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>{{ t('pages.backups.createBackup') }}</DialogTitle>
        <form class="flex flex-col gap-4" @submit.prevent="submitCreate">
          <div class="flex flex-col gap-1.5">
            <Label for="bk-notes">{{ t('pages.backups.notesLabel') }}</Label>
            <Textarea id="bk-notes" v-model="createNotes" rows="3" :placeholder="t('pages.backups.notesPlaceholder')" />
          </div>
          <DialogFooter class="flex-row! gap-2 pt-2">
            <Button type="button" variant="outline" @click="createOpen = false">{{ t('common.cancel') }}</Button>
            <Button type="submit" :disabled="creating">
              {{ creating ? t('pages.backups.creating') : t('pages.backups.create') }}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <!-- Prune -->
    <Dialog :open="pruneOpen" @update:open="pruneOpen = $event">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>{{ t('pages.backups.pruneTitle') }}</DialogTitle>
        <form class="flex flex-col gap-4" @submit.prevent="submitPrune">
          <Callout variant="warning">
            <CalloutTitle>{{ t('pages.backups.pruneCalloutTitle') }}</CalloutTitle>
            <p class="text-sm text-muted-foreground">{{ t('pages.backups.pruneCalloutBody') }}</p>
          </Callout>
          <div class="flex flex-col gap-1.5">
            <Label for="bk-keep">{{ t('pages.backups.keepLabel') }}</Label>
            <Input id="bk-keep" v-model.number="pruneKeep" type="number" min="1" max="100" />
          </div>
          <DialogFooter class="flex-row! gap-2 pt-2">
            <Button type="button" variant="outline" @click="pruneOpen = false">{{ t('common.cancel') }}</Button>
            <Button type="submit" :disabled="pruning" class="bg-warning text-warning-foreground hover:bg-warning/90">
              {{ pruning ? t('pages.backups.pruning') : t('pages.backups.keepN', { n: pruneKeep }) }}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <!-- Restore confirmation — destructive, named-fix copy -->
    <Dialog :open="restoreTarget !== null" @update:open="(v) => { if (!v) restoreTarget = null }">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>{{ t('pages.backups.restoreTitle') }}</DialogTitle>
        <Callout variant="error">
          <CalloutTitle>{{ t('pages.backups.restoreCalloutTitle') }}</CalloutTitle>
          <p class="text-sm text-muted-foreground">
            {{ t('pages.backups.restoreCalloutBody') }}
          </p>
          <template #next>
            {{ t('pages.backups.restoreCalloutNext') }}
          </template>
        </Callout>
        <div class="rounded-xl border border-glass-border bg-glass/40 p-3 mono text-xs">
          {{ restoreTarget?.id }}
          <span class="ml-2 text-muted-foreground">{{ restoreTarget ? `${formatBytes(restoreTarget.sizeBytes)} · ${timeAgo(restoreTarget.createdAt)}` : '' }}</span>
        </div>
        <DialogFooter class="flex-row! gap-2 pt-2">
          <Button variant="outline" @click="restoreTarget = null">{{ t('common.cancel') }}</Button>
          <Button :disabled="restoring" class="bg-destructive text-destructive-foreground hover:bg-destructive/90" @click="confirmRestore">
            {{ restoring ? t('pages.backups.starting') : t('pages.backups.restore') }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
