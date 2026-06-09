<script setup lang="ts">
import { StatusDot } from "@/components/ui/status-dot"
</script>

<template>
  <Story title="Status dot" group="primitives">
    <Variant title="Tones">
      <div class="flex items-center gap-4">
        <div class="flex items-center gap-2"><StatusDot tone="success" /><span class="text-sm">success</span></div>
        <div class="flex items-center gap-2"><StatusDot tone="primary" /><span class="text-sm">primary</span></div>
        <div class="flex items-center gap-2"><StatusDot tone="warning" /><span class="text-sm">warning</span></div>
        <div class="flex items-center gap-2"><StatusDot tone="destructive" /><span class="text-sm">destructive</span></div>
        <div class="flex items-center gap-2"><StatusDot tone="muted" /><span class="text-sm">muted</span></div>
      </div>
    </Variant>
    <Variant title="Pulse">
      <div class="flex gap-4">
        <StatusDot tone="success" pulse />
        <StatusDot tone="primary" pulse />
        <StatusDot tone="warning" pulse />
      </div>
    </Variant>
    <Variant title="Sizes">
      <div class="flex items-center gap-3">
        <StatusDot tone="success" size="sm" />
        <StatusDot tone="success" size="md" />
      </div>
    </Variant>
  </Story>
</template>
