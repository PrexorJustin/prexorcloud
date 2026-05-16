<script setup lang="ts">
import {
  ChevronRight, Folder, File, FilePlus, FolderPlus, Trash2,
  Check, X, Download, Archive,
} from "lucide-vue-next"
import { formatBytes } from "~/lib/utils"

export interface TreeNode {
  name: string
  path: string
  isDirectory: boolean
  size: number
  children?: TreeNode[]
  expanded?: boolean
  loaded?: boolean
}

type ChangeType = "modified" | "created" | "deleted" | "renamed"

const props = defineProps<{
  node: TreeNode
  depth?: number
  openFilePath?: string | null
  draggedPath?: string | null
  dropTargetPath?: string | null
  renamingPath?: string | null
  renameValue?: string
  getChangeType: (path: string) => ChangeType | null
  showNewInput?: "file" | "dir" | null
  newFileTargetPath?: string
  newItemName?: string
  readonly?: boolean
}>()

const emit = defineEmits<{
  click: [node: TreeNode]
  dblclick: [node: TreeNode]
  delete: [path: string]
  extract: [path: string]
  newFile: [parentPath: string]
  newDir: [parentPath: string]
  download: [path: string]
  dragAction: [payload: { action: "start"; e: DragEvent; node: TreeNode }
    | { action: "end" }
    | { action: "over"; e: DragEvent; path: string }
    | { action: "leave"; e: DragEvent; path: string }
    | { action: "drop"; e: DragEvent; path: string }]
  "update:renameValue": [value: string]
  confirmRename: [node: TreeNode]
  cancelRename: []
  confirmNewItem: []
  cancelNewItem: []
  "update:newItemName": [value: string]
}>()

const depth = computed(() => props.depth ?? 0)
const isZip = computed(() => props.node.name.toLowerCase().endsWith('.zip'))

const changeTypeStyles: Record<ChangeType, { label: string; class: string }> = {
  modified: { label: "M", class: "text-warning" },
  created: { label: "A", class: "text-success" },
  deleted: { label: "D", class: "text-destructive line-through" },
  renamed: { label: "R", class: "text-primary" },
}

function getFileIcon(name: string) {
  const ext = name.split(".").pop()?.toLowerCase()
  if (["yml", "yaml"].includes(ext ?? "")) return "text-yellow-500"
  if (["json"].includes(ext ?? "")) return "text-green-500"
  if (["properties", "conf", "cfg", "ini", "toml"].includes(ext ?? "")) return "text-blue-400"
  if (["zip"].includes(ext ?? "")) return "text-violet-500"
  if (["jar"].includes(ext ?? "")) return "text-orange-500"
  if (["sh", "bat"].includes(ext ?? "")) return "text-emerald-400"
  return "text-muted-foreground"
}


</script>

