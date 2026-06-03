<script setup lang="ts">
import { AlignJustify, Columns2 } from "lucide-vue-next"
import { useDiff } from "~/composables/useDiff"
import type { DiffLine } from "~/composables/useDiff"

const props = withDefaults(defineProps<{
  original: string
  modified: string
  language?: string
  originalLabel?: string
  modifiedLabel?: string
}>(), {
  originalLabel: "",
  modifiedLabel: "",
})

const { t } = useI18n()

const originalRef = computed(() => props.original)
const modifiedRef = computed(() => props.modified)

const diff = useDiff(originalRef, modifiedRef)

const viewMode = ref<"unified" | "split">("unified")

// Split view: build aligned left/right arrays with spacers
const splitLines = computed(() => {
  const left: (DiffLine | null)[] = []
  const right: (DiffLine | null)[] = []

  const lines = diff.value.lines
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (!line) { i++; continue }
    if (line.type === "unchanged") {
      left.push(line)
      right.push(line)
      i++
    } else if (line.type === "removed") {
      // Collect consecutive removed lines
      const removedStart = i
      while (i < lines.length && lines[i]?.type === "removed") i++
      // Collect consecutive added lines
      const addedStart = i
      while (i < lines.length && lines[i]?.type === "added") i++

      const removedCount = addedStart - removedStart
      const addedCount = i - addedStart
      const maxCount = Math.max(removedCount, addedCount)

      for (let j = 0; j < maxCount; j++) {
        left.push(j < removedCount ? (lines[removedStart + j] ?? null) : null)
        right.push(j < addedCount ? (lines[addedStart + j] ?? null) : null)
      }
    } else if (line.type === "added") {
      left.push(null)
      right.push(line)
      i++
    }
  }

  return { left, right }
})

// Sync scroll for split view
const leftPanelRef = ref<HTMLElement | null>(null)
const rightPanelRef = ref<HTMLElement | null>(null)
let syncing = false

function onScroll(source: "left" | "right") {
  if (syncing) return
  syncing = true
  const src = source === "left" ? leftPanelRef.value : rightPanelRef.value
  const dst = source === "left" ? rightPanelRef.value : leftPanelRef.value
  if (src && dst) {
    dst.scrollTop = src.scrollTop
    dst.scrollLeft = src.scrollLeft
  }
  syncing = false
}

function lineClass(type: DiffLine["type"]) {
  if (type === "added") return "bg-success/10 border-l-2 border-l-success"
  if (type === "removed") return "bg-destructive/10 border-l-2 border-l-destructive"
  return ""
}
</script>

<template>
  <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border overflow-hidden">
    <!-- Header -->
    <div class="flex items-center justify-between px-4 py-3 border-b border-glass-border">
      <div class="flex items-center gap-4 text-sm text-muted-foreground">
        <span class="font-medium text-foreground">{{ originalLabel || t('components.diff.original') }}</span>
        <span class="text-muted-foreground/40">&rarr;</span>
        <span class="font-medium text-foreground">{{ modifiedLabel || t('components.diff.modified') }}</span>
      </div>
      <div class="flex items-center gap-1 bg-glass rounded-lg border border-glass-border p-1">
        <button
          :class="['inline-flex size-7 items-center justify-center rounded-md transition-all', viewMode === 'unified' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground']"
          :title="t('components.diff.unifiedView')"
          @click="viewMode = 'unified'"
        >
          <AlignJustify class="size-3.5" />
        </button>
        <button
          :class="['inline-flex size-7 items-center justify-center rounded-md transition-all', viewMode === 'split' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground']"
          :title="t('components.diff.splitView')"
          @click="viewMode = 'split'"
        >
          <Columns2 class="size-3.5" />
        </button>
      </div>
    </div>

    <!-- Stats -->
    <div v-if="diff.hasChanges" class="flex items-center gap-3 px-4 py-2 border-b border-glass-border text-xs">
      <span class="text-success font-medium">{{ t('components.diff.additions', { count: diff.additions }, diff.additions) }}</span>
      <span class="text-destructive font-medium">{{ t('components.diff.deletions', { count: diff.deletions }, diff.deletions) }}</span>
    </div>

    <!-- No changes -->
    <div v-if="!diff.hasChanges" class="flex items-center justify-center py-12 text-sm text-muted-foreground">
      {{ t('components.diff.noDiff') }}
    </div>

    <!-- Unified view -->
    <div v-else-if="viewMode === 'unified'" class="overflow-auto max-h-[32rem] styled-scrollbar">
      <div class="font-mono text-sm min-w-fit">
        <div
          v-for="(line, i) in diff.lines"
          :key="i"
          :class="['flex', lineClass(line.type)]"
        >
          <span class="w-12 shrink-0 text-right pr-2 text-xs text-muted-foreground tabular-nums select-none leading-6">
            {{ line.oldLineNumber ?? '' }}
          </span>
          <span class="w-12 shrink-0 text-right pr-3 text-xs text-muted-foreground tabular-nums select-none leading-6 border-r border-glass-border/50">
            {{ line.newLineNumber ?? '' }}
          </span>
          <span class="px-3 leading-6 whitespace-pre">{{ line.content }}</span>
        </div>
      </div>
    </div>

    <!-- Split view -->
    <div v-else class="flex overflow-hidden max-h-[32rem]">
      <!-- Left panel (original) -->
      <div
        ref="leftPanelRef"
        class="flex-1 overflow-auto styled-scrollbar border-r border-glass-border"
        @scroll="onScroll('left')"
      >
        <div class="font-mono text-sm min-w-fit">
          <div
            v-for="(line, i) in splitLines.left"
            :key="i"
            :class="['flex', line ? lineClass(line.type) : '']"
          >
            <span class="w-12 shrink-0 text-right pr-3 text-xs text-muted-foreground tabular-nums select-none leading-6">
              {{ line?.oldLineNumber ?? '' }}
            </span>
            <span class="px-3 leading-6 whitespace-pre">{{ line?.content ?? '' }}</span>
          </div>
        </div>
      </div>
      <!-- Right panel (modified) -->
      <div
        ref="rightPanelRef"
        class="flex-1 overflow-auto styled-scrollbar"
        @scroll="onScroll('right')"
      >
        <div class="font-mono text-sm min-w-fit">
          <div
            v-for="(line, i) in splitLines.right"
            :key="i"
            :class="['flex', line ? lineClass(line.type) : '']"
          >
            <span class="w-12 shrink-0 text-right pr-3 text-xs text-muted-foreground tabular-nums select-none leading-6">
              {{ line?.newLineNumber ?? '' }}
            </span>
            <span class="px-3 leading-6 whitespace-pre">{{ line?.content ?? '' }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
