<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from "@/lib/utils"

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()
</script>

<template>
  <li
    data-sidebar="menu-item"
    :class="cn('group/menu-item relative flex flex-col group-data-[collapsible=icon]:items-center', props.class)"
  >
    <slot />
    <div class="absolute -right-2 inset-y-0.5 w-0.75 rounded-l-full bg-sidebar-primary opacity-0 transition-opacity peer-data-[active=true]/menu-button:opacity-100" />
  </li>
</template>
