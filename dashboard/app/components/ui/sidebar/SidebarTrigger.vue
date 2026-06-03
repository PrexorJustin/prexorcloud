<script setup lang="ts">
import type {HTMLAttributes} from "vue"
import {PanelLeft} from "lucide-vue-next"
import {cn} from "@/lib/utils"
import {Button} from '@/components/ui/button'
import {useSidebar} from "./utils"

const props = defineProps<{
  class?: HTMLAttributes["class"]
}>()

const {toggleSidebar} = useSidebar()
const { t } = useI18n()
</script>

<template>
  <Button
      data-sidebar="trigger"
      variant="ghost"
      size="icon"
      @click="toggleSidebar"
  >
    <PanelLeft class="size-5!"/>
    <span class="sr-only">{{ t('components.srOnly.toggleSidebar') }}</span>
  </Button>
</template>
