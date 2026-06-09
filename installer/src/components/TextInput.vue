<script setup lang="ts">
const model = defineModel<string>({ required: true });

defineProps<{
  placeholder?: string;
  invalid?: boolean;
  prose?: boolean;
  type?: 'text' | 'password' | 'url' | 'email';
  autocomplete?: string;
}>();
</script>

<template>
  <input
    v-model="model"
    :class="['input', prose ? 'prose' : '', invalid ? 'invalid' : '']"
    :type="type ?? 'text'"
    :placeholder="placeholder"
    :autocomplete="autocomplete"
  />
</template>
