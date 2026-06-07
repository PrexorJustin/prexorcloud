<script setup lang="ts">
import { ref } from "vue"
import { Toggle } from "@/components/ui/toggle"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { Bold, Italic, Underline, AlignLeft, AlignCenter, AlignRight } from "lucide-vue-next"

const align = ref("left")
</script>

<template>
  <Story title="Toggle / ToggleGroup" group="primitives">
    <Variant title="Single toggles">
      <div class="flex items-center gap-2">
        <Toggle aria-label="Bold"><Bold class="size-4" /></Toggle>
        <Toggle aria-label="Italic"><Italic class="size-4" /></Toggle>
        <Toggle aria-label="Underline"><Underline class="size-4" /></Toggle>
      </div>
    </Variant>

    <Variant title="ToggleGroup (single-select)">
      <ToggleGroup v-model="align" type="single" class="inline-flex gap-1 rounded-lg border border-glass-border bg-glass/40 p-1">
        <ToggleGroupItem value="left" aria-label="Align left"><AlignLeft class="size-4" /></ToggleGroupItem>
        <ToggleGroupItem value="center" aria-label="Align center"><AlignCenter class="size-4" /></ToggleGroupItem>
        <ToggleGroupItem value="right" aria-label="Align right"><AlignRight class="size-4" /></ToggleGroupItem>
      </ToggleGroup>
      <p class="mt-2 text-xs text-muted-foreground">Selected: <span class="mono">{{ align }}</span></p>
    </Variant>
  </Story>
</template>
