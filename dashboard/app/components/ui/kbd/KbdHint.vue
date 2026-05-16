<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from "@/lib/utils"

const props = defineProps<{ class?: HTMLAttributes["class"] }>()
</script>

<template>
  <kbd
    :class="cn('inline-flex items-center justify-center rounded-md border border-glass-border bg-glass px-1.5 py-0.5 mono text-[10px] font-medium leading-none text-muted-foreground tabular', props.class)"
  >
    <slot />
  </kbd>
</template>
