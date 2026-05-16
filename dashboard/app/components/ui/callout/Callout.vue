<script setup lang="ts">
import type { Component, HTMLAttributes } from "vue"
import { computed } from "vue"
import { Info, CheckCircle2, AlertTriangle, AlertOctagon, ArrowRight } from "lucide-vue-next"
import { cn } from "@/lib/utils"

export type CalloutVariant = "info" | "ok" | "warning" | "error"

const props = withDefaults(defineProps<{
  variant?: CalloutVariant
  icon?: Component
  class?: HTMLAttributes["class"]
}>(), {
  variant: "info",
})

const VARIANT: Record<CalloutVariant, { icon: Component; surface: string; ring: string; iconClass: string }> = {
  info: {
    icon: Info,
    surface: "bg-primary/5",
    ring: "border-primary/30",
    iconClass: "text-primary",
  },
  ok: {
    icon: CheckCircle2,
    surface: "bg-success/5",
    ring: "border-success/30",
    iconClass: "text-success",
  },
  warning: {
    icon: AlertTriangle,
    surface: "bg-warning/5",
    ring: "border-warning/30",
    iconClass: "text-warning",
  },
  error: {
    icon: AlertOctagon,
    surface: "bg-destructive/5",
    ring: "border-destructive/30",
    iconClass: "text-destructive",
  },
}

const cfg = computed(() => VARIANT[props.variant])
const IconComp = computed(() => props.icon ?? cfg.value.icon)
</script>

<template>
  <div
    role="note"
    :class="cn('relative flex gap-3 rounded-xl border p-4 backdrop-blur-sm', cfg.surface, cfg.ring, props.class)"
  >
    <component :is="IconComp" :class="cn('mt-0.5 size-4 shrink-0', cfg.iconClass)" aria-hidden="true" />
    <div class="flex-1 space-y-1.5 text-sm">
      <slot />
      <div v-if="$slots.next" class="flex items-start gap-1.5 pt-1 text-muted-foreground">
        <ArrowRight class="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
        <span class="font-medium text-foreground/80">next:&nbsp;</span>
        <span class="flex-1"><slot name="next" /></span>
      </div>
    </div>
  </div>
</template>
