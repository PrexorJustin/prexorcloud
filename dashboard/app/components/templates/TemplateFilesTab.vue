<script setup lang="ts">
import type { TemplateSearchResult } from "~/types/api"
import TemplateFileTreePanel from "~/components/templates/TemplateFileTreePanel.vue"
import TemplateFileEditorPanel from "~/components/templates/TemplateFileEditorPanel.vue"
import TemplateStagedChangesPanel from "~/components/templates/TemplateStagedChangesPanel.vue"
import type { ChangeType, useTemplateEditor } from "~/composables/useTemplateEditor"
import type { useTemplateMeta } from "~/composables/useTemplateMeta"

const props = defineProps<{
  editor: ReturnType<typeof useTemplateEditor>
  meta: ReturnType<typeof useTemplateMeta>
  validationErrors: { line: number; column: number; message: string; severity: 'error' | 'warning' }[]
  changeTypeStyles: Record<ChangeType, { label: string; class: string; dot: string }>
}>()

const emit = defineEmits<{
  (e: 'upload'): void
  (e: 'searchSelect', r: TemplateSearchResult): void
  (e: 'save'): void
}>()

function onEditorContentUpdate(value: string) {
  if (props.editor.openFile.value) props.editor.openFile.value.content = value
}
</script>

<template>
  <div class="flex gap-4 flex-1 min-h-0" style="height: calc(100vh - 320px)">
    <TemplateFileTreePanel
      v-model:show-search="meta.showSearch.value"
      :tree="editor.tree.value"
      :tree-loading="editor.treeLoading.value"
      :open-file-path="editor.openFile.value?.path ?? null"
      :dragged-path="editor.draggedNode.value?.path ?? null"
      :drop-target-path="editor.dropTargetPath.value"
      :renaming-path="editor.renamingPath.value"
      :rename-value="editor.renameValue.value"
      :show-new-input="editor.showNewInput.value"
      :new-file-target-path="editor.newFileTargetPath.value"
      :new-item-name="editor.newItemName.value"
      :search-results="meta.searchResults.value"
      :search-loading="meta.searchLoading.value"
      :search-query="meta.searchQuery.value"
      :get-change-type="editor.getChangeType"
      @new-file="editor.startNewFile"
      @new-dir="editor.startNewDir"
      @upload="emit('upload')"
      @refresh="editor.loadRoot()"
      @search="meta.onSearch"
      @search-select="(r) => emit('searchSelect', r)"
      @cancel-new-item="editor.cancelNewItem"
      @confirm-new-item="editor.confirmNewItem"
      @file-click="editor.onFileClick"
      @start-rename="editor.startRename"
      @request-delete="editor.requestDelete"
      @extract="editor.extractZipFile"
      @download="editor.downloadFile"
      @drag-action="editor.handleDragAction"
      @update:rename-value="editor.renameValue.value = $event"
      @update:new-item-name="editor.newItemName.value = $event"
      @confirm-rename="editor.confirmRename"
      @cancel-rename="editor.cancelRename"
      @root-drag-over="editor.onRootDragOver"
      @root-drag-leave="editor.onRootDragLeave"
      @root-drop="editor.onRootDrop"
    />

    <TemplateFileEditorPanel
      :open-file="editor.openFile.value"
      :binary-file="editor.binaryFile.value"
      :extracting="editor.extracting.value"
      :editor-language="editor.editorLanguage.value"
      :validation-errors="validationErrors"
      :file-is-modified="editor.fileIsModified.value"
      :change-type-styles="changeTypeStyles"
      :get-change-type="editor.getChangeType"
      :get-file-icon="editor.getFileIcon"
      @update:open-file-content="onEditorContentUpdate"
      @extract="editor.extractZipFile"
      @download="editor.downloadFile"
      @revert="editor.revertCurrentFile"
    />

    <TemplateStagedChangesPanel
      v-if="editor.hasChanges.value"
      :changes="editor.stagedChangesList.value"
      :change-type-styles="changeTypeStyles"
      :saving="editor.saving.value"
      @unstage="editor.unstageChange"
      @save="emit('save')"
    />
  </div>
</template>
