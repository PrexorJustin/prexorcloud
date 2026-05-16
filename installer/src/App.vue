<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useWizardStore, type WizardStep } from '@/stores/wizard';
import Mode from '@/screens/Mode.vue';
import Essentials from '@/screens/Essentials.vue';
import Security from '@/screens/Security.vue';
import Review from '@/screens/Review.vue';
import CliLogin from '@/screens/CliLogin.vue';

const wiz = useWizardStore();

const steps = [
  { id: 'mode', l1: 'Mode' },
  { id: 'essentials', l1: 'Essentials' },
  { id: 'security', l1: 'Security' },
  { id: 'review', l1: 'Review' },
] as const;

const stepIndex = computed(() => steps.findIndex((s) => s.id === wiz.step));

function stepState(i: number): 'done' | 'active' | 'upcoming' {
  if (i < stepIndex.value) return 'done';
  if (i === stepIndex.value) return 'active';
  return 'upcoming';
}

function gotoStep(i: number) {
  if (i > stepIndex.value + 1) return;
  wiz.setStep(steps[i].id as WizardStep);
}

// cli-login is a side flow off the Mode screen; the stepper is collapsed there
// (matches the legacy `noStepper` opt).
const showStepper = computed(() => wiz.step !== 'cli-login');

// Pull host-side defaults (ports + install dirs + cli-login availability) once
// at mount. Action is idempotent so HMR / remount can't clobber user edits.
onMounted(() => {
  void wiz.loadDefaults();
});
</script>

<template>
  <div v-if="wiz.step === 'mode'" class="glow glow-cyan"></div>
  <div class="shell" :class="{ 'no-stepper': !showStepper }">
    <div class="brand">
      <div class="brand-logo">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M17 18a5 5 0 0 0 0-10 7 7 0 0 0-13 3 4 4 0 0 0 1 7h12z" />
        </svg>
      </div>
      <span class="brand-name">PrexorCloud</span>
      <span class="brand-pill">installer</span>
      <span class="brand-grow"></span>
      <a class="brand-link" href="https://prexor.cloud/docs/getting-started/wizard" target="_blank" rel="noopener">Docs</a>
      <a class="brand-link" href="https://github.com/prexorjustin/prexorcloud" target="_blank" rel="noopener">Source</a>
      <span class="brand-profile" :class="wiz.profile === 'production' ? 'prod' : 'dev'">
        <span class="dot"></span>{{ wiz.profile === 'production' ? 'production' : 'development' }}
      </span>
      <span class="brand-version">v0.18</span>
    </div>

    <div v-if="showStepper" class="stepper-wrap">
      <div class="stepper">
        <template v-for="(s, i) in steps" :key="s.id">
          <div class="step" :class="stepState(i)" @click="gotoStep(i)">
            <div class="circle">{{ stepState(i) === 'done' ? '✓' : i + 1 }}</div>
            <div class="labels"><div class="l1">{{ s.l1 }}</div></div>
          </div>
          <div v-if="i < steps.length - 1" class="connector"></div>
        </template>
      </div>
    </div>

    <Mode v-if="wiz.step === 'mode'" />
    <Essentials v-else-if="wiz.step === 'essentials'" />
    <Security v-else-if="wiz.step === 'security'" />
    <Review v-else-if="wiz.step === 'review'" />
    <CliLogin v-else-if="wiz.step === 'cli-login'" />
  </div>
</template>
