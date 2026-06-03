<script setup lang="ts">
import { Eye, FileCode, Hash, History, Loader2, RotateCcw, Trash2 } from "lucide-vue-next"
import type { Template, TemplateFile, TemplateVersion } from "~/types/api"
import FileTreeNode, { type TreeNode } from "~/components/templates/FileTreeNode.vue"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import { toast } from "vue-sonner"
import { formatBytes, timeAgo } from "~/lib/utils"

const props = defineProps<{
  templateName: string
  template: Template
  versions: TemplateVersion[]
  versionsLoading: boolean
  restoring: boolean
}>()

const emit = defineEmits<{
  (e: 'rollback', hash: string): void
  (e: 'deleted', hash: string): void
}>()

const store = useTemplatesStore()
const { t } = useI18n()

const confirmRollbackHash = ref<string | null>(null)
const confirmDeleteHash = ref<string | null>(null)
const deletingVersion = ref(false)

async function deleteVersion(hash: string) {
  deletingVersion.value = true
  try {
    await store.deleteVersion(props.templateName, hash)
    emit('deleted', hash)
    confirmDeleteHash.value = null
  } catch {
    toast.error(t("toast.templates.deleteVersionFailed"))
  } finally {
    deletingVersion.value = false
  }
}

function requestRollback(hash: string) {
  emit('rollback', hash)
}

watch(() => props.restoring, (v, prev) => {
  if (prev && !v) confirmRollbackHash.value = null
})

defineExpose({
  reset() {
    previewVersionHash.value = null
    previewTree.value = []
    previewFile.value = null
    compareVersionHash.value = null
    compareFiles.value = []
    compareSelectedFile.value = null
  },
})

// ── Snapshot preview ────────────────────────
const previewVersionHash = ref<string | null>(null)
const previewTree = ref<TreeNode[]>([])
const previewTreeLoading = ref(false)
const previewFile = ref<{ path: string; name: string; content: string; loading: boolean } | null>(null)

async function loadSnapshotDir(hash: string, path: string): Promise<TreeNode[]> {
  const files = await store.fetchSnapshotFiles(props.templateName, hash, path)
  return files
    .sort((a, b) => {
      if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1
      return a.name.localeCompare(b.name)
    })
    .map(f => ({
      name: f.name,
      path: path ? `${path}/${f.name}` : f.name,
      isDirectory: f.isDirectory,
      size: f.size,
      children: f.isDirectory ? [] : undefined,
      expanded: false,
      loaded: false,
    }))
}

async function togglePreviewVersion(hash: string) {
  if (previewVersionHash.value === hash) {
    previewVersionHash.value = null
    previewTree.value = []
    previewFile.value = null
    return
  }
  previewVersionHash.value = hash
  previewFile.value = null
  previewTreeLoading.value = true
  try {
    previewTree.value = await loadSnapshotDir(hash, "")
  }
  catch {
    toast.error(t("toast.templates.snapshotLoadFailed"))
    previewVersionHash.value = null
  }
  finally { previewTreeLoading.value = false }
}

async function onPreviewNodeClick(node: TreeNode) {
  if (!previewVersionHash.value) return
  if (node.isDirectory) {
    if (!node.loaded) {
      node.children = await loadSnapshotDir(previewVersionHash.value, node.path)
      node.loaded = true
    }
    node.expanded = !node.expanded
    return
  }
  // File click
  previewFile.value = { path: node.path, name: node.name, content: "", loading: true }
  try {
    const content = await store.fetchSnapshotFileContent(props.templateName, previewVersionHash.value, node.path)
    if (previewFile.value?.path === node.path) {
      previewFile.value.content = content
      previewFile.value.loading = false
    }
  }
  catch {
    if (previewFile.value?.path === node.path) {
      previewFile.value.content = "// Failed to load file content"
      previewFile.value.loading = false
    }
  }
}

function formatDate(iso: string): string {
  const date = iso.endsWith('Z') || /[+-]\d{2}:\d{2}$/.test(iso) ? new Date(iso) : new Date(iso + 'Z')
  return date.toLocaleString(undefined, {
    month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
  })
}

// ── Diff comparison ──────────────────────────
const compareVersionHash = ref<string | null>(null)
const compareFiles = ref<{ path: string; status: 'added' | 'removed' | 'modified' }[]>([])
const compareLoading = ref(false)
const compareSelectedFile = ref<string | null>(null)
const compareOriginal = ref('')
const compareModified = ref('')
const compareFileLoading = ref(false)

