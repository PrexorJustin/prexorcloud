<script setup lang="ts">
import { computed } from 'vue';
import { useWizardStore } from '@/stores/wizard';

const props = defineProps<{
  label: string;
  keyPath: string;
  required?: boolean;
  help?: string;
}>();

const wiz = useWizardStore();
const helpOpen = computed(() => wiz.helpOpen === props.keyPath);

function toggleHelp() {
  wiz.toggleHelp(props.keyPath);
}
</script>

<template>
  <div class="field">
    <div class="field-label">
      <span class="label">
        <span>{{ label }}</span>
        <span v-if="required" class="req">*</span>
        <slot name="badges" />
        <button
          v-if="help"
          class="field-help-btn"
          :class="{ active: helpOpen }"
          type="button"
          @click="toggleHelp"
        >?</button>
      </span>
      <span class="key">{{ keyPath }}</span>
      <div v-if="help && helpOpen" class="field-tooltip" v-html="help" />
    </div>
    <div><slot /></div>
  </div>
</template>
