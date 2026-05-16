<script setup lang="ts" generic="T extends string | boolean | null">
import { computed } from 'vue';

const model = defineModel<T>({ required: true });

const props = defineProps<{
  options: Array<T | [T, string]>;
}>();

interface NormalizedOption {
  raw: T;
  value: string;
  label: string;
}

// Selects only deal in strings on the DOM side, so encode T <-> string. We
// keep the raw T values around so the model lands back on the right runtime
// type (booleans for the signing-mode toggle, null for "auto by profile").
const items = computed<NormalizedOption[]>(() =>
  props.options.map((o) => {
    if (Array.isArray(o)) {
      return { raw: o[0], value: String(o[0]), label: o[1] };
    }
    return { raw: o, value: String(o), label: String(o) };
  }),
);

const proxy = computed({
  get: () => String(model.value),
  set: (v: string) => {
    const match = items.value.find((it) => it.value === v);
    if (match) model.value = match.raw;
  },
});
</script>

<template>
  <select v-model="proxy" class="select">
    <option v-for="it in items" :key="it.value" :value="it.value">{{ it.label }}</option>
  </select>
</template>
