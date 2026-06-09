<script setup lang="ts">
import {Search, LayoutGrid, TableProperties} from 'lucide-vue-next'
import type {FilterOption} from '~/composables/useFilteredList'
import {Badge} from "~/components/ui/badge";

const props = withDefaults(defineProps<{
  searchPlaceholder?: string
  filters?: FilterOption[]
  activeFilters?: Set<string>
  viewMode?: 'grid' | 'table'
  showViewToggle?: boolean
  count?: number
  countLabel?: string
}>(), {
  showViewToggle: true,
})

const { t } = useI18n()

const resolvedSearchPlaceholder = computed(() => props.searchPlaceholder ?? t('common.search'))
const resolvedCountLabel = computed(() => props.countLabel ?? t('common.items'))

const search = defineModel<string>('search', {default: ''})

const emit = defineEmits<{
  'toggle-filter': [key: string]
  'update:viewMode': [mode: 'grid' | 'table']
}>()
</script>

<template>
  <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-4 flex flex-col gap-3">
    <div class="relative w-full">
      <Search class="absolute left-4 top-1/2 -translate-y-1/2 size-4 text-muted-foreground"/>
      <input
          v-model="search"
          type="text"
          :placeholder="resolvedSearchPlaceholder"
          class="h-10 w-full pl-10 pr-4 bg-glass rounded-xl border border-glass-border text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-primary focus:ring-1 focus:ring-primary/50 transition-all text-sm"
      >
    </div>

    <div
v-if="(filters && filters.length > 0) || showViewToggle || count !== undefined || $slots.default"
         class="flex items-center gap-3">
      <div v-if="filters && filters.length > 0" class="flex flex-wrap gap-2">
        <button
            v-for="filter in filters"
            :key="filter.key"
            :class="[
            'inline-flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg transition-all',
            activeFilters?.has(filter.key)
              ? 'bg-primary text-primary-foreground'
              : 'bg-glass text-muted-foreground hover:text-foreground hover:bg-glass-hover',
          ]"
            @click="emit('toggle-filter', filter.key)"
        >
          <component :is="filter.icon" v-if="filter.icon" class="size-3.5"/>
          {{ filter.label }}
        </button>
      </div>

      <slot/>

      <div class="ml-auto flex items-center gap-3">
        <div v-if="count !== undefined" class="shrink-0">
          <Badge variant="outline" class="text-muted-foreground whitespace-nowrap">
            {{ count }} {{ resolvedCountLabel }}
          </Badge>
        </div>

        <div v-if="showViewToggle" class="flex items-center gap-1 bg-glass rounded-lg border border-glass-border p-1">
          <button
              :class="['inline-flex size-8 items-center justify-center rounded-md transition-all', viewMode === 'grid' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground']"
              :aria-label="t('common.gridView')"
              @click="emit('update:viewMode', 'grid')"
          >
            <LayoutGrid class="size-4"/>
          </button>
          <button
              :class="['inline-flex size-8 items-center justify-center rounded-md transition-all', viewMode === 'table' ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground']"
              :aria-label="t('common.tableView')"
              @click="emit('update:viewMode', 'table')"
          >
            <TableProperties class="size-4"/>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
