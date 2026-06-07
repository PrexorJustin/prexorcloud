<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  icon: Component
  title: string
  description?: string
}>()
</script>

<template>
  <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border px-6 py-16 flex flex-col items-center justify-center text-center">
    <component :is="icon" class="size-8 text-muted-foreground/40 mb-4" stroke-width="2" />
    <p class="text-foreground font-semibold text-base">{{ title }}</p>
    <p v-if="description" class="text-muted-foreground text-sm mt-1 max-w-sm">{{ description }}</p>
    <div v-if="$slots.default" class="mt-5">
      <slot />
    </div>
  </div>
</template>
