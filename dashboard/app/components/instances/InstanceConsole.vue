<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue"
import { Send, Terminal as TerminalIcon } from "lucide-vue-next"
import { Terminal } from "@xterm/xterm"
import { FitAddon } from "@xterm/addon-fit"
import { WebLinksAddon } from "@xterm/addon-web-links"
import "@xterm/xterm/css/xterm.css"
import { StatusDot } from "~/components/ui/status-dot"
import { getAuthToken } from "~/lib/auth-storage"

const props = defineProps<{
  instanceId: string
}>()

const containerRef = ref<HTMLDivElement | null>(null)
const command = ref("")
const commandHistory = ref<string[]>([])
const historyIndex = ref(-1)
const connected = ref(false)

let term: Terminal | null = null
let fitAddon: FitAddon | null = null
let resizeObserver: ResizeObserver | null = null
let eventSource: EventSource | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let historyLoaded = false

function buildTerminal(): Terminal {
  return new Terminal({
    cursorBlink: false,
    cursorStyle: "block",
    disableStdin: true,
    convertEol: true,
    scrollback: 5000,
    fontFamily: "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace",
    fontSize: 13,
    lineHeight: 1.4,
    theme: {
      background: "#00000000",
      foreground: "#e2e8f0",
      cursor: "#06b6d4",
      cursorAccent: "#0a0a12",
      selectionBackground: "rgba(6, 182, 212, 0.3)",
      black:        "#0a0a12",
      red:          "#ef4444",
      green:        "#10b981",
      yellow:       "#f59e0b",
      blue:         "#06b6d4",
      magenta:      "#8b5cf6",
      cyan:         "#22c5e0",
      white:        "#cbd5e1",
      brightBlack:  "#475569",
      brightRed:    "#f87171",
      brightGreen:  "#34d39c",
      brightYellow: "#fbbf24",
      brightBlue:   "#22c5e0",
      brightMagenta:"#a07ff8",
      brightCyan:   "#67e2f5",
      brightWhite:  "#f8fafc",
    },
  })
}

async function loadHistory() {
  if (historyLoaded || !term) return
  historyLoaded = true
  try {
    const res = await useApiClient().GET('/api/v1/services/{id}/console/history', {
      params: { path: { id: props.instanceId }, query: { limit: 2000 } },
    })
    const lines = res.data?.lines ?? []
    if (lines.length === 0) return
    for (const entry of lines) {
      if (entry?.line != null) term.writeln(entry.line)
    }
    term.writeln('[2m── live stream connected ──[0m')
  } catch {
    // History is best-effort — fall through and rely on the SSE replay buffer.
  }
}

async function connect() {
  const token = getAuthToken()
  if (!token) return

  await loadHistory()

  const config = useRuntimeConfig()
  const apiBase = config.public.apiBase as string

  let url: string
  try {
    const res = await $fetch<{ ticket: string }>(`${apiBase}/api/v1/events/ticket`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    })
    url = `${apiBase}/api/v1/services/${props.instanceId}/console?ticket=${res.ticket}`
  } catch {
    url = `${apiBase}/api/v1/services/${props.instanceId}/console?token=${token}`
  }

  eventSource = new EventSource(url)

  eventSource.onopen = () => {
    connected.value = true
  }

  eventSource.onmessage = (event) => {
    if (!term) return
    term.writeln(event.data)
  }

  eventSource.onerror = () => {
    connected.value = false
    disconnect()
    reconnectTimer = setTimeout(connect, 3000)
  }
}

function disconnect() {
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
  if (eventSource) { eventSource.close(); eventSource = null }
}

async function sendCommand() {
  const cmd = command.value.trim()
  if (!cmd) return
  try {
    await useApiClient().POST('/api/v1/services/{id}/command', {
      params: { path: { id: props.instanceId } },
      body: { command: cmd },
    })
    commandHistory.value.push(cmd)
    if (commandHistory.value.length > 50) commandHistory.value.shift()
    historyIndex.value = -1
    command.value = ""
  } catch {
    // Server-side errors will surface in the console output stream.
  }
}

function onKeyDown(e: KeyboardEvent) {
  if (e.key === "ArrowUp") {
    e.preventDefault()
    if (commandHistory.value.length === 0) return
    if (historyIndex.value === -1) {
      historyIndex.value = commandHistory.value.length - 1
    } else if (historyIndex.value > 0) {
      historyIndex.value--
    }
    command.value = commandHistory.value[historyIndex.value] ?? ""
  } else if (e.key === "ArrowDown") {
    e.preventDefault()
    if (historyIndex.value === -1) return
    if (historyIndex.value < commandHistory.value.length - 1) {
      historyIndex.value++
      command.value = commandHistory.value[historyIndex.value] ?? ""
    } else {
      historyIndex.value = -1
      command.value = ""
    }
  }
}

onMounted(() => {
  if (!containerRef.value) return
  term = buildTerminal()
  fitAddon = new FitAddon()
  term.loadAddon(fitAddon)
  term.loadAddon(new WebLinksAddon())
  term.open(containerRef.value)
  fitAddon.fit()

  resizeObserver = new ResizeObserver(() => fitAddon?.fit())
  resizeObserver.observe(containerRef.value)

  connect()
})

onUnmounted(() => {
  disconnect()
  resizeObserver?.disconnect()
  resizeObserver = null
  term?.dispose()
  term = null
  fitAddon = null
})
</script>

<template>
  <div class="flex h-96 flex-col overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
    <div class="flex shrink-0 items-center justify-between border-b border-glass-border px-5 py-3">
      <div class="flex items-center gap-2.5">
        <TerminalIcon class="size-4 text-muted-foreground" />
        <span class="text-sm font-semibold">Console</span>
      </div>
      <div class="flex items-center gap-2">
        <StatusDot :tone="connected ? 'success' : 'muted'" :pulse="connected" size="sm" />
        <span class="text-xs text-muted-foreground">{{ connected ? 'Connected' : 'Disconnected' }}</span>
      </div>
    </div>

    <div ref="containerRef" class="instance-console-host min-h-0 flex-1 px-3 py-2" />

    <div class="flex shrink-0 items-center gap-3 border-t border-glass-border bg-glass/30 px-5 py-3">
      <span class="select-none mono text-xs font-bold text-primary">&gt;</span>
      <input
        v-model="command"
        type="text"
        placeholder="Enter command…"
        class="flex-1 bg-transparent mono text-xs text-foreground placeholder:text-muted-foreground/40 focus:outline-none"
        @keydown.enter="sendCommand"
        @keydown="onKeyDown"
      >
      <button
        type="button"
        :disabled="!command.trim()"
        aria-label="Send command"
        class="flex size-7 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-primary/10 hover:text-primary disabled:opacity-30 disabled:hover:bg-transparent disabled:hover:text-muted-foreground"
        @click="sendCommand"
      >
        <Send class="size-3.5" />
      </button>
    </div>
  </div>
</template>

<style>
.instance-console-host .xterm {
  height: 100%;
  padding: 0;
}
.instance-console-host .xterm-viewport {
  background-color: transparent !important;
}
</style>