<template>
  <div>
    <div
      :class="[
        'flex items-center gap-1.5 px-2 py-1.5 rounded-lg cursor-pointer group/node transition-colors text-sm select-none',
        getChangeType(node.path) === 'deleted' ? 'opacity-40' : '',
        openFilePath === node.path ? 'bg-primary/10 text-foreground' : 'hover:bg-glass-hover text-foreground',
        node.isDirectory && dropTargetPath === node.path ? 'ring-2 ring-primary/40 bg-primary/5' : '',
        draggedPath === node.path ? 'opacity-40' : '',
      ]"
      :draggable="!readonly"
      @dragstart="emit('dragAction', { action: 'start', e: $event, node })"
      @dragend="emit('dragAction', { action: 'end' })"
      @dragover="node.isDirectory ? emit('dragAction', { action: 'over', e: $event, path: node.path }) : undefined"
      @dragleave="node.isDirectory ? emit('dragAction', { action: 'leave', e: $event, path: node.path }) : undefined"
      @drop="node.isDirectory ? emit('dragAction', { action: 'drop', e: $event, path: node.path }) : undefined"
      @click="emit('click', node)"
      @dblclick.stop="!node.isDirectory && emit('dblclick', node)"
    >
      <ChevronRight
        v-if="node.isDirectory"
        :class="['size-3 text-muted-foreground transition-transform shrink-0', node.expanded ? 'rotate-90' : '']"
      />
      <div v-else class="w-3 shrink-0" />

      <Folder v-if="node.isDirectory" class="size-4 text-primary shrink-0" />
      <File v-else :class="['size-4 shrink-0', getFileIcon(node.name)]" />

      <template v-if="renamingPath === node.path">
        <input
          :value="renameValue"
          type="text"
          class="flex-1 h-6 px-1.5 bg-glass rounded border border-primary text-foreground text-xs focus:outline-none"
          autofocus
          @click.stop
          @input="emit('update:renameValue', ($event.target as HTMLInputElement).value)"
          @keyup.enter="emit('confirmRename', node)"
          @keyup.escape="emit('cancelRename')"
        >
      </template>
      <template v-else>
        <span :class="['flex-1 truncate text-xs', changeTypeStyles[getChangeType(node.path)!]?.class]">
          {{ node.name }}
        </span>
        <span v-if="!node.isDirectory" class="text-[10px] text-muted-foreground tabular-nums shrink-0">
          {{ formatBytes(node.size) }}
        </span>
      </template>

      <!-- Change indicator -->
      <span
        v-if="getChangeType(node.path)"
        :class="['text-[10px] font-bold shrink-0', changeTypeStyles[getChangeType(node.path)!].class]"
      >
        {{ changeTypeStyles[getChangeType(node.path)!].label }}
      </span>

      <!-- Actions (hover) -->
      <div v-if="!readonly" class="hidden group-hover/node:flex items-center gap-0.5 shrink-0" @click.stop>
        <button
          v-if="node.isDirectory"
          class="size-5 rounded flex items-center justify-center text-muted-foreground hover:text-foreground"
          title="New file"
          @click="emit('newFile', node.path)"
        >
          <FilePlus class="size-3" />
        </button>
        <button
          v-if="node.isDirectory"
          class="size-5 rounded flex items-center justify-center text-muted-foreground hover:text-foreground"
          title="New folder"
          @click="emit('newDir', node.path)"
        >
          <FolderPlus class="size-3" />
        </button>
        <button
          v-if="!node.isDirectory && isZip"
          class="size-5 rounded flex items-center justify-center text-muted-foreground hover:text-foreground"
          title="Extract ZIP"
          @click="emit('extract', node.path)"
        >
          <Archive class="size-3" />
        </button>
        <button
          v-if="!node.isDirectory"
          class="size-5 rounded flex items-center justify-center text-muted-foreground hover:text-foreground"
          title="Download"
          @click="emit('download', node.path)"
        >
          <Download class="size-3" />
        </button>
        <button
          class="size-5 rounded flex items-center justify-center text-muted-foreground hover:text-destructive"
          title="Delete"
          @click="emit('delete', node.path)"
        >
          <Trash2 class="size-3" />
        </button>
      </div>
    </div>

    <!-- Children (expanded) -->
    <div v-if="node.isDirectory && node.expanded" class="ml-3 pl-2 border-l border-glass-border/50">
      <!-- Inline new item input for this directory -->
      <div v-if="showNewInput && newFileTargetPath === node.path" class="flex items-center gap-1.5 px-2 py-1.5">
        <div class="w-3 shrink-0" />
        <Folder v-if="showNewInput === 'dir'" class="size-4 text-primary shrink-0" />
        <File v-else class="size-4 text-muted-foreground shrink-0" />
        <input
          :value="newItemName"
          type="text"
          :placeholder="showNewInput === 'dir' ? 'folder name' : 'file name'"
          class="flex-1 h-6 px-1.5 bg-glass rounded border border-glass-border text-foreground text-xs focus:outline-none focus:border-primary"
          autofocus
          @input="emit('update:newItemName', ($event.target as HTMLInputElement).value)"
          @keyup.enter="emit('confirmNewItem')"
          @keyup.escape="emit('cancelNewItem')"
          @click.stop
        >
        <button class="text-success shrink-0" @click.stop="emit('confirmNewItem')"><Check class="size-3" /></button>
        <button class="text-muted-foreground shrink-0" @click.stop="emit('cancelNewItem')"><X class="size-3" /></button>
      </div>

      <FileTreeNode
        v-for="child in node.children"
        :key="child.path"
        :node="child"
        :depth="depth + 1"
        :open-file-path="openFilePath"
        :dragged-path="draggedPath"
        :drop-target-path="dropTargetPath"
        :renaming-path="renamingPath"
        :rename-value="renameValue"
        :get-change-type="getChangeType"
        :show-new-input="showNewInput"
        :new-file-target-path="newFileTargetPath"
        :new-item-name="newItemName"
        :readonly="readonly"
        @click="emit('click', $event)"
        @dblclick="emit('dblclick', $event)"
        @delete="emit('delete', $event)"
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
    </div>
  </div>
</template>
