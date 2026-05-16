<script setup lang="ts">
import { FileCode, History, Package } from "lucide-vue-next"
import type { Template, TemplateSearchResult, TemplateVersion } from "~/types/api"
import { toast } from "vue-sonner"
import UploadFilesDialog from "~/components/templates/UploadFilesDialog.vue"
import TemplatePageHeader from "~/components/templates/TemplatePageHeader.vue"
import TemplateOverviewPanel from "~/components/templates/TemplateOverviewPanel.vue"
import TemplateVersionHistory from "~/components/templates/TemplateVersionHistory.vue"
import TemplateFilesTab from "~/components/templates/TemplateFilesTab.vue"
import type { ChangeType } from "~/composables/useTemplateEditor"

const route = useRoute()
const router = useRouter()
const templateName = route.params.name as string
const store = useTemplatesStore()
const groupsStore = useGroupsStore()

const isBaseTemplate = computed(() => templateName === "base" || templateName.startsWith("base-"))
const usedByGroups = computed(() =>
  groupsStore.groups.filter((g) => {
    if (g.templates.includes(templateName)) return true
    if (templateName === 'base') return true
    if (templateName.startsWith('base-') && `base-${g.platform.toLowerCase()}` === templateName) return true
    if (g.name === templateName) return true
    return false
  }),
)

// ── Template data ───────────────────────────
const template = ref<Template | null>(null)
const loading = ref(true)

async function fetchTemplate() {
  loading.value = true
  try {
    template.value = (await useApiClient().GET('/api/v1/templates/{name}', { params: { path: { name: templateName } } })).data as Template
  } catch {
    toast.error("Can't load template", { description: `Template "${templateName}" couldn't be reached. It may have been deleted, or the controller is unreachable.` })
    await router.push("/templates")
  } finally {
    loading.value = false
  }
}

// ── Editor state (file tree, open file, staged changes, drag/drop, …) ──
const editor = useTemplateEditor(templateName)

onMounted(async () => {
  await fetchTemplate()
  await editor.loadRoot()
  void groupsStore.fetchGroups()
})

// ── Inheritance, variables, search ──────────
const meta = useTemplateMeta(templateName, template)

// ── Export ───────────────────────────────────
function exportTemplate() {
  window.open(store.exportUrl(templateName), '_blank')
}

function onSearchSelect(result: TemplateSearchResult) {
  meta.showSearch.value = false
  editor.loadFileContent(result.path)
}

// ── Tab state ───────────────────────────────
const activeTab = ref<"overview" | "files" | "versions">("overview")

// ── Versions tab ────────────────────────────
const versions = ref<TemplateVersion[]>([])
const versionsLoading = ref(false)
const restoring = ref(false)
const versionHistoryRef = ref<InstanceType<typeof TemplateVersionHistory> | null>(null)

watch(activeTab, async (tab) => {
  if (tab !== "versions") {
    versionHistoryRef.value?.reset()
  }
  if (tab === "versions") {
    versionsLoading.value = true
    try {
      versions.value = await store.fetchVersions(templateName)
    } catch {
      toast.error("Can't load version history", { description: "Try again, or check the controller logs." })
    } finally {
      versionsLoading.value = false
    }
  }
})

// ── Unsaved-changes warning dialog ──────────
const unsavedWarningOpen = ref(false)
const unsavedWarningResolve = ref<((proceed: boolean) => void) | null>(null)

function showUnsavedWarning(): Promise<boolean> {
  return new Promise((resolve) => {
    unsavedWarningResolve.value = resolve
    unsavedWarningOpen.value = true
  })
}

function onUnsavedWarningConfirm() {
  unsavedWarningOpen.value = false
  unsavedWarningResolve.value?.(true)
  unsavedWarningResolve.value = null
}

function onUnsavedWarningCancel() {
  unsavedWarningOpen.value = false
  unsavedWarningResolve.value?.(false)
  unsavedWarningResolve.value = null
}

