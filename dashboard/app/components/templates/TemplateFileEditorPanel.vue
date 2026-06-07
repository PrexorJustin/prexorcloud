<script setup lang="ts">
import { Archive, Download, File, FileCode, Loader2, RefreshCw, RotateCcw } from "lucide-vue-next"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import { Separator } from "~/components/ui/separator"
import { formatBytes } from "~/lib/utils"

type ChangeType = "modified" | "created" | "deleted" | "renamed"

interface OpenFile {
  path: string
  name: string
  originalContent: string
  content: string
  loading: boolean
}

const props = defineProps<{
  openFile: OpenFile | null
  binaryFile: { path: string; name: string; size: number } | null
  extracting: boolean
  editorLanguage: string
  validationErrors: { line: number; column: number; message: string; severity: 'error' | 'warning' }[]
  fileIsModified: boolean
  changeTypeStyles: Record<ChangeType, { label: string; class: string; dot: string }>
  getChangeType: (path: string) => ChangeType | null
  getFileIcon: (name: string) => string
}>()

const emit = defineEmits<{
  (e: 'update:openFileContent', value: string): void
  (e: 'extract', path: string): void
  (e: 'download', path: string): void
  (e: 'revert'): void
}>()

const { t } = useI18n()

const editorContent = computed({
  get: () => props.openFile?.content ?? '',
  set: (v) => emit('update:openFileContent', v),
})
</script>

<template>
  <div class="flex-1 bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border flex flex-col overflow-hidden">
    <!-- Binary file -->
    <div v-if="!openFile && binaryFile" class="flex-1 flex flex-col items-center justify-center text-center p-8">
      <File class="size-16 text-muted-foreground/20 mb-4" />
      <p class="text-foreground font-medium">{{ binaryFile.name }}</p>
      <p class="text-muted-foreground text-sm mt-1">{{ t('components.templateEditor.binaryFile') }} · {{ formatBytes(binaryFile.size) }}</p>
      <div class="flex items-center gap-2 mt-4">
        <Button v-if="binaryFile.name.toLowerCase().endsWith('.zip')" size="sm" :disabled="extracting" @click="emit('extract', binaryFile.path)">
          <Loader2 v-if="extracting" class="size-3.5 mr-1.5 animate-spin" />
          <Archive v-else class="size-3.5 mr-1.5" /> {{ t('components.templateEditor.extract') }}
        </Button>
        <Button variant="outline" size="sm" class="border-glass-border" @click="emit('download', binaryFile.path)">
          <Download class="size-3.5 mr-1.5" /> {{ t('components.templateEditor.download') }}
        </Button>
      </div>
    </div>

    <!-- No file open -->
    <div v-else-if="!openFile" class="flex-1 flex flex-col items-center justify-center text-center p-8">
      <FileCode class="size-16 text-muted-foreground/20 mb-4" />
      <p class="text-foreground font-medium">{{ t('components.templateEditor.noFileSelected') }}</p>
      <p class="text-muted-foreground text-sm mt-1">{{ t('components.templateEditor.noFileHint') }}</p>
    </div>

    <template v-else>
      <!-- Editor toolbar -->
      <div class="flex items-center gap-2 px-4 py-2.5 border-b border-glass-border">
        <File :class="['size-4 shrink-0', getFileIcon(openFile.name)]" />
        <span class="text-sm font-medium text-foreground truncate">{{ openFile.path }}</span>

        <Badge v-if="fileIsModified" variant="outline" class="text-[10px] text-warning border-warning/30 ml-1">
          {{ t('components.templateEditor.unsaved') }}
        </Badge>
        <Badge v-if="getChangeType(openFile.path)" variant="outline" :class="['text-[10px] ml-1', changeTypeStyles[getChangeType(openFile.path)!].class]">
          {{ t('components.templateEditor.staged') }}
        </Badge>

        <span class="text-[10px] text-muted-foreground/50 ml-1 uppercase">{{ editorLanguage }}</span>
        <Badge v-if="validationErrors.length > 0" variant="outline" class="text-[10px] text-destructive border-destructive/30 ml-1">
          {{ t('components.templateEditor.errors', { count: validationErrors.length }, validationErrors.length) }}
        </Badge>

        <div class="ml-auto flex items-center gap-1">
          <button
            class="size-7 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors"
            :title="t('components.templateEditor.downloadFile')"
            @click="emit('download', openFile.path)"
          >
            <Download class="size-3.5" />
          </button>

          <Separator orientation="vertical" class="h-4 mx-1" />

          <Button
            variant="ghost"
            size="sm"
            class="h-7 px-2 text-xs"
            :disabled="!fileIsModified"
            @click="emit('revert')"
          >
            <RotateCcw class="size-3 mr-1" /> {{ t('components.templateEditor.revert') }}
          </Button>
        </div>
      </div>

      <!-- Loading -->
      <div v-if="openFile.loading" class="flex-1 flex items-center justify-center">
        <RefreshCw class="size-5 text-muted-foreground animate-spin" />
      </div>

      <!-- Code editor -->
      <div v-else class="flex-1 overflow-hidden">
        <TemplatesCodeEditor
          v-model="editorContent"
          :language="editorLanguage"
          :diagnostics="validationErrors"
        />
      </div>
    </template>
  </div>
</template>
