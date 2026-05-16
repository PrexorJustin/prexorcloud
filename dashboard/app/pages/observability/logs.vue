<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue"
import { FileText, Pause, Play, Trash2, Download, Share2 } from "lucide-vue-next"
import { Terminal } from "@xterm/xterm"
import { FitAddon } from "@xterm/addon-fit"
import "@xterm/xterm/css/xterm.css"
import { Button } from "~/components/ui/button"
import { StatusDot } from "~/components/ui/status-dot"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select"
import { Callout, CalloutTitle } from "~/components/ui/callout"
import { getAuthToken } from "~/lib/auth-storage"

/**
 * Controller + node log viewer. Source is selected via the toolbar:
 *   • `controller` → /api/v1/system/logs/stream
 *   • `node-<id>`  → /api/v1/nodes/{id}/logs/stream
 *
 * Both use the SSE ticket-auth pattern already wired in InstanceConsole.
 * Follow-tail keeps the scroll pinned to the bottom; pausing detaches from
 * the live stream without disconnecting.
 */
const route = useRoute()
const nodesStore = useNodesStore()
const systemStore = useSystemStore()
const shareStore = useShareStore()
const { t } = useI18n()

const source = ref<string>(typeof route.query.nodeId === 'string' ? `node-${route.query.nodeId}` : 'controller')
const followTail = ref(true)
const connected = ref(false)
const paused = ref(false)
const sharing = ref(false)
const shareEnabled = computed(() => (systemStore.settings as { shareEnabled?: boolean })?.shareEnabled === true)

async function shareCurrentLogs() {
  if (sharing.value) return
  sharing.value = true
  try {
    if (source.value === 'controller') {
      await shareStore.shareControllerLogs({})
    } else {
      const nodeId = source.value.replace(/^node-/, '')
      await shareStore.shareDaemonLogs(nodeId, {})
    }
  } finally {
    sharing.value = false
  }
}

onMounted(() => {
  nodesStore.fetchNodes()
  if (Object.keys(systemStore.settings).length === 0) systemStore.fetchAll()
})

const containerRef = ref<HTMLDivElement | null>(null)
let term: Terminal | null = null
let fitAddon: FitAddon | null = null
let eventSource: EventSource | null = null
let resizeObserver: ResizeObserver | null = null

function buildTerminal(): Terminal {
  return new Terminal({
    cursorBlink: false,
    disableStdin: true,
    convertEol: true,
    scrollback: 10_000,
    fontFamily: "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
    fontSize: 12,
    lineHeight: 1.45,
    theme: {
      background: "#00000000",
      foreground: "#cbd5e1",
      cursor: "#06b6d4",
      cursorAccent: "#0a0a12",
      selectionBackground: "rgba(6,182,212,0.3)",
      black: "#0a0a12", red: "#ef4444", green: "#10b981", yellow: "#f59e0b",
      blue: "#06b6d4", magenta: "#8b5cf6", cyan: "#22c5e0", white: "#cbd5e1",
      brightBlack: "#475569", brightRed: "#f87171", brightGreen: "#34d39c", brightYellow: "#fbbf24",
      brightBlue: "#22c5e0", brightMagenta: "#a07ff8", brightCyan: "#67e2f5", brightWhite: "#f8fafc",
    },
  })
}

function streamUrl(): string {
  const apiBase = useRuntimeConfig().public.apiBase as string
  const token = getAuthToken() ?? ''
  if (source.value === 'controller') {
    return `${apiBase}/api/v1/system/logs/stream?token=${token}`
  }
  const nodeId = source.value.replace(/^node-/, '')
  return `${apiBase}/api/v1/nodes/${encodeURIComponent(nodeId)}/logs/stream?token=${token}`
}

function disconnect() {
  if (eventSource) { eventSource.close(); eventSource = null }
  connected.value = false
}

function connect() {
  if (paused.value) return
  disconnect()
  const url = streamUrl()
  eventSource = new EventSource(url)
  eventSource.onopen = () => { connected.value = true }
  eventSource.onerror = () => { connected.value = false }
  eventSource.onmessage = (ev) => {
    if (!term) return
    term.writeln(ev.data)
    if (followTail.value) term.scrollToBottom()
  }
}

watch(source, () => {
  term?.clear()
  connect()
})