async function toggleCompare(hash: string) {
  if (compareVersionHash.value === hash) {
    compareVersionHash.value = null
    compareFiles.value = []
    compareSelectedFile.value = null
    return
  }

  compareVersionHash.value = hash
  compareSelectedFile.value = null
  compareOriginal.value = ''
  compareModified.value = ''
  compareLoading.value = true

  try {
    // Recursively collect all files from both snapshot and current template
    async function collectAllFiles(
      fetcher: (path: string) => Promise<TemplateFile[]>,
      path: string,
    ): Promise<string[]> {
      const entries = await fetcher(path)
      const result: string[] = []
      for (const entry of entries) {
        const fullPath = path ? `${path}/${entry.name}` : entry.name
        if (entry.isDirectory) {
          const children = await collectAllFiles(fetcher, fullPath)
          result.push(...children)
        } else {
          result.push(fullPath)
        }
      }
      return result
    }

    const [snapshotPaths, currentPaths] = await Promise.all([
      collectAllFiles(
        (p) => store.fetchSnapshotFiles(props.templateName, hash, p),
        '',
      ),
      collectAllFiles(
        async (p) => {
          const { data } = await useApiClient().GET('/api/v1/templates/{name}/files', {
            params: { path: { name: props.templateName }, query: p ? { path: p } : undefined },
          }).catch(() => ({ data: undefined }))
          return (data ?? []) as TemplateFile[]
        },
        '',
      ),
    ])

    const snapshotSet = new Set(snapshotPaths)
    const currentSet = new Set(currentPaths)

    const files: typeof compareFiles.value = []
    for (const filePath of snapshotSet) {
      if (!currentSet.has(filePath)) files.push({ path: filePath, status: 'removed' })
      else files.push({ path: filePath, status: 'modified' })
    }
    for (const filePath of currentSet) {
      if (!snapshotSet.has(filePath)) files.push({ path: filePath, status: 'added' })
    }
    compareFiles.value = files.sort((a, b) => a.path.localeCompare(b.path))
  } catch {
    toast.error(t("toast.templates.comparisonLoadFailed"))
    compareVersionHash.value = null
  } finally {
    compareLoading.value = false
  }
}

async function selectCompareFile(path: string) {
  if (!compareVersionHash.value) return
  compareSelectedFile.value = path
  compareFileLoading.value = true
  compareOriginal.value = ''
  compareModified.value = ''

  try {
    const [original, modified] = await Promise.allSettled([
      store.fetchSnapshotFileContent(props.templateName, compareVersionHash.value, path),
      store.fetchFileContent(props.templateName, path),
    ])
    compareOriginal.value = original.status === 'fulfilled' ? original.value : ''
    compareModified.value = modified.status === 'fulfilled' ? modified.value : ''
  } catch { /* handled by allSettled */ }
  finally { compareFileLoading.value = false }
}
</script>

