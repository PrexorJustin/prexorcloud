<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { computed } from "vue"
import { cn } from "@/lib/utils"
import StatusDot, { type StatusDotTone } from "../status-dot/StatusDot.vue"

/**
 * Maps API state values (instance/node) to dot tone + display label.
 * Single source of truth — pages should never hand-roll status badges.
 */
const STATE_MAP: Record<string, { tone: StatusDotTone; label: string }> = {
  // Instances
  RUNNING:    { tone: "success",     label: "Running" },
  STARTING:   { tone: "primary",     label: "Starting" },
  SCHEDULED:  { tone: "primary",     label: "Scheduled" },
  PENDING:    { tone: "primary",     label: "Pending" },
  STOPPING:   { tone: "warning",     label: "Stopping" },
  STOPPED:    { tone: "muted",       label: "Stopped" },
  CRASHED:    { tone: "destructive", label: "Crashed" },
  // Nodes
  ONLINE:      { tone: "success",     label: "Online" },
  OFFLINE:     { tone: "muted",       label: "Offline" },
  DRAINING:    { tone: "warning",     label: "Draining" },
  CORDONED:    { tone: "warning",     label: "Cordoned" },
  UNREACHABLE: { tone: "destructive", label: "Unreachable" },
}

const props = withDefaults(defineProps<{
  state?: string
  tone?: StatusDotTone
  label?: string
  pulse?: boolean
  class?: HTMLAttributes["class"]
}>(), {
  pulse: false,
})

const resolved = computed(() => {
  if (props.state) {
    const hit = STATE_MAP[props.state.toUpperCase()]
    if (hit) return hit
  }
  return { tone: props.tone ?? "muted", label: props.label ?? props.state ?? "Unknown" }
})
</script>

<template>
  <span
    :class="cn('inline-flex items-center gap-2 rounded-full border border-glass-border bg-glass/60 px-2.5 py-0.5 text-xs font-medium tabular', props.class)"
  >
    <StatusDot :tone="resolved.tone" :pulse="props.pulse" size="sm" />
    <span>{{ props.label ?? resolved.label }}</span>
  </span>
</template>
