<script setup lang="ts">
import { ChevronRight } from "lucide-vue-next"

export interface ChainNode {
  name: string
  label?: string
  active?: boolean
  link?: string
}

defineProps<{
  chain: ChainNode[]
}>()

defineEmits<{
  click: [name: string]
}>()
</script>

<template>
  <div class="flex gap-2 items-center flex-wrap">
    <template v-for="(node, index) in chain" :key="node.name">
      <!-- Node pill -->
      <NuxtLink
        v-if="node.link"
        :to="node.link"
        :class="[
          'px-3 py-1.5 rounded-xl border text-sm transition-colors',
          node.active
            ? 'border-primary/40 bg-primary/10 text-primary font-semibold'
            : 'border-glass-border bg-glass/40 text-muted-foreground hover:bg-glass-hover hover:text-foreground',
        ]"
      >
        {{ node.label ?? node.name }}
      </NuxtLink>
      <button
        v-else
        :class="[
          'px-3 py-1.5 rounded-xl border text-sm transition-colors',
          node.active
            ? 'border-primary/40 bg-primary/10 text-primary font-semibold cursor-default'
            : 'border-glass-border bg-glass/40 text-muted-foreground hover:bg-glass-hover hover:text-foreground cursor-pointer',
        ]"
        @click="$emit('click', node.name)"
      >
        {{ node.label ?? node.name }}
      </button>

      <!-- Arrow separator (not after last) -->
      <ChevronRight
        v-if="index < chain.length - 1"
        class="size-4 text-muted-foreground/40 shrink-0"
      />
    </template>
  </div>
</template>
