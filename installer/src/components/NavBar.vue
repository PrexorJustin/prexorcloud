<script setup lang="ts">
import { useWizardStore, type WizardStep } from '@/stores/wizard';

const props = defineProps<{
  back?: WizardStep;
  next?: WizardStep;
  disableNext?: boolean;
  hideNext?: boolean;
}>();

const wiz = useWizardStore();

function go(target: WizardStep | undefined) {
  if (target) wiz.setStep(target);
}

function restart() {
  wiz.reset();
}
</script>

<template>
  <div class="navbar">
    <button v-if="back" class="btn ghost" type="button" @click="go(props.back)">← Back</button>
    <span class="grow"></span>
    <button
      v-if="!hideNext && next"
      class="btn primary"
      type="button"
      :disabled="disableNext"
      @click="go(props.next)"
    >Continue →</button>
    <button v-if="hideNext" class="btn ghost" type="button" @click="restart">Start over</button>
  </div>
</template>
