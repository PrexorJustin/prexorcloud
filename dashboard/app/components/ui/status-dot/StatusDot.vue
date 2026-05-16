<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from "@/lib/utils"

export type StatusDotTone = "success" | "primary" | "warning" | "destructive" | "muted"

const props = withDefaults(defineProps<{
  tone?: StatusDotTone
  pulse?: boolean
  size?: "sm" | "md"
  class?: HTMLAttributes["class"]
}>(), {
  tone: "muted",
  pulse: false,
  size: "md",
})

const toneClass: Record<StatusDotTone, string> = {
  success:     "bg-success",
  primary:     "bg-primary",
  warning:     "bg-warning",
  destructive: "bg-destructive",
  muted:       "bg-muted-foreground/60",
}

const sizeClass = { sm: "size-1.5", md: "size-2" }
</script>

<template>
  <span
    :class="cn('relative inline-block rounded-full', sizeClass[props.size], toneClass[props.tone], props.class)"
    aria-hidden="true"
  >
    <span
      v-if="props.pulse"
      :class="cn('absolute inset-0 rounded-full opacity-75 animate-ping', toneClass[props.tone])"
    />
  </span>
</template>
