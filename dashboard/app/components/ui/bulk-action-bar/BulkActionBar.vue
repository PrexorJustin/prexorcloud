<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { X } from "lucide-vue-next"
import { cn } from "@/lib/utils"

/**
 * Slides up from the bottom of the viewport when `count > 0`. Shows the
 * selection count + a Clear button on the left, and a slot for action
 * buttons on the right. Pairs with `useSelection`.
 *
 *   <BulkActionBar :count="count" :singular="'instance'" :plural="'instances'" @clear="clear">
 *     <Button @click="bulkStop">Stop</Button>
 *     <Button variant="destructive" @click="bulkDelete">Delete</Button>
 *   </BulkActionBar>
 */
const props = defineProps<{
  count: number
  /** Noun shown next to the count when count === 1. */
  singular: string
  /** Noun shown next to the count when count !== 1. */
  plural: string
  class?: HTMLAttributes["class"]
}>()

const emit = defineEmits<{ clear: [] }>()
</script>

<template>
  <Transition
    enter-active-class="transition-all duration-200 ease-out"
    enter-from-class="translate-y-4 opacity-0"
    leave-active-class="transition-all duration-150 ease-in"
    leave-to-class="translate-y-4 opacity-0"
  >
    <div
      v-if="props.count > 0"
      :class="cn(
        'fixed bottom-6 left-1/2 z-40 -translate-x-1/2 flex items-center gap-3 rounded-2xl border border-glass-border bg-popover/95 px-4 py-2.5 backdrop-blur-xl shadow-lg shadow-black/20',
        props.class,
      )"
      role="region"
      aria-label="Bulk actions"
    >
      <button
        type="button"
        aria-label="Clear selection"
        class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-glass-hover hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
        @click="emit('clear')"
      >
        <X class="size-4" />
      </button>

      <span class="text-sm tabular">
        <span class="font-semibold text-foreground">{{ props.count }}</span>
        <span class="text-muted-foreground"> {{ props.count === 1 ? props.singular : props.plural }} selected</span>
      </span>

      <span class="h-6 w-px bg-glass-border" aria-hidden="true" />

      <div class="flex items-center gap-1.5">
        <slot />
      </div>
    </div>
  </Transition>
</template>
