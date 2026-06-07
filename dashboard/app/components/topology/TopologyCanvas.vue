<script setup lang="ts">
import { computed, markRaw, onMounted, ref, watch } from "vue"
import type { Node } from "@vue-flow/core"
import { VueFlow, useVueFlow } from "@vue-flow/core"
import { Background } from "@vue-flow/background"
import { Controls } from "@vue-flow/controls"
import { MiniMap } from "@vue-flow/minimap"
import "@vue-flow/core/dist/style.css"
import "@vue-flow/core/dist/theme-default.css"
import "@vue-flow/controls/dist/style.css"
import "@vue-flow/minimap/dist/style.css"
import NodeBox, { type NodeBoxData } from "./NodeBox.vue"
import type { ConnectedNode, NodeEntry, ServerInstance } from "~/types/api"

/**
 * Cluster topology canvas. Renders one custom NodeBox per cluster node,
 * arranged in a row by default. Drag-positions persist to localStorage so
 * the operator's hand-arranged layout sticks across reloads.
 */
const props = defineProps<{
  nodes: NodeEntry[]
  instances: ServerInstance[]
  /** History buffers keyed by node id, fed by the live SSE-driven store. */
  cpuHistory?: Record<string, number[]>
  /** Optional filter — only nodes whose group set intersects the filter. */
  groupFilter?: Set<string>
}>()

const emit = defineEmits<{
  "instance-click": [instanceId: string]
}>()

const STORAGE_KEY = "prexor:topology:positions"

function readPositions(): Record<string, { x: number; y: number }> {
  if (typeof localStorage === "undefined") return {}
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as Record<string, { x: number; y: number }>
    return typeof parsed === "object" && parsed !== null ? parsed : {}
  } catch { return {} }
}

function writePositions(p: Record<string, { x: number; y: number }>) {
  if (typeof localStorage === "undefined") return
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(p)) } catch { /* ignore */ }
}

const positions = ref<Record<string, { x: number; y: number }>>(readPositions())

function defaultPosition(i: number, _total: number) {
  // Wrap to a new row every 4 nodes so 12+ node clusters don't shoot off-screen.
  const COL_W = 320
  const ROW_H = 380
  const col = i % 4
  const row = Math.floor(i / 4)
  return { x: 80 + col * COL_W, y: 80 + row * ROW_H }
}

const filteredNodes = computed(() => {
  if (!props.groupFilter || props.groupFilter.size === 0) return props.nodes
  return props.nodes.filter(n => {
    const ids = props.instances.filter(i => i.node === n.id).map(i => i.group)
    return ids.some(g => props.groupFilter!.has(g))
  })
})

const flowNodes = computed<Node[]>(() => {
  return filteredNodes.value.map((n, i) => {
    const isConnected = n.type === "CONNECTED"
    const conn = isConnected ? (n as ConnectedNode) : null
    const data: NodeBoxData = {
      nodeId: n.id,
      status: "status" in n ? n.status : "OFFLINE",
      address: conn?.address,
      cpuHistory: props.cpuHistory?.[n.id] ?? [],
      instances: props.instances
        .filter(i => i.node === n.id)
        .map(i => ({ id: i.id, group: i.group, state: i.state, playerCount: i.playerCount })),
    }
    const stored = positions.value[n.id]
    return {
      id: n.id,
      type: "node-box",
      position: stored ?? defaultPosition(i, filteredNodes.value.length),
      data,
      draggable: true,
    }
  })
})

// vue-flow's NodeTypesObject is a Record<string, Component>. Cast through
// `any` because `markRaw` strips the typed component shape.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const nodeTypes = { "node-box": markRaw(NodeBox) } as any

const { onNodeDragStop, fitView } = useVueFlow()

onNodeDragStop(({ node }) => {
  positions.value = { ...positions.value, [node.id]: { x: node.position.x, y: node.position.y } }
  writePositions(positions.value)
})

function resetLayout() {
  positions.value = {}
  writePositions(positions.value)
}

defineExpose({ resetLayout, fitView })

// Tell vue-flow to forward `instance-click` from custom node up to us.
function onNodeEvent(_id: string, instanceId: string) { emit("instance-click", instanceId) }

// Mount: fit the viewport to the rendered nodes once the initial layout
// is applied.
onMounted(() => {
  setTimeout(() => fitView({ padding: 0.15 }), 50)
})
watch(filteredNodes, () => {
  setTimeout(() => fitView({ padding: 0.15 }), 50)
})
</script>

<template>
  <VueFlow
    :nodes="flowNodes"
    :node-types="nodeTypes"
    :default-zoom="1"
    :min-zoom="0.3"
    :max-zoom="1.5"
    :nodes-connectable="false"
    :elements-selectable="true"
    class="topology-canvas size-full"
  >
    <Background pattern-color="var(--glass-border)" :gap="24" :size="1" />
    <Controls
      class="topology-controls"
      position="bottom-left"
      :show-zoom="true"
      :show-fit-view="true"
      :show-interactive="false"
    />
    <MiniMap
      pannable
      zoomable
      class="topology-minimap"
      :node-color="() => 'var(--glass)'"
      :node-stroke-color="() => 'var(--primary)'"
      mask-color="rgba(10,10,18,0.7)"
    />

    <!-- Custom slot listening for child-emitted `instance-click` -->
    <template #node-node-box="slotProps">
      <NodeBox v-bind="slotProps" @instance-click="(id) => onNodeEvent(slotProps.id, id)" />
    </template>
  </VueFlow>
</template>

<style>
/* Theme vue-flow's chrome to the design system. */
.topology-canvas .vue-flow__pane { background: transparent; }
.topology-canvas .vue-flow__renderer { background: transparent; }

.topology-controls.vue-flow__controls {
  display: flex;
  flex-direction: row;
  gap: 4px;
  background: color-mix(in srgb, var(--popover) 95%, transparent) !important;
  border: 1px solid var(--glass-border) !important;
  border-radius: 12px !important;
  padding: 4px !important;
  box-shadow: 0 4px 16px rgba(0,0,0,0.2);
}
.topology-controls.vue-flow__controls .vue-flow__controls-button {
  background: transparent !important;
  border: none !important;
  color: var(--muted-foreground) !important;
  width: 28px !important;
  height: 28px !important;
  border-radius: 8px !important;
}
.topology-controls.vue-flow__controls .vue-flow__controls-button:hover {
  background: var(--glass-hover) !important;
  color: var(--foreground) !important;
}
.topology-controls.vue-flow__controls .vue-flow__controls-button svg {
  fill: currentColor !important;
}

.topology-minimap.vue-flow__minimap {
  background: color-mix(in srgb, var(--popover) 95%, transparent) !important;
  border: 1px solid var(--glass-border) !important;
  border-radius: 12px !important;
}
</style>
