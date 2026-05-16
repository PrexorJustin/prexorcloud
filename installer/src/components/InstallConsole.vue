<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import { useWizardStore } from '@/stores/wizard';
import { onTerminalData } from '@/lib/terminalBus';

const wiz = useWizardStore();

const termHost = ref<HTMLElement | null>(null);
let term: Terminal | null = null;
let fit: FitAddon | null = null;
let unsubscribe: (() => void) | null = null;
let resizeObserver: ResizeObserver | null = null;

// A live clock so active-phase durations and the overall elapsed counter tick
// in real time. Only runs while an install is in flight.
const now = ref(Date.now());
let ticker: number | null = null;
function startTicker() {
  if (ticker == null) ticker = window.setInterval(() => (now.value = Date.now()), 250);
}
function stopTicker() {
  if (ticker != null) {
    window.clearInterval(ticker);
    ticker = null;
  }
}

function fmtDuration(ms: number): string {
  if (ms < 0) ms = 0;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  return `${m}m ${String(s % 60).padStart(2, '0')}s`;
}

function phaseDuration(p: { startedAt: number; endedAt: number | null }): string {
  return fmtDuration((p.endedAt ?? now.value) - p.startedAt);
}

const elapsed = computed(() => {
  if (wiz.installStartedAt == null) return '0s';
  return fmtDuration((wiz.installFinishedAt ?? now.value) - wiz.installStartedAt);
});

const overall = computed<'running' | 'done' | 'failed'>(() => {
  if (wiz.busy) return 'running';
  if (wiz.installError) return 'failed';
  if (wiz.installDone) return 'done';
  return 'running';
});

onMounted(() => {
  if (!termHost.value) return;
  term = new Terminal({
    convertEol: true, // piped (non-PTY) output is \n-only; render it as CRLF
    cursorBlink: false,
    disableStdin: true,
    fontFamily: "'JetBrains Mono', ui-monospace, Menlo, Monaco, monospace",
    fontSize: 12,
    lineHeight: 1.25,
    scrollback: 8000,
    theme: {
      background: '#0a0a10',
      foreground: '#c5c5cf',
      cursor: '#4ec5d4',
      selectionBackground: 'rgba(78,197,212,0.25)',
      black: '#14141d',
      brightBlack: '#4a4a55',
      red: '#f43f5e',
      green: '#10b981',
      yellow: '#f59e0b',
      blue: '#4ec5d4',
      magenta: '#a78bfa',
      cyan: '#4ec5d4',
      white: '#c5c5cf',
      brightWhite: '#ededf2',
    },
  });
  fit = new FitAddon();
  term.loadAddon(fit);
  term.open(termHost.value);
  fit.fit();

  // Replay buffer + live chunks straight to the terminal.
  unsubscribe = onTerminalData((chunk) => term?.write(chunk));

  resizeObserver = new ResizeObserver(() => {
    try {
      fit?.fit();
    } catch {
      // fit throws if the host is detached mid-resize — safe to ignore.
    }
  });
  resizeObserver.observe(termHost.value);

  if (wiz.busy) startTicker();
});

// Run the live clock only while an install is in flight; freeze on the final
// value when it ends so durations stop counting.
watch(
  () => wiz.busy,
  (busy) => {
    if (busy) startTicker();
    else {
      now.value = Date.now();
      stopTicker();
    }
  },
);

onBeforeUnmount(() => {
  stopTicker();
  unsubscribe?.();
  resizeObserver?.disconnect();
  term?.dispose();
  term = null;
});
</script>

<template>
  <div class="install-console" :class="overall">
    <div class="ic-head">
      <span class="ic-status" :class="overall">
        <span v-if="overall === 'running'" class="ic-spinner" />
        <span v-else-if="overall === 'done'">✓</span>
        <span v-else>✗</span>
      </span>
      <span class="ic-title">
        <template v-if="overall === 'running'">Installing on this VPS…</template>
        <template v-else-if="overall === 'done'">Install complete</template>
        <template v-else>Install failed</template>
      </span>
      <span class="ic-elapsed mono">{{ elapsed }}</span>
    </div>

    <div class="ic-grid">
      <!-- Phase timeline -->
      <ol class="ic-timeline">
        <li
          v-for="(p, i) in wiz.installPhases"
          :key="i"
          class="ic-phase"
          :class="p.status"
        >
          <span class="ic-marker">
            <span v-if="p.status === 'active'" class="ic-spinner sm" />
            <span v-else-if="p.status === 'done'">✓</span>
            <span v-else>✗</span>
          </span>
          <span class="ic-phase-label">
            {{ p.label }}
            <span v-if="p.status === 'failed' && p.detail" class="ic-phase-detail">{{ p.detail }}</span>
          </span>
          <span class="ic-phase-dur mono">{{ phaseDuration(p) }}</span>
        </li>
        <li v-if="!wiz.installPhases.length" class="ic-phase placeholder">
          <span class="ic-marker"><span class="ic-spinner sm" /></span>
          <span class="ic-phase-label">Starting…</span>
        </li>
      </ol>

      <!-- Live VPS terminal -->
      <div class="ic-term-wrap">
        <div class="ic-term-bar">
          <span class="ic-dots"><i /><i /><i /></span>
          <span class="mono">root@vps · live console</span>
        </div>
        <div ref="termHost" class="ic-term" />
      </div>
    </div>

    <div v-if="wiz.installNextSteps.length" class="ic-next">
      <div class="ic-next-eyebrow">Next steps</div>
      <div v-for="(s, i) in wiz.installNextSteps" :key="i" class="ic-next-line mono">{{ s }}</div>
    </div>
  </div>
</template>
