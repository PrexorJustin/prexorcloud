<script setup lang="ts">
import { Spinner } from "@/components/ui/spinner"
</script>

<template>
  <Story title="Spinner" group="primitives">
    <Variant title="Sizes">
      <div class="flex items-center gap-4">
        <Spinner class="size-3" />
        <Spinner class="size-4" />
        <Spinner class="size-5" />
        <Spinner class="size-8" />
      </div>
    </Variant>

    <Variant title="Tones">
      <div class="flex items-center gap-4">
        <Spinner class="text-primary" />
        <Spinner class="text-success" />
        <Spinner class="text-warning" />
        <Spinner class="text-destructive" />
        <Spinner class="text-muted-foreground" />
      </div>
    </Variant>
  </Story>
</template>
