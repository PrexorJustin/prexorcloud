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
  if (!b.verifyStatus) return "Not verified"
  if (b.verifyStatus === "OK") return `Verified ${b.verifiedAt ? timeAgo(b.verifiedAt) : ''}`.trim()
  if (b.verifyStatus === "FAILED") return "Verify failed"
  return "Verifying…"
}

const bulkBusy = ref(false)
async function bulkDelete() {
  bulkBusy.value = true
  try {
    const ids = Array.from(selected.value)
    await Promise.allSettled(ids.map(id => store.deleteBackup(id)))
    toast.success(`${ids.length} ${ids.length === 1 ? 'backup' : 'backups'} deleted`)
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
    <PageHeader title="Backups" description="Cluster-state snapshots: groups, templates, deployment history.">
      <template #actions>
        <Button variant="outline" @click="pruneOpen = true">
          <Scissors class="mr-2 size-4" /> Prune
        </Button>
        <Button @click="createOpen = true">
          <Plus class="mr-2 size-4" /> Create backup
        </Button>
      </template>
    </PageHeader>

    <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
      <div class="rounded-xl border border-glass-border bg-glass/50 p-4">
        <p class="eyebrow mb-2">Snapshots</p>
        <p class="text-2xl font-semibold tabular">{{ store.backups.length }}</p>
      </div>
      <div class="rounded-xl border border-glass-border bg-glass/50 p-4">
        <p class="eyebrow mb-2">Total size</p>
        <p class="text-2xl font-semibold tabular">{{ formatBytes(totalSize) }}</p>
      </div>
      <div class="rounded-xl border border-glass-border bg-glass/50 p-4">
        <p class="eyebrow mb-2">Latest</p>
        <p class="text-sm">{{ store.backups[0]?.createdAt ? timeAgo(store.backups[0].createdAt) : '—' }}</p>
      </div>
    </div>

    <FilterToolbar
      v-model:search="search"
      :filters="[]"
      :active-filters="new Set(['ALL'])"
      search-placeholder="Search by id or notes…"
      :show-view-toggle="false"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="4" />

    <EmptyState
      v-else-if="filteredBackups.length === 0"
      :icon="Database"
      :title="search ? 'No matches' : 'No backups yet'"
      :description="search ? 'Try clearing the filter.' : 'Run Create backup to take the first snapshot.'"
    >
      <Button v-if="!search" @click="createOpen = true">
        <Plus class="mr-2 size-4" /> Create backup
      </Button>
    </EmptyState>

    <div v-else class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
      <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
        <div class="w-8 shrink-0 -ml-1">
          <Checkbox :model-value="isAll" aria-label="Select all backups" @update:model-value="toggleAll" />
        </div>
        <div class="w-72 shrink-0">ID</div>
        <div class="w-40 shrink-0 text-right">Size</div>
        <div class="w-44 shrink-0 text-right">Created</div>
        <div class="w-48 shrink-0">Verify</div>
        <div class="flex-1 text-right">Actions</div>
      </div>
      <div
        v-for="b in filteredBackups"
        :key="b.id"
        class="flex h-12 select-none items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover group/row"
        :class="isSelected(b.id) ? 'bg-glass/40' : ''"
      >
        <div class="w-8 shrink-0 -ml-1" @click.stop>
          <Checkbox :model-value="isSelected(b.id)" :aria-label="`Select ${b.id}`" @update:model-value="toggleSelected(b.id)" />
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
            title="Verify"
            class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-primary/10 hover:text-primary"
            @click.stop="store.verifyBackup(b.id)"
          >
            <ShieldCheck class="size-3.5" />
          </button>
          <button
            type="button"
            title="Restore from this backup"
            class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-warning/10 hover:text-warning"
            @click.stop="restoreTarget = b"
          >
            <RotateCcw class="size-3.5" />
          </button>
          <button
            type="button"
            aria-label="Delete"
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
        <Trash2 class="mr-1.5 size-3.5" /> Delete
      </Button>
    </BulkActionBar>

    <!-- Create -->
    <Dialog :open="createOpen" @update:open="createOpen = $event">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>Create backup</DialogTitle>
        <form class="flex flex-col gap-4" @submit.prevent="submitCreate">
          <div class="flex flex-col gap-1.5">
            <Label for="bk-notes">Notes (optional)</Label>
            <Textarea id="bk-notes" v-model="createNotes" rows="3" placeholder="Pre-migration snapshot" />
          </div>
          <DialogFooter class="flex-row! gap-2 pt-2">
            <Button type="button" variant="outline" @click="createOpen = false">Cancel</Button>
            <Button type="submit" :disabled="creating">
              {{ creating ? 'Creating…' : 'Create' }}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <!-- Prune -->
    <Dialog :open="pruneOpen" @update:open="pruneOpen = $event">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>Prune backups</DialogTitle>
        <form class="flex flex-col gap-4" @submit.prevent="submitPrune">
          <Callout variant="warning">
            <CalloutTitle>This deletes older backups</CalloutTitle>
            <p class="text-sm text-muted-foreground">Only the most recent N snapshots are kept. Verified ones are not exempt.</p>
          </Callout>
          <div class="flex flex-col gap-1.5">
            <Label for="bk-keep">Keep most recent</Label>
            <Input id="bk-keep" v-model.number="pruneKeep" type="number" min="1" max="100" />
          </div>
          <DialogFooter class="flex-row! gap-2 pt-2">
            <Button type="button" variant="outline" @click="pruneOpen = false">Cancel</Button>
            <Button type="submit" :disabled="pruning" class="bg-warning text-warning-foreground hover:bg-warning/90">
              {{ pruning ? 'Pruning…' : `Keep ${pruneKeep}` }}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <!-- Restore confirmation — destructive, named-fix copy -->
    <Dialog :open="restoreTarget !== null" @update:open="(v) => { if (!v) restoreTarget = null }">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>Restore from backup?</DialogTitle>
        <Callout variant="error">
          <CalloutTitle>This replaces cluster state</CalloutTitle>
          <p class="text-sm text-muted-foreground">
            Scheduler pauses while the controller rolls back groups, templates, and deployment history to the snapshot.
            Running instances stay up until they're rescheduled against the restored state.
          </p>
          <template #next>
            Take a fresh backup first if you might want to come back. Restores can't be undone.
          </template>
        </Callout>
        <div class="rounded-xl border border-glass-border bg-glass/40 p-3 mono text-xs">
          {{ restoreTarget?.id }}
          <span class="ml-2 text-muted-foreground">{{ restoreTarget ? `${formatBytes(restoreTarget.sizeBytes)} · ${timeAgo(restoreTarget.createdAt)}` : '' }}</span>
        </div>
        <DialogFooter class="flex-row! gap-2 pt-2">
          <Button variant="outline" @click="restoreTarget = null">Cancel</Button>
          <Button :disabled="restoring" class="bg-destructive text-destructive-foreground hover:bg-destructive/90" @click="confirmRestore">
            {{ restoring ? 'Starting…' : 'Restore' }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
