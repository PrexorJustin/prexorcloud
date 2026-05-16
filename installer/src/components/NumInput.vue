<script setup lang="ts">
import { computed } from 'vue';

const model = defineModel<number>({ required: true });

defineProps<{
  invalid?: boolean;
  suffix?: string;
}>();

// Bind through a string proxy so users can clear the field while editing;
// empty becomes 0 to keep the store's numeric field type honest.
const proxy = computed({
  get: () => String(model.value),
  set: (v: string) => {
    if (v === '') {
      model.value = 0;
      return;
    }
    const n = Number(v);
    model.value = Number.isFinite(n) ? n : 0;
  },
});
</script>

<template>
  <div v-if="suffix" class="input-suffix">
    <input
      v-model="proxy"
      :class="['input', invalid ? 'invalid' : '']"
      inputmode="numeric"
    />
    <span class="suffix">{{ suffix }}</span>
  </div>
  <input
    v-else
    v-model="proxy"
    :class="['input', invalid ? 'invalid' : '']"
    inputmode="numeric"
  />
</template>
