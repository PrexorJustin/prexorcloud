<script setup lang="ts">
defineProps<{
  title: string
  titleAccent?: string
  description?: string
  eyebrow?: string
}>()
</script>

<template>
  <div class="flex items-start justify-between gap-4">
    <div class="min-w-0">
      <p v-if="eyebrow || $slots.eyebrow" class="eyebrow mb-2">
        <slot name="eyebrow">{{ eyebrow }}</slot>
      </p>
      <h1
        class="font-display font-semibold leading-tight"
        style="font-size: var(--text-3xl); letter-spacing: var(--tracking-tight)"
      >
        {{ title }}<template v-if="titleAccent || $slots.titleAccent">
          <span> </span>
          <em class="accent-serif"><slot name="titleAccent">{{ titleAccent }}</slot></em>
        </template>
      </h1>
      <p v-if="description" class="mt-1 text-sm text-muted-foreground max-w-2xl">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="flex items-center gap-2 shrink-0">
      <slot name="actions" />
    </div>
  </div>
</template>
