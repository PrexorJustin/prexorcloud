<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from "@/lib/utils"

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()
</script>

<template>
  <main
    :class="cn(
      'relative flex min-h-svh flex-1 flex-col',
      'peer-data-[variant=inset]:min-h-[calc(100svh-1rem)]',
      'md:peer-data-[variant=inset]:mt-2 md:peer-data-[variant=inset]:mr-2 md:peer-data-[variant=inset]:mb-2 md:peer-data-[variant=inset]:ml-0',
      'md:peer-data-[variant=inset]:rounded-r-xl md:peer-data-[variant=inset]:shadow',
      props.class,
    )"
  >
    <slot />
  </main>
</template>
