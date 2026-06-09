<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from "@/lib/utils"

const props = withDefaults(defineProps<{
  fade?: boolean
  class?: HTMLAttributes["class"]
}>(), {
  fade: true,
})
</script>

<template>
  <div
    aria-hidden="true"
    :class="cn(
      'pointer-events-none absolute inset-0 bg-dot-pattern',
      props.fade && '[mask-image:radial-gradient(ellipse_at_center,black_30%,transparent_75%)]',
      props.class,
    )"
  />
</template>
