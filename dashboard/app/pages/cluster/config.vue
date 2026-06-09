<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { History, RotateCcw } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { StatusBadge } from "~/components/ui/status-badge"
import { Dialog, DialogContent, DialogFooter, DialogTitle } from "~/components/ui/dialog"
import { Callout } from "~/components/ui/callout"
import type { ClusterConfigVersionMeta } from "~/stores/cluster"

// /cluster/config is the operator-facing history of the Raft-replicated cluster
// config (Phase 6 / A.8). Each version stores the patch it applied on top of its
// parent; selecting a row diffs that patch against the parent's via the shared
// DiffViewer. Rollback re-points the active version through a fresh Raft commit.

const { t } = useI18n()
const store = useClusterStore()
const auth = useAuthStore()

onMounted(() => {
  store.fetchStatus()
  store.fetchConfigVersions()
})

const canWrite = computed(() => auth.can("cluster.config.write"))

// --- Selected version → diff against its parent ---
const selectedVersion = ref<number | null>(null)
const loadingDiff = ref(false)
const diffOriginal = ref("")
const diffModified = ref("")
const diffParentVersion = ref<number | null>(null)

function stringify(patch: Record<string, unknown> | undefined): string {
  if (!patch || Object.keys(patch).length === 0) return ""
  return JSON.stringify(patch, null, 2)
}

async function selectVersion(v: ClusterConfigVersionMeta) {
  selectedVersion.value = v.version
  loadingDiff.value = true
  diffParentVersion.value = v.parentVersion > 0 ? v.parentVersion : null
  try {
    const detail = await store.fetchConfigVersion(v.version)
    diffModified.value = stringify(detail?.patch)
    if (v.parentVersion > 0) {
      const parent = await store.fetchConfigVersion(v.parentVersion)
      diffOriginal.value = stringify(parent?.patch)
    } else {
      // Root/seed version has no parent — everything in its patch is "added".
      diffOriginal.value = ""
    }
  } finally {
    loadingDiff.value = false
  }
}

const originalLabel = computed(() =>
  diffParentVersion.value != null
    ? t("pages.clusterConfig.parentLabel", { version: diffParentVersion.value })
    : t("pages.clusterConfig.seedLabel"),
)
const modifiedLabel = computed(() =>
  selectedVersion.value != null ? t("pages.clusterConfig.versionLabel", { version: selectedVersion.value }) : "",
)

// --- Rollback dialog ---
const rollbackOpen = ref(false)
const rollbackTarget = ref<number | null>(null)
const rollbackReason = ref("")
const rollingBack = ref(false)

function openRollback(version: number) {
  rollbackTarget.value = version
  rollbackReason.value = ""
  rollbackOpen.value = true
}

async function confirmRollback() {
  if (rollbackTarget.value == null) return
  rollingBack.value = true
  try {
    await store.rollbackConfig(rollbackTarget.value, rollbackReason.value.trim() || undefined)
    rollbackOpen.value = false
  } catch {
    /* toast surfaces error */
  } finally {
    rollingBack.value = false
  }
}

