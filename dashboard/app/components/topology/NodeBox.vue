<script setup lang="ts">
import { Server, Cpu, Box } from "lucide-vue-next"
import { Handle, Position } from "@vue-flow/core"
import { StatusBadge } from "~/components/ui/status-badge"
import { StatusDot, type StatusDotTone } from "~/components/ui/status-dot"
import { Sparkline } from "~/components/ui/sparkline"

/**
 * Custom vue-flow node type that renders a cluster node as a glass-card
 * containing stacked instance pills. Clicking an instance pill emits the
 * `instance-click` event so the parent canvas can open the DetailSheet.
 *
 * vue-flow passes node data via a `data` prop; we shape it as
 * `NodeBoxData` below. Handles are present on left/right so future PRs can
 * add network-routing edges without a re-architect.
 */
export interface NodeBoxInstance {
  id: string
  group: string
  state: string
  playerCount: number
}

export interface NodeBoxData {
  nodeId: string
  status: string
  address?: string
  cpuHistory?: number[]
  instances: NodeBoxInstance[]
}

const props = defineProps<{ data: NodeBoxData }>()

const emit = defineEmits<{
  "instance-click": [instanceId: string]
}>()

function instanceTone(state: string): StatusDotTone {
  if (state === "RUNNING") return "success"
  if (state === "STARTING" || state === "SCHEDULED") return "primary"
  if (state === "DRAINING" || state === "STOPPING") return "warning"
  if (state === "CRASHED") return "destructive"
  return "muted"
}

function nodeTone(status: string): "success" | "warning" | "destructive" | "muted" {
  if (status === "ONLINE") return "success"
  if (status === "DRAINING" || status === "CORDONED") return "warning"
  if (status === "UNREACHABLE") return "destructive"
  return "muted"
}
</script>

<template>
  <div class="w-72 overflow-hidden rounded-2xl border border-glass-border bg-glass/80 backdrop-blur-xl shadow-lg shadow-black/20">
    <Handle :position="Position.Left" type="target" class="!bg-primary !border-glass-border" />
    <Handle :position="Position.Right" type="source" class="!bg-primary !border-glass-border" />

    <!-- Header -->
    <div class="flex items-center gap-2 border-b border-glass-border bg-glass/40 px-4 py-3">
      <Server class="size-4 text-muted-foreground" />
      <div class="min-w-0 flex-1">
        <p class="truncate text-sm font-medium mono">{{ props.data.nodeId }}</p>
        <p v-if="props.data.address" class="truncate mono text-[10px] text-muted-foreground">{{ props.data.address }}</p>
      </div>
      <StatusBadge :state="props.data.status" :pulse="props.data.status === 'ONLINE'" />
    </div>

    <!-- CPU sparkline -->
    <div v-if="props.data.cpuHistory?.length" class="border-b border-glass-border px-4 py-2">
      <div class="mb-1 flex items-center gap-1.5 text-[10px] text-muted-foreground">
        <Cpu class="size-3" /> CPU
      </div>
      <Sparkline :data="props.data.cpuHistory" tone="primary" :height="24" />
    </div>

    <!-- Instances -->
    <div class="space-y-1 p-3">
      <button
        v-for="inst in props.data.instances"
        :key="inst.id"
        type="button"
        class="group/inst flex w-full items-center gap-2 rounded-md border border-glass-border bg-glass/40 px-2 py-1.5 text-left transition-colors hover:bg-glass-hover"
        @click.stop="emit('instance-click', inst.id)"
      >
        <Box class="size-3 shrink-0 text-muted-foreground" />
        <StatusDot :tone="instanceTone(inst.state)" :pulse="inst.state === 'RUNNING'" size="sm" />
        <span class="truncate mono text-xs">{{ inst.id }}</span>
        <span class="ml-auto shrink-0 tabular text-[10px] text-muted-foreground">{{ inst.playerCount }}</span>
      </button>
      <p
        v-if="props.data.instances.length === 0"
        class="py-3 text-center text-xs text-muted-foreground"
      >
        No instances
      </p>
    </div>
  </div>
</template>

<style>
/* Tone down vue-flow's handle dots — they fight glass borders otherwise. */
.vue-flow__handle {
  width: 6px !important;
  height: 6px !important;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.vue-flow__node:hover .vue-flow__handle { opacity: 0.6; }
</style>
