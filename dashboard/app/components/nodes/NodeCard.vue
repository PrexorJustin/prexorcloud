<script setup lang="ts">
import { Server, Cpu, MemoryStick, Box } from "lucide-vue-next"
import type { NodeEntry, ConnectedNode } from "~/types/api"
import { StatusBadge } from "~/components/ui/status-badge"
import { formatMemory } from "~/lib/utils"
import { NODE_STATUS_CONFIG } from "~/lib/constants"

const props = defineProps<{
  node: NodeEntry
  exploding?: boolean
}>()

const { t } = useI18n()

const isConnected = computed(() => props.node.type === "CONNECTED")
const connected = computed(() => (isConnected.value ? props.node as ConnectedNode : null))

const status = computed((): { label: string; color: string; dot: string } =>
  NODE_STATUS_CONFIG[props.node.status] ?? { label: t('components.nodeCard.unknown'), color: "text-muted-foreground", dot: "bg-muted-foreground" },
)


</script>

<template>
  <div
    :class="[
      'relative bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border hover:bg-glass-hover hover:border-glass-border-hover p-5 cursor-pointer transition-all duration-300 group select-none',
      exploding ? 'animate-card-poof pointer-events-none' : '',
    ]"
    @click="navigateTo(`/nodes/${node.id}`)"
  >
    <!-- Status gradient -->
    <div
class="absolute inset-0 bg-gradient-to-br from-transparent to-transparent opacity-30 rounded-2xl overflow-hidden"
      :class="node.status === 'ONLINE' ? 'from-success/10' : node.status === 'OFFLINE' ? 'from-muted/10' : 'from-warning/10'"
    />

    <div class="relative z-10">
      <!-- Header: Icon + Name + Status -->
      <div class="flex items-start justify-between mb-4">
        <div class="flex items-center gap-3">
          <div class="relative">
            <div class="size-10 rounded-xl bg-glass flex items-center justify-center">
              <Server class="size-5 text-muted-foreground" />
            </div>
            <!-- Status dot -->
            <div :class="['absolute -bottom-0.5 -right-0.5 size-3 rounded-full border-2 border-background', status.dot]" />
          </div>
          <div>
            <p class="font-semibold text-foreground">{{ node.id }}</p>
            <p class="text-xs text-muted-foreground">
              <template v-if="isConnected">{{ connected!.address }}</template>
              <template v-else-if="node.type === 'PENDING'">{{ t('components.nodeCard.awaitingConnection') }}</template>
              <template v-else>{{ t('components.nodeCard.disconnected') }}</template>
            </p>
          </div>
        </div>
        <StatusBadge :state="node.status" />
      </div>

      <!-- Metrics (connected nodes only) -->
      <template v-if="connected">
        <div class="grid grid-cols-3 gap-3 mt-3">
          <div class="flex items-center gap-2">
            <Cpu class="size-3.5 text-muted-foreground" />
            <span class="text-sm text-foreground tabular-nums">{{ connected.cpuUsage.toFixed(0) }}%</span>
          </div>
          <div class="flex items-center gap-2">
            <MemoryStick class="size-3.5 text-muted-foreground" />
            <span class="text-sm text-foreground tabular-nums">{{ formatMemory(connected.usedMemoryMb) }}</span>
          </div>
          <div class="flex items-center gap-2">
            <Box class="size-3.5 text-muted-foreground" />
            <span class="text-sm text-foreground tabular-nums">{{ connected.instanceCount }}</span>
          </div>
        </div>
      </template>

      <!-- Pending info -->
      <template v-else-if="node.type === 'PENDING'">
        <p class="text-xs text-muted-foreground mt-3">
          {{ t('components.nodeCard.expires', { date: new Date(node.expiresAt).toLocaleString() }) }}
        </p>
      </template>

      <!-- Disconnected info -->
      <template v-else-if="node.type === 'DISCONNECTED'">
        <p class="text-xs text-muted-foreground mt-3">
          {{ t('components.nodeCard.lastSeen', { date: new Date(node.lastSeen).toLocaleString() }) }}
        </p>
      </template>
    </div>
  </div>
</template>