function fmtDate(s: string | undefined): string {
  return s ? new Date(s).toLocaleString() : "—"
}
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader :title="t('pages.clusterConfig.title')" :description="t('pages.clusterConfig.description')" />

    <!-- Summary -->
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
      <div class="rounded-2xl border border-glass-border bg-glass/60 p-4">
        <div class="eyebrow text-xs">{{ t('pages.clusterConfig.summary.activeVersion') }}</div>
        <div class="text-2xl mt-1">v{{ store.configActiveVersion ?? store.status?.activeConfigVersion ?? "—" }}</div>
      </div>
      <div class="rounded-2xl border border-glass-border bg-glass/60 p-4">
        <div class="eyebrow text-xs">{{ t('pages.clusterConfig.summary.totalVersions') }}</div>
        <div class="text-2xl mt-1">{{ store.configVersions.length }}</div>
      </div>
    </div>

    <!-- Version history -->
    <section class="flex flex-col gap-2">
      <h2 class="text-lg font-medium">
        {{ t('pages.clusterConfig.historyHeading', { count: store.configVersions.length }) }}
      </h2>

      <LoadingSkeleton v-if="store.loadingConfigVersions" mode="table" :count="4" />

      <EmptyState
        v-else-if="store.configVersions.length === 0"
        :icon="History"
        :title="t('pages.clusterConfig.emptyTitle')"
        :description="t('pages.clusterConfig.emptyBody')"
      />

      <div v-else class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
        <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
          <div class="w-24 shrink-0">{{ t('pages.clusterConfig.columns.version') }}</div>
          <div class="w-24 shrink-0">{{ t('pages.clusterConfig.columns.parent') }}</div>
          <div class="w-44 shrink-0">{{ t('pages.clusterConfig.columns.mutator') }}</div>
          <div class="w-44 shrink-0">{{ t('pages.clusterConfig.columns.changedAt') }}</div>
          <div class="flex-1">{{ t('pages.clusterConfig.columns.reason') }}</div>
          <div class="w-24 shrink-0" />
        </div>
        <div
          v-for="v in store.configVersions"
          :key="v.version"
          role="button"
          tabindex="0"
          :class="[
            'flex h-12 cursor-pointer select-none items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover group/row',
            selectedVersion === v.version ? 'bg-glass-hover' : '',
          ]"
          @click="selectVersion(v)"
          @keydown.enter="selectVersion(v)"
          @keydown.space.prevent="selectVersion(v)"
        >
          <div class="flex w-24 shrink-0 items-center gap-2">
            <span class="mono text-sm">v{{ v.version }}</span>
            <StatusBadge v-if="v.isActive" tone="success" :label="t('pages.clusterConfig.active')" />
          </div>
          <div class="w-24 shrink-0 mono text-xs text-muted-foreground">
            {{ v.parentVersion > 0 ? `v${v.parentVersion}` : t('pages.clusterConfig.rootSeed') }}
          </div>
          <div class="w-44 shrink-0 truncate text-sm text-muted-foreground">{{ v.mutator || "—" }}</div>
          <div class="w-44 shrink-0 truncate text-sm text-muted-foreground tabular">{{ fmtDate(v.mutatedAt) }}</div>
          <div class="flex-1 truncate text-sm text-muted-foreground">{{ v.reason || t('pages.clusterConfig.noReason') }}</div>
          <div class="flex w-24 shrink-0 justify-end">
            <button
              v-if="canWrite && !v.isActive"
              type="button"
              :aria-label="t('pages.clusterConfig.rollbackToThisAria')"
              class="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground/0 transition-all group-hover/row:text-muted-foreground hover:bg-primary/10 hover:text-primary"
              @click.stop="openRollback(v.version)"
            >
              <RotateCcw class="size-3.5" /> {{ t('pages.clusterConfig.rollback') }}
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- Diff -->
    <section class="flex flex-col gap-2">
      <h2 class="text-lg font-medium">{{ t('pages.clusterConfig.diffHeading') }}</h2>
      <p class="text-sm text-muted-foreground">{{ t('pages.clusterConfig.diffHint') }}</p>

      <div
        v-if="selectedVersion == null"
        class="rounded-2xl border border-dashed border-glass-border bg-glass/30 px-4 py-10 text-center text-sm text-muted-foreground"
      >
        {{ t('pages.clusterConfig.selectPrompt') }}
      </div>

      <div
        v-else-if="loadingDiff"
        class="h-40 animate-pulse rounded-2xl border border-glass-border bg-glass/40"
      />

      <template v-else>
        <p class="text-xs text-muted-foreground">{{ t('pages.clusterConfig.maskedNote') }}</p>
        <DiffViewer
          :original="diffOriginal"
          :modified="diffModified"
          language="json"
          :original-label="originalLabel"
          :modified-label="modifiedLabel"
        />
      </template>
    </section>

    <!-- Rollback dialog -->
    <Dialog v-model:open="rollbackOpen">
      <DialogContent class="sm:max-w-md">
        <DialogTitle>{{ t('pages.clusterConfig.rollbackDialog.title') }}</DialogTitle>
        <Callout variant="warning">
          <p class="text-sm text-muted-foreground">
            {{ t('pages.clusterConfig.rollbackDialog.body', { version: rollbackTarget }) }}
          </p>
        </Callout>
        <div class="flex flex-col gap-1.5">
          <Label for="rb-reason">{{ t('pages.clusterConfig.rollbackDialog.reasonLabel') }}</Label>
          <Input
            id="rb-reason"
            v-model="rollbackReason"
            :placeholder="t('pages.clusterConfig.rollbackDialog.reasonPlaceholder')"
          />
        </div>
        <DialogFooter class="flex-row! gap-2 pt-2">
          <Button variant="outline" @click="rollbackOpen = false">{{ t('common.cancel') }}</Button>
          <Button variant="destructive" :disabled="rollingBack" @click="confirmRollback">
            {{ t('pages.clusterConfig.rollbackDialog.confirm') }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
