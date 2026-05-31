<script setup lang="ts">
import { computed } from 'vue';
import { useWizardStore, type WizardMode } from '@/stores/wizard';

const wiz = useWizardStore();

interface Mode {
  id: WizardMode;
  tag: string;
  name: string;
  desc: string;
}

const allModes: Mode[] = [
  { id: 'all',             tag: 'All-in-one',    name: 'Controller + Daemon', desc: 'Single host runs both. Best for evaluation and home labs.' },
  { id: 'controller',      tag: 'Control plane', name: 'Controller only',     desc: 'Daemons join later from other hosts. Recommended for production.' },
  { id: 'controller-join', tag: 'Join cluster',  name: 'Add controller',      desc: "Join this host to an existing controller cluster. Needs a join token from 'prexorctl cluster join-token create'." },
  { id: 'daemon',          tag: 'Join cluster',  name: 'Add daemon',          desc: 'Bring a node online for an existing controller. Needs a join token.' },
  { id: 'dashboard',       tag: 'Web UI',        name: 'Dashboard',           desc: 'Standalone frontend that talks to a remote controller over HTTPS.' },
  { id: 'cli',             tag: 'Sign in',       name: 'CLI login',           desc: "Bind this prexorctl to an existing cluster. No install — just point it at the controller and sign in." },
];

// The CLI-login card is hidden when /api/info reports the host wizard wasn't
// launched with an OnCliLogin callback — without it the resulting token has
// nowhere to land.
const modes = computed<Mode[]>(() =>
  wiz.cliLoginAvailable ? allModes : allModes.filter((m) => m.id !== 'cli'),
);

// Every non-cli mode runs an install against this host. On macOS/Windows
// (installSupported=false from /api/info) those cards are visibly disabled
// — see the table-shaped banner above the grid for the "why".
function isModeDisabled(id: WizardMode): boolean {
  return !wiz.installSupported && id !== 'cli';
}

const installBlockedReason = computed(() =>
  wiz.installUnsupportedReason ||
  "Server-side components run on Linux. On this host you can only sign this CLI into an existing cluster.",
);

const files = computed(() => wiz.filesForMode.join(' + '));

function selectMode(id: WizardMode) {
  if (isModeDisabled(id)) return;
  wiz.setMode(id);
}

function next() {
  wiz.advance();
}
</script>

<template>
  <div class="body">
    <div class="content-pane">
      <div class="welcome">
        <div class="eyebrow">Step 1 of 4</div>
        <h1>How do you want to <em class="accent-serif">run it?</em></h1>
        <p>This decides which configuration we generate. You can change it any time — controllers and daemons can be added to a cluster in either order.</p>
      </div>
      <div v-if="!wiz.installSupported" class="install-blocked-banner" role="status">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
        <span>{{ installBlockedReason }}</span>
      </div>
      <div class="mode-grid">
        <button
          v-for="m in modes"
          :key="m.id"
          class="mode-card"
          :class="{ selected: wiz.mode === m.id, disabled: isModeDisabled(m.id) }"
          :data-mode="m.id"
          :disabled="isModeDisabled(m.id)"
          :aria-disabled="isModeDisabled(m.id) ? 'true' : undefined"
          :title="isModeDisabled(m.id) ? installBlockedReason : ''"
          type="button"
          @click="selectMode(m.id)"
        >
          <span class="mode-radio"></span>
          <div class="mode-body">
            <div class="mode-head">
              <span class="mode-name">{{ m.name }}</span>
              <span class="mode-tag">{{ m.tag }}</span>
            </div>
            <div class="mode-desc">{{ m.desc }}</div>
            <div v-if="isModeDisabled(m.id)" class="mode-disabled-reason">Requires Linux</div>
          </div>
        </button>
      </div>
      <div class="continue-bar">
        <div class="info-icon">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
        </div>
        <div class="copy">
          <template v-if="wiz.mode === 'cli'">
            You'll just enter a controller URL + credentials — no install, no files touched on this host.
          </template>
          <template v-else>
            You'll spend ~3 minutes here. We'll generate <code>{{ files }}</code> at the end.
          </template>
        </div>
        <button class="btn primary" type="button" @click="next">
          Continue <span style="margin-left: 2px">→</span>
        </button>
      </div>
    </div>
  </div>
</template>