<template>
  <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border overflow-x-auto">
    <!-- Loading -->
    <div v-if="versionsLoading" class="flex items-center justify-center py-16">
      <Loader2 class="size-5 text-muted-foreground animate-spin" />
    </div>

    <!-- Empty -->
    <div v-else-if="versions.length === 0" class="flex flex-col items-center justify-center text-center py-16">
      <History class="size-10 text-muted-foreground/30 mb-3" />
      <p class="text-sm text-muted-foreground">{{ t('components.versionHistory.empty') }}</p>
    </div>

    <!-- Version list -->
    <template v-else>
      <!-- Table header -->
      <div class="flex items-center h-10 px-5 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider min-w-[720px]">
        <div class="w-44 shrink-0">{{ t('components.versionHistory.colHash') }}</div>
        <div class="w-24 shrink-0 text-center">{{ t('components.versionHistory.colStatus') }}</div>
        <div class="w-28 shrink-0 text-right">{{ t('components.versionHistory.colSize') }}</div>
        <div class="flex-1 text-right">{{ t('components.versionHistory.colCreated') }}</div>
        <div class="w-48 shrink-0 text-right">{{ t('components.versionHistory.colActions') }}</div>
      </div>

      <div class="max-h-[calc(100vh-340px)] overflow-auto styled-scrollbar">
        <div v-for="(v, i) in versions" :key="v.hash">
          <div
            :class="[
              'flex items-center h-14 px-5 border-b border-glass-border/50 transition-colors min-w-[720px]',
              v.hash === template.hash ? 'bg-primary/5' : 'hover:bg-glass-hover',
              previewVersionHash === v.hash || compareVersionHash === v.hash ? 'border-b-0' : '',
            ]"
          >
            <div class="w-44 shrink-0 flex items-center gap-2">
              <Hash class="size-3 text-muted-foreground shrink-0" />
              <span class="text-sm font-mono text-muted-foreground truncate" :title="v.hash">{{ v.hash.slice(0, 12) }}</span>
            </div>
            <div class="w-24 shrink-0 text-center">
              <Badge v-if="v.hash === template.hash" variant="outline" class="text-[10px] text-primary border-primary/30">
                {{ t('components.versionHistory.current') }}
              </Badge>
              <Badge v-else-if="i === 0" variant="outline" class="text-[10px] text-success border-success/30">
                {{ t('components.versionHistory.latest') }}
              </Badge>
            </div>
            <div class="w-28 shrink-0 text-right text-sm text-foreground tabular-nums">{{ formatBytes(v.sizeBytes) }}</div>
            <div class="flex-1 text-right">
              <span class="text-sm text-muted-foreground">{{ formatDate(v.createdAt) }}</span>
              <span class="text-xs text-muted-foreground/50 ml-2">{{ timeAgo(v.createdAt) }}</span>
            </div>
            <div class="w-48 shrink-0 flex justify-end gap-1">
              <Button
                variant="ghost"
                size="sm"
                :class="['h-7 px-2 text-xs', previewVersionHash === v.hash ? 'text-primary' : '']"
                @click="togglePreviewVersion(v.hash)"
              >
                <Eye class="size-3 mr-1" />
                {{ previewVersionHash === v.hash ? t('components.versionHistory.close') : t('components.versionHistory.view') }}
              </Button>
              <Button
                variant="ghost"
                size="sm"
                :class="['h-7 px-2 text-xs', compareVersionHash === v.hash ? 'text-primary' : '']"
                @click="toggleCompare(v.hash)"
              >
                <FileCode class="size-3 mr-1" />
                {{ compareVersionHash === v.hash ? t('components.versionHistory.closeDiff') : t('components.versionHistory.compare') }}
              </Button>
              <template v-if="v.hash !== template.hash">
                <!-- Restore / Delete (normal state) -->
                <template v-if="confirmRollbackHash !== v.hash && confirmDeleteHash !== v.hash">
                  <Button
                    variant="outline"
                    size="sm"
                    class="h-7 px-2 text-xs border-glass-border"
                    @click="confirmRollbackHash = v.hash; confirmDeleteHash = null"
                  >
                    <RotateCcw class="size-3 mr-1" /> {{ t('components.versionHistory.restore') }}
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    class="h-7 px-2 text-xs text-muted-foreground hover:text-destructive"
                    @click="confirmDeleteHash = v.hash; confirmRollbackHash = null"
                  >
                    <Trash2 class="size-3" />
                  </Button>
                </template>
                <!-- Confirm restore -->
                <div v-else-if="confirmRollbackHash === v.hash" class="flex items-center gap-1">
                  <Button
                    size="sm"
                    class="h-7 px-2 text-xs bg-warning hover:bg-warning/90 text-warning-foreground"
                    :disabled="restoring"
                    @click="requestRollback(v.hash)"
                  >
                    <Loader2 v-if="restoring" class="size-3 mr-1 animate-spin" />
                    {{ t('components.versionHistory.confirm') }}
                  </Button>
                  <Button variant="ghost" size="sm" class="h-7 px-2 text-xs" :disabled="restoring" @click="confirmRollbackHash = null">
                    {{ t('components.versionHistory.cancel') }}
                  </Button>
                </div>
                <!-- Confirm delete -->
                <div v-else-if="confirmDeleteHash === v.hash" class="flex items-center gap-1">
                  <Button
                    size="sm"
                    class="h-7 px-2 text-xs bg-destructive hover:bg-destructive/90 text-destructive-foreground"
                    :disabled="deletingVersion"
                    @click="deleteVersion(v.hash)"
                  >
                    <Loader2 v-if="deletingVersion" class="size-3 mr-1 animate-spin" />
                    {{ t('components.versionHistory.delete') }}
                  </Button>
                  <Button variant="ghost" size="sm" class="h-7 px-2 text-xs" :disabled="deletingVersion" @click="confirmDeleteHash = null">
                    {{ t('components.versionHistory.cancel') }}
                  </Button>
                </div>
              </template>
            </div>
          </div>

          <!-- Snapshot preview panel -->
          <div
            v-if="previewVersionHash === v.hash"
            class="border-b border-glass-border/50 bg-muted/20"
          >
            <div class="flex gap-4 p-4" style="height: 400px">
              <!-- Read-only file tree -->
              <div class="w-64 shrink-0 bg-glass/40 rounded-xl border border-glass-border flex flex-col overflow-hidden">
                <div class="px-3 py-2 border-b border-glass-border">
                  <span class="text-xs font-medium text-muted-foreground uppercase tracking-wider">{{ t('components.versionHistory.snapshotFiles') }}</span>
                </div>
                <div v-if="previewTreeLoading" class="flex-1 flex items-center justify-center">
                  <Loader2 class="size-4 animate-spin text-muted-foreground" />
                </div>
                <div v-else class="flex-1 overflow-auto styled-scrollbar p-1">
                  <FileTreeNode
                    v-for="node in previewTree"
                    :key="node.path"
                    :node="node"
                    :open-file-path="previewFile?.path ?? null"
                    :dragged-path="null"
                    :drop-target-path="null"
                    :renaming-path="null"
                    :rename-value="''"
                    :get-change-type="() => null"
                    :readonly="true"
                    @click="onPreviewNodeClick($event)"
                  />
                </div>
              </div>

              <!-- Read-only content viewer -->
              <div class="flex-1 bg-glass/40 rounded-xl border border-glass-border flex flex-col overflow-hidden">
                <div v-if="!previewFile" class="flex-1 flex items-center justify-center text-muted-foreground text-sm">
                  {{ t('components.versionHistory.selectFilePreview') }}
                </div>
                <template v-else>
                  <div class="flex items-center gap-2 px-3 py-2 border-b border-glass-border">
                    <FileCode class="size-3.5 text-muted-foreground" />
                    <span class="text-xs text-foreground font-medium truncate">{{ previewFile.name }}</span>
                    <Badge variant="outline" class="text-[10px] ml-auto">{{ t('components.versionHistory.readOnly') }}</Badge>
                  </div>
                  <div v-if="previewFile.loading" class="flex-1 flex items-center justify-center">
                    <Loader2 class="size-4 animate-spin text-muted-foreground" />
                  </div>
                  <pre v-else class="flex-1 overflow-auto styled-scrollbar p-4 text-xs font-mono text-foreground whitespace-pre-wrap break-all">{{ previewFile.content }}</pre>
                </template>
              </div>
            </div>
          </div>

          <!-- Diff comparison panel -->
          <div v-if="compareVersionHash === v.hash" class="border-b border-glass-border/50 bg-muted/20">
            <div v-if="compareLoading" class="flex items-center justify-center py-8">
              <Loader2 class="size-5 text-muted-foreground animate-spin" />
            </div>
            <div v-else-if="compareFiles.length === 0" class="py-8 text-center text-sm text-muted-foreground">
              {{ t('components.versionHistory.noDifferences') }}
            </div>
            <div v-else class="flex gap-4 p-4" style="height: 400px">
              <!-- Changed files list -->
              <div class="w-48 shrink-0 overflow-auto styled-scrollbar border-r border-glass-border pr-4">
                <p class="text-xs font-medium text-muted-foreground uppercase tracking-wider mb-2">{{ t('components.versionHistory.changedFiles') }}</p>
                <div
                  v-for="f in compareFiles"
                  :key="f.path"
                  :class="[
                    'flex items-center gap-2 px-2 py-1.5 rounded-md cursor-pointer transition-colors text-xs',
                    compareSelectedFile === f.path ? 'bg-primary/10 text-foreground' : 'text-muted-foreground hover:bg-glass-hover',
                  ]"
                  @click="selectCompareFile(f.path)"
                >
                  <div
:class="[
                    'size-1.5 rounded-full shrink-0',
                    f.status === 'added' ? 'bg-success' : f.status === 'removed' ? 'bg-destructive' : 'bg-warning',
                  ]" />
                  <span class="truncate">{{ f.path }}</span>
                </div>
              </div>
              <!-- Diff viewer -->
              <div class="flex-1 min-w-0 overflow-hidden">
                <div v-if="!compareSelectedFile" class="flex items-center justify-center h-full text-sm text-muted-foreground">
                  {{ t('components.versionHistory.selectFileDiff') }}
                </div>
                <div v-else-if="compareFileLoading" class="flex items-center justify-center h-full">
                  <Loader2 class="size-5 text-muted-foreground animate-spin" />
                </div>
                <UiDiffViewer
                  v-else
                  :original="compareOriginal"
                  :modified="compareModified"
                  :original-label="`${compareVersionHash?.slice(0, 8)}`"
                  :modified-label="t('components.versionHistory.current')"
                />
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