watch(paused, (p) => {
  if (p) disconnect()
  else connect()
})

onMounted(() => {
  if (!containerRef.value) return
  term = buildTerminal()
  fitAddon = new FitAddon()
  term.loadAddon(fitAddon)
  term.open(containerRef.value)
  fitAddon.fit()
  resizeObserver = new ResizeObserver(() => fitAddon?.fit())
  resizeObserver.observe(containerRef.value)
  connect()
})

onUnmounted(() => {
  disconnect()
  resizeObserver?.disconnect()
  term?.dispose()
})

function clearBuffer() {
  term?.clear()
}

function exportBuffer() {
  if (!term) return
  let content = ""
  for (let i = 0; i < term.buffer.active.length; i++) {
    content += (term.buffer.active.getLine(i)?.translateToString() ?? "") + "\n"
  }
  const blob = new Blob([content], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${source.value}-${new Date().toISOString()}.log`
  a.click()
  URL.revokeObjectURL(url)
}

const sourceOptions = computed(() => {
  const opts = [{ value: 'controller', label: t('pages.logs.controller') }]
  for (const n of nodesStore.nodes) {
    opts.push({ value: `node-${n.id}`, label: t('pages.logs.nodeOption', { id: n.id }) })
  }
  return opts
})
</script>

<template>
  <div class="flex flex-1 flex-col gap-5">
    <PageHeader :title="t('pages.logs.title')" :description="t('pages.logs.description')">
      <template #actions>
        <Select v-model="source">
          <SelectTrigger class="w-56">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem v-for="o in sourceOptions" :key="o.value" :value="o.value">{{ o.label }}</SelectItem>
          </SelectContent>
        </Select>
        <Button variant="outline" size="sm" @click="paused = !paused">
          <component :is="paused ? Play : Pause" class="mr-1.5 size-3.5" />
          {{ paused ? t('pages.logs.resume') : t('pages.logs.pause') }}
        </Button>
        <Button variant="outline" size="sm" @click="exportBuffer">
          <Download class="mr-1.5 size-3.5" /> {{ t('pages.logs.export') }}
        </Button>
        <Button v-if="shareEnabled" variant="outline" size="sm" :disabled="sharing" @click="shareCurrentLogs">
          <Share2 class="mr-1.5 size-3.5" /> {{ t('pages.logs.share') }}
        </Button>
        <Button variant="outline" size="sm" @click="clearBuffer">
          <Trash2 class="mr-1.5 size-3.5" /> {{ t('pages.logs.clear') }}
        </Button>
      </template>
    </PageHeader>

    <Callout variant="info">
      <CalloutTitle>{{ t('pages.logs.scrollbackNote') }}</CalloutTitle>
      <template #next>For deeper history, fall back to <code class="mono">prexorctl logs</code> or the host-side journal until the backend ships a history API (see <code class="mono">docs/dashboard-backend-gaps.md §5</code>).</template>
    </Callout>

    <div class="flex h-[calc(100vh-260px)] min-h-[420px] flex-col overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
      <div class="flex shrink-0 items-center justify-between border-b border-glass-border px-5 py-3">
        <div class="flex items-center gap-2.5">
          <FileText class="size-4 text-muted-foreground" />
          <span class="text-sm font-medium">{{ sourceOptions.find(o => o.value === source)?.label ?? source }}</span>
        </div>
        <div class="flex items-center gap-3">
          <label class="flex items-center gap-1.5 text-xs text-muted-foreground">
            <input v-model="followTail" type="checkbox" class="size-3 rounded border-glass-border bg-glass/60" >
            {{ t('pages.logs.followTail') }}
          </label>
          <div class="flex items-center gap-2">
            <StatusDot :tone="connected && !paused ? 'success' : paused ? 'warning' : 'muted'" :pulse="connected && !paused" size="sm" />
            <span class="text-xs text-muted-foreground">
              {{ paused ? t('pages.logs.statusPaused') : connected ? t('pages.logs.statusConnected') : t('pages.logs.statusDisconnected') }}
            </span>
          </div>
        </div>
      </div>

      <div ref="containerRef" class="logs-host min-h-0 flex-1 px-3 py-2" />
    </div>
  </div>
</template>

<style>
.logs-host .xterm { height: 100%; padding: 0; }
.logs-host .xterm-viewport { background-color: transparent !important; }
</style>
