<script setup lang="ts">
const model = defineModel<boolean>({ required: true });

defineProps<{
  label?: string;
}>();

function toggle() {
  model.value = !model.value;
}
</script>

<template>
  <button type="button" class="toggle" :class="{ on: model }" @click="toggle">
    <span class="pill"></span>
    <span
      v-if="label"
      :style="{ fontSize: '13px', color: model ? 'var(--foreground)' : 'var(--muted-foreground)' }"
    >
      {{ label }}
    </span>
  </button>
</template>
