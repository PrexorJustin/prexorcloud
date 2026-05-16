<script setup lang="ts">
/**
 * Tiny helper that lifts the cmdk filterState.search value out of the Command
 * context. Useful when the parent needs to react to the input (e.g. resource
 * search in the palette) without binding a second v-model on CommandInput.
 *
 *   <CommandDialog>
 *     <CommandInput />
 *     <CommandQueryProbe @update:query="onQuery" />
 *     ...
 *   </CommandDialog>
 */
import { watch } from "vue"
import { useCommand } from "."

const emit = defineEmits<{ "update:query": [value: string] }>()
const ctx = useCommand()

watch(
  () => ctx.filterState.search,
  (next) => emit("update:query", next),
  { immediate: true },
)
</script>

<template>
  <span hidden aria-hidden="true" />
</template>
