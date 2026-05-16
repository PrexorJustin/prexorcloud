<script setup lang="ts">
import { Search, File, Loader2, ChevronDown, ChevronRight } from "lucide-vue-next"
import { Badge } from "~/components/ui/badge"

export interface SearchResult {
  path: string
  line: number
  content: string
  matchStart: number
  matchEnd: number
}

const props = defineProps<{
  results: SearchResult[]
  loading: boolean
  query: string
}>()

const emit = defineEmits<{
  search: [query: string]
  select: [result: SearchResult]
}>()

// Group results by file path
const grouped = computed(() => {
  const map = new Map<string, SearchResult[]>()
  for (const result of props.results) {
    const group = map.get(result.path)
    if (group) group.push(result)
    else map.set(result.path, [result])
  }
  return map
})

// Track collapsed file groups
const collapsed = ref<Set<string>>(new Set())

function toggleGroup(path: string) {
  const next = new Set(collapsed.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  collapsed.value = next
}

function onInput(event: Event) {
  const value = (event.target as HTMLInputElement).value
  emit("search", value)
}
</script>

<template>
  <div class="flex flex-col gap-3">
    <!-- Search input -->
    <div class="relative">
      <Search class="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
      <input
        type="text"
        :value="query"
        placeholder="Search in files..."
        class="h-8 w-full pl-9 pr-3 bg-glass border border-glass-border rounded-lg text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-primary focus:ring-1 focus:ring-primary/50 transition-all"
        @input="onInput"
      >
    </div>

    <!-- Loading -->
    <div v-if="loading" class="flex items-center justify-center py-8">
      <Loader2 class="size-5 text-muted-foreground animate-spin" />
    </div>

    <!-- Results -->
    <div v-else-if="query && results.length > 0" class="max-h-80 overflow-auto styled-scrollbar flex flex-col gap-2">
      <div v-for="[path, matches] in grouped" :key="path" class="flex flex-col">
        <!-- File header -->
        <button
          class="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-glass-hover transition-colors text-left w-full"
          @click="toggleGroup(path)"
        >
          <component
            :is="collapsed.has(path) ? ChevronRight : ChevronDown"
            class="size-3.5 text-muted-foreground shrink-0"
          />
          <File class="size-3.5 text-muted-foreground shrink-0" />
          <span class="text-xs font-medium text-foreground truncate flex-1">{{ path }}</span>
          <Badge variant="outline" class="text-[10px] shrink-0">{{ matches.length }}</Badge>
        </button>

        <!-- Matches -->
        <div v-if="!collapsed.has(path)" class="flex flex-col ml-5">
          <div
            v-for="(result, i) in matches"
            :key="i"
            class="flex items-center gap-2 px-2 py-1 rounded-md hover:bg-glass-hover cursor-pointer transition-colors"
            @click="emit('select', result)"
          >
            <span class="text-xs text-muted-foreground tabular-nums w-8 text-right shrink-0">
              {{ result.line }}
            </span>
            <span class="text-xs font-mono truncate">
              <span>{{ result.content.slice(0, result.matchStart) }}</span>
              <span class="bg-primary/20 text-primary font-medium rounded-sm px-0.5">{{
                result.content.slice(result.matchStart, result.matchEnd)
              }}</span>
              <span>{{ result.content.slice(result.matchEnd) }}</span>
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- No results -->
    <div v-else-if="query && results.length === 0" class="flex flex-col items-center justify-center py-8 text-muted-foreground">
      <Search class="size-8 text-muted-foreground/30 mb-2" />
      <span class="text-sm">No results found</span>
    </div>

    <!-- Initial state -->
    <div v-else class="flex items-center justify-center py-8 text-sm text-muted-foreground">
      Type to search across all files
    </div>
  </div>
</template>