async function rollbackToVersion(hash: string) {
  if (editor.hasChanges.value) {
    const proceed = await showUnsavedWarning()
    if (!proceed) return
  }
  restoring.value = true
  try {
    await store.rollbackToVersion(templateName, hash)
    editor.stagedChanges.clear()
    editor.cancelStageTimer()
    await fetchTemplate()
    await editor.loadRoot()
    if (editor.openFile.value) await editor.loadFileContent(editor.openFile.value.path)
    versions.value = await store.fetchVersions(templateName)
    toast.success("Version restored")
  } catch {
    toast.error("Restore failed", { description: "Couldn't roll back to that version. Try again, or check the controller logs." })
  } finally {
    restoring.value = false
  }
}

function onVersionDeleted(hash: string) {
  versions.value = versions.value.filter(v => v.hash !== hash)
}

// ── Dialog state ────────────────────────────
const showUploadDialog = ref(false)
const showDeleteConfirm = ref(false)
const deleteLoading = ref(false)

async function onFilesUploaded() {
  await fetchTemplate()
  await editor.loadRoot()
}

async function deleteTemplate() {
  deleteLoading.value = true
  try {
    await store.deleteTemplate(templateName)
    await router.push("/templates")
  }
  finally {
    deleteLoading.value = false
  }
}

// ── Syntax validation ───────────────────────
const validationContent = computed(() => editor.openFile.value?.content ?? '')
const validationLanguage = computed(() => editor.editorLanguage.value)
const { errors: validationErrors } = useFileValidation(validationContent, validationLanguage)

// Auto-stage when openFile content changes
watch(() => editor.openFile.value?.content, () => {
  if (!editor.openFile.value) return
  editor.scheduleAutoStage()
})

// Flush pending stage when switching files
watch(() => editor.openFile.value?.path, (_new, old) => {
  if (old) editor.flushStage()
})

// Flush on unmount
onBeforeUnmount(() => editor.flushStage())

// Warn before navigating away with unsaved staged changes
const pendingLeaveResolve = ref<((proceed: boolean) => void) | null>(null)

onBeforeRouteLeave(() => {
  editor.flushStage()
  if (editor.hasChanges.value) {
    return new Promise<boolean>((resolve) => {
      pendingLeaveResolve.value = resolve
      unsavedWarningOpen.value = true
      unsavedWarningResolve.value = (proceed: boolean) => {
        pendingLeaveResolve.value = null
        resolve(proceed)
      }
    })
  }
})

// ── Save all staged changes ─────────────────
async function saveAllChanges() {
  const ok = await editor.saveAllChanges(() => validationErrors.value.length)
  if (ok) await fetchTemplate()
}

// ── Ctrl+S keyboard shortcut ────────────────
function onKeyboardSave(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    editor.flushStage()
    if (editor.hasChanges.value) saveAllChanges()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeyboardSave)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyboardSave)
})

// ── Discard all ─────────────────────────────
const confirmDiscardAll = ref(false)

function discardAllChanges() {
  editor.discardAllChanges()
  confirmDiscardAll.value = false
}

