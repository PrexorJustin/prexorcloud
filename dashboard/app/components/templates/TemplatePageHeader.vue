<script setup lang="ts">
import { ArrowLeft, CircleDot, Download, RotateCcw, Save } from "lucide-vue-next"
import type { Template } from "~/types/api"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"
import { formatBytes } from "~/lib/utils"

const { t } = useI18n()

defineProps<{
  templateName: string
  template: Template | null
  hasChanges: boolean
  stagedCount: number
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'back'): void
  (e: 'export'): void
  (e: 'discard'): void
  (e: 'save'): void
}>()
</script>

<template>
  <div class="flex items-center gap-4">
    <Button variant="ghost" size="icon" :aria-label="t('common.a11y.back')" class="size-9 shrink-0" @click="emit('back')">
      <ArrowLeft class="size-5" />
    </Button>
    <div class="flex-1 min-w-0">
      <h1 class="text-2xl font-bold text-gradient-title truncate">{{ templateName }}</h1>
      <p v-if="template" class="text-muted-foreground text-sm mt-0.5">{{ template.platform }} · {{ formatBytes(template.sizeBytes) }}</p>
    </div>
    <div class="flex items-center gap-2">
      <Badge v-if="hasChanges" variant="outline" class="text-warning border-warning/30 gap-1.5">
        <CircleDot class="size-3" />
        {{ stagedCount }} staged
      </Badge>
      <Button variant="outline" size="sm" class="border-glass-border h-8 text-xs" @click="emit('export')">
        <Download class="size-3.5 mr-1.5" /> Export
      </Button>
      <Button
        v-if="hasChanges"
        variant="outline"
        class="border-glass-border"
        @click="emit('discard')"
      >
        <RotateCcw class="size-4 mr-2" /> Discard
      </Button>
      <Button
        :disabled="!hasChanges || saving"
        @click="emit('save')"
      >
        <Save class="size-4 mr-2" />
        {{ saving ? "Saving..." : "Save & Rehash" }}
      </Button>
    </div>
  </div>
</template>
