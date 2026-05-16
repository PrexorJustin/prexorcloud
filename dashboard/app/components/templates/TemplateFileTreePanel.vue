<script setup lang="ts">
import { Check, File, FilePlus, Folder, FolderPlus, RefreshCw, Search, Upload, X } from "lucide-vue-next"
import FileTreeNode, { type TreeNode } from "~/components/templates/FileTreeNode.vue"
import type { TemplateSearchResult } from "~/types/api"

type ChangeType = "modified" | "created" | "deleted" | "renamed"

const props = defineProps<{
  tree: TreeNode[]
  treeLoading: boolean
  openFilePath: string | null
  draggedPath: string | null
  dropTargetPath: string | null
  renamingPath: string | null
  renameValue: string
  showNewInput: 'file' | 'dir' | null
  newFileTargetPath: string
  newItemName: string
  showSearch: boolean
  searchResults: TemplateSearchResult[]
  searchLoading: boolean
  searchQuery: string
  getChangeType: (path: string) => ChangeType | null
}>()

const emit = defineEmits<{
  (e: 'update:showSearch', value: boolean): void
  (e: 'update:renameValue', value: string): void
  (e: 'update:newItemName', value: string): void
  (e: 'newFile', path: string): void
  (e: 'newDir', path: string): void
  (e: 'upload'): void
  (e: 'refresh'): void
  (e: 'search', q: string): void
  (e: 'searchSelect', r: TemplateSearchResult): void
  (e: 'cancelNewItem'): void
  (e: 'confirmNewItem'): void
  (e: 'fileClick', node: TreeNode): void
  (e: 'startRename', node: TreeNode): void
  (e: 'requestDelete', path: string): void
  (e: 'extract', path: string): void
  (e: 'download', path: string): void
  (e: 'dragAction', payload: { action: string; e?: DragEvent; node?: TreeNode; path?: string }): void
  (e: 'confirmRename', node: TreeNode): void
  (e: 'cancelRename'): void
  (e: 'rootDragOver', event: DragEvent): void
  (e: 'rootDragLeave'): void
  (e: 'rootDrop', event: DragEvent): void
}>()
</script>

<template>
  <div class="w-72 shrink-0 bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border flex flex-col overflow-hidden">
    <!-- Tree header -->
    <div class="flex items-center justify-between px-4 py-3 border-b border-glass-border">
      <span class="text-xs font-medium text-muted-foreground uppercase tracking-wider">Files</span>
      <div class="flex items-center gap-1">
        <button
          class="size-7 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors"
          title="New file"
          @click="emit('newFile', '')"
        >
          <FilePlus class="size-3.5" />
        </button>
        <button
          class="size-7 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors"
          title="New folder"
          @click="emit('newDir', '')"
        >
          <FolderPlus class="size-3.5" />
        </button>
        <button
          class="size-7 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors"
          title="Upload files"
          @click="emit('upload')"
        >
          <Upload class="size-3.5" />
        </button>
        <button
          class="size-7 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors"
          title="Refresh"
          @click="emit('refresh')"
        >
          <RefreshCw class="size-3.5" />
        </button>
        <button
          class="size-7 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors"
          :class="showSearch ? 'bg-primary/10 text-primary' : ''"
          title="Search files"
          @click="emit('update:showSearch', !showSearch)"
        >
          <Search class="size-3.5" />
        </button>
      </div>
    </div>

    <!-- File search -->
    <div v-if="showSearch" class="border-b border-glass-border">
      <UiFileSearchPanel
        :results="searchResults"
        :loading="searchLoading"
        :query="searchQuery"
        @search="(q: string) => emit('search', q)"
        @select="(r: TemplateSearchResult) => emit('searchSelect', r)"
      />
    </div>

    <!-- New item inline input (root level) -->
    <div v-if="showNewInput && newFileTargetPath === ''" class="px-3 py-2 border-b border-glass-border">
      <div class="flex items-center gap-2">
        <Folder v-if="showNewInput === 'dir'" class="size-4 text-primary shrink-0" />
        <File v-else class="size-4 text-muted-foreground shrink-0" />
        <input
          :value="newItemName"
          type="text"
          :placeholder="showNewInput === 'dir' ? 'folder name' : 'file name'"
          class="flex-1 h-7 px-2 bg-glass rounded border border-glass-border text-foreground text-xs focus:outline-none focus:border-primary"
          autofocus
          @input="(e) => emit('update:newItemName', (e.target as HTMLInputElement).value)"
          @keyup.enter="emit('confirmNewItem')"
          @keyup.escape="emit('cancelNewItem')"
        >
        <button class="text-success" @click="emit('confirmNewItem')"><Check class="size-3.5" /></button>
        <button class="text-muted-foreground" @click="emit('cancelNewItem')"><X class="size-3.5" /></button>
      </div>
    </div>

    <!-- Tree content -->
    <div
      class="flex-1 overflow-auto styled-scrollbar p-2"
      :class="dropTargetPath === '__root__' ? 'ring-2 ring-primary/40 ring-inset rounded-lg' : ''"
      @dragover="(e) => emit('rootDragOver', e)"
      @dragleave="emit('rootDragLeave')"
      @drop="(e) => emit('rootDrop', e)"
    >
      <div v-if="treeLoading" class="flex items-center justify-center py-8">
        <RefreshCw class="size-4 text-muted-foreground animate-spin" />
      </div>
      <template v-else>
        <FileTreeNode
          v-for="node in tree"
          :key="node.path"
          :node="node"
          :open-file-path="openFilePath"
          :dragged-path="draggedPath"
          :drop-target-path="dropTargetPath"
          :renaming-path="renamingPath"
          :rename-value="renameValue"
          :get-change-type="getChangeType"
          :show-new-input="showNewInput"
          :new-file-target-path="newFileTargetPath"
          :new-item-name="newItemName"
          @click="emit('fileClick', $event)"
          @dblclick="emit('startRename', $event)"
          @delete="emit('requestDelete', $event)"
          @extract="emit('extract', $event)"
          @new-file="emit('newFile', $event)"
          @new-dir="emit('newDir', $event)"
          @download="emit('download', $event)"
          @drag-action="emit('dragAction', $event)"
          @update:rename-value="emit('update:renameValue', $event)"
          @confirm-rename="emit('confirmRename', $event)"
          @cancel-rename="emit('cancelRename')"
          @confirm-new-item="emit('confirmNewItem')"
          @cancel-new-item="emit('cancelNewItem')"
          @update:new-item-name="emit('update:newItemName', $event)"
        />
      </template>
    </div>
  </div>
</template>