// ── Helpers ─────────────────────────────────
const changeTypeStyles: Record<ChangeType, { label: string; class: string; dot: string }> = {
  modified: { label: "M", class: "text-warning", dot: "bg-warning" },
  created: { label: "A", class: "text-success", dot: "bg-success" },
  deleted: { label: "D", class: "text-destructive line-through", dot: "bg-destructive" },
  renamed: { label: "R", class: "text-primary", dot: "bg-primary" },
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <TemplatePageHeader
      :template-name="templateName"
      :template="template"
      :has-changes="editor.hasChanges.value"
      :staged-count="editor.stagedChanges.size"
      :saving="editor.saving.value"
      @back="router.push('/templates')"
      @export="exportTemplate"
      @discard="confirmDiscardAll = true"
      @save="saveAllChanges"
    />

    <!-- Loading skeleton -->
    <div v-if="loading" class="grid grid-cols-1 lg:grid-cols-4 gap-4">
      <div v-for="i in 4" :key="i" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-5 animate-pulse">
        <div class="h-4 bg-glass rounded w-20 mb-2" />
        <div class="h-6 bg-glass rounded w-16" />
      </div>
    </div>

    <template v-else-if="template">
      <!-- Tabs -->
      <nav class="flex gap-1 border-b border-glass-border -mb-px">
        <button
          v-for="tab in ([
            { key: 'overview', label: 'Overview', icon: Package },
            { key: 'files', label: 'Files', icon: FileCode },
            { key: 'versions', label: 'Versions', icon: History },
          ] as const)"
          :key="tab.key"
          :class="[
            'inline-flex items-center gap-2 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors -mb-px',
            activeTab === tab.key
              ? 'border-primary text-foreground'
              : 'border-transparent text-muted-foreground hover:text-foreground hover:border-muted-foreground/30',
          ]"
          @click="activeTab = tab.key"
        >
          <component :is="tab.icon" class="size-4" />
          {{ tab.label }}
        </button>
      </nav>

      <!-- Overview tab -->
      <TemplateOverviewPanel
        v-if="activeTab === 'overview'"
        v-model:template-variables="meta.templateVariables.value"
        :template="template"
        :template-name="templateName"
        :is-base-template="isBaseTemplate"
        :inheritance-chain="meta.inheritanceChain.value"
        :variables-saving="meta.variablesSaving.value"
        :scanning-variables="meta.scanningVariables.value"
        :used-by-groups="usedByGroups"
        :groups-loading="groupsStore.loading"
        :delete-loading="deleteLoading"
        @update:template="(t) => template = t"
        @save-variables="meta.saveTemplateVariables"
        @scan-variables="meta.scanForVariables"
        @inheritance-click="(name) => router.push(`/templates/${name}`)"
        @request-delete="showDeleteConfirm = true"
      />

      <!-- Versions tab -->
      <TemplateVersionHistory
        v-if="activeTab === 'versions'"
        ref="versionHistoryRef"
        :template-name="templateName"
        :template="template"
        :versions="versions"
        :versions-loading="versionsLoading"
        :restoring="restoring"
        @rollback="rollbackToVersion"
        @deleted="onVersionDeleted"
      />

      <!-- Files tab -->
      <TemplateFilesTab
        v-if="activeTab === 'files'"
        :editor="editor"
        :meta="meta"
        :validation-errors="validationErrors"
        :change-type-styles="changeTypeStyles"
        @upload="showUploadDialog = true"
        @search-select="onSearchSelect"
        @save="saveAllChanges"
      />
    </template>

    <!-- Dialogs -->
    <UploadFilesDialog
      :open="showUploadDialog"
      :template-name="templateName"
      @update:open="showUploadDialog = $event"
      @uploaded="onFilesUploaded"
    />

    <ConfirmDialog
      :open="!!editor.confirmDeletePath.value"
      title="Stage Deletion"
      :description="`Mark '${editor.confirmDeletePath.value}' for deletion? This will be applied when you save.`"
      confirm-label="Stage Delete"
      @update:open="editor.cancelDelete"
      @confirm="editor.confirmDelete"
    />

    <ConfirmDialog
      :open="confirmDiscardAll"
      title="Discard All Changes"
      :description="`Discard all ${editor.stagedChanges.size} staged change${editor.stagedChanges.size > 1 ? 's' : ''}? This cannot be undone.`"
      confirm-label="Discard All"
      @update:open="confirmDiscardAll = $event"
      @confirm="discardAllChanges"
    />

    <ConfirmDialog
      :open="showDeleteConfirm"
      title="Delete Template"
      :description="`Permanently delete '${templateName}'? All files and version history will be lost.`"
      confirm-label="Delete Template"
      :loading="deleteLoading"
      @update:open="showDeleteConfirm = $event"
      @confirm="deleteTemplate"
    />

    <ConfirmDialog
      :open="unsavedWarningOpen"
      title="Unsaved Changes"
      description="You have unsaved staged changes that will be lost. Do you want to continue?"
      confirm-label="Continue"
      @update:open="onUnsavedWarningCancel"
      @confirm="onUnsavedWarningConfirm"
    />
  </div>
</template>
