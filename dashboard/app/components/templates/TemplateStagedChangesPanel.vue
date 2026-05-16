<script setup lang="ts">
import { Save, X } from "lucide-vue-next"
import { Button } from "~/components/ui/button"

type ChangeType = "modified" | "created" | "deleted" | "renamed"

interface StagedChange {
  type: ChangeType
  path: string
  content?: string
  newPath?: string
  isDir?: boolean
}

defineProps<{
  changes: StagedChange[]
  changeTypeStyles: Record<ChangeType, { label: string; class: string; dot: string }>
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'unstage', path: string): void
  (e: 'save'): void
}>()
</script>

<template>
  <div class="w-56 shrink-0 bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border flex flex-col overflow-hidden">
    <div class="px-4 py-3 border-b border-glass-border">
      <span class="text-xs font-medium text-muted-foreground uppercase tracking-wider">Staged Changes</span>
    </div>
    <div class="flex-1 overflow-auto styled-scrollbar p-2">
      <div
        v-for="change in changes"
        :key="change.path"
        class="flex items-center gap-2 px-2 py-1.5 rounded-lg hover:bg-glass-hover transition-colors group/staged"
      >
        <div :class="['size-2 rounded-full shrink-0', changeTypeStyles[change.type].dot]" />
        <span :class="['flex-1 text-xs truncate', changeTypeStyles[change.type].class]" :title="change.path">
          {{ change.path.split('/').pop() }}
        </span>
        <span :class="['text-[10px] font-bold shrink-0', changeTypeStyles[change.type].class]">
          {{ changeTypeStyles[change.type].label }}
        </span>
        <button
          class="size-4 rounded flex items-center justify-center text-muted-foreground hover:text-foreground opacity-0 group-hover/staged:opacity-100 transition-opacity shrink-0"
          title="Unstage"
          @click="emit('unstage', change.path)"
        >
          <X class="size-3" />
        </button>
      </div>
    </div>
    <div class="px-3 py-2.5 border-t border-glass-border">
      <Button class="w-full h-8 text-xs" :disabled="saving" @click="emit('save')">
        <Save class="size-3 mr-1.5" />
        {{ saving ? "Saving..." : "Save & Rehash" }}
      </Button>
    </div>
  </div>
</template>
