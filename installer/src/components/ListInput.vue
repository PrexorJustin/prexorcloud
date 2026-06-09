<script setup lang="ts">
const model = defineModel<string[]>({ required: true });

defineProps<{
  placeholder?: string;
}>();

function update(i: number, v: string) {
  const next = [...model.value];
  next[i] = v;
  model.value = next;
}

function remove(i: number) {
  const next = [...model.value];
  next.splice(i, 1);
  model.value = next;
}

function add() {
  model.value = [...model.value, ''];
}
</script>

<template>
  <div class="list-input">
    <div v-for="(v, i) in model" :key="i" class="list-row">
      <input
        class="input"
        :value="v"
        :placeholder="placeholder"
        @input="update(i, ($event.target as HTMLInputElement).value)"
      />
      <button class="icon-btn" type="button" title="Remove" @click="remove(i)">×</button>
    </div>
    <button class="add-btn" type="button" @click="add">+ Add</button>
  </div>
</template>
