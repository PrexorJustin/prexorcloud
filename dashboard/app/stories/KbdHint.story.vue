<script setup lang="ts">
import { KbdHint } from "@/components/ui/kbd"
</script>

<template>
  <Story title="Kbd hint" group="primitives">
    <Variant title="Single keys">
      <div class="flex gap-2">
        <KbdHint>⌘K</KbdHint>
        <KbdHint>Esc</KbdHint>
        <KbdHint>↵</KbdHint>
        <KbdHint>?</KbdHint>
      </div>
    </Variant>
  </Story>
</template>
