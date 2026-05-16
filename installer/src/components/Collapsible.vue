<script setup lang="ts">
import { computed } from 'vue';
import { useWizardStore } from '@/stores/wizard';

const props = defineProps<{
  id: string;
  title: string;
  sub?: string;
}>();

const wiz = useWizardStore();
const open = computed(() => !!wiz.collapsibles[props.id]);

function toggle() {
  wiz.toggleCollapsible(props.id);
}
</script>

<template>
  <div class="collapsible" :class="{ open }">
    <button class="collapsible-head" type="button" @click="toggle">
      <span class="chev">▶</span>
      <span>
        <div class="title">{{ title }}</div>
        <div v-if="sub" class="sub">{{ sub }}</div>
      </span>
      <span style="flex:1"></span>
      <span class="show">{{ open ? 'Hide' : 'Show' }}</span>
    </button>
    <div class="collapsible-body">
      <slot />
    </div>
  </div>
</template>
