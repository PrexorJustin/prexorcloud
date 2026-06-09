<script setup lang="ts">
withDefaults(defineProps<{
  mode?: 'grid' | 'table'
  count?: number
}>(), {
  mode: 'grid',
  count: 6,
})
</script>

<template>
  <!-- Grid skeleton -->
  <div v-if="mode === 'grid'" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
    <div v-for="i in count" :key="i" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-5 animate-pulse">
      <div class="flex items-center gap-3 mb-4">
        <div class="size-10 rounded-xl bg-glass" />
        <div class="flex-1">
          <div class="h-4 bg-glass rounded w-24 mb-2" />
          <div class="h-3 bg-glass rounded w-32" />
        </div>
      </div>
      <div class="grid grid-cols-3 gap-3 mt-3">
        <div class="h-4 bg-glass rounded" />
        <div class="h-4 bg-glass rounded" />
        <div class="h-4 bg-glass rounded" />
      </div>
    </div>
  </div>

  <!-- Table skeleton -->
  <div v-else class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-5 animate-pulse">
    <div class="h-10 bg-glass rounded mb-3" />
    <div v-for="i in count" :key="i" class="h-12 bg-glass rounded mb-2 last:mb-0" />
  </div>
</template>
