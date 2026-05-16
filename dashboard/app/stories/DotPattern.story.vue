<script setup lang="ts">
import { Eyebrow } from "@/components/ui/eyebrow"
import { DotPattern } from "@/components/ui/dot-pattern"
</script>

<template>
  <Story title="Dot pattern" group="primitives">
    <Variant title="Faded over glass">
      <div class="relative h-48 rounded-2xl border border-glass-border bg-glass/40 overflow-hidden">
        <DotPattern />
        <div class="relative z-10 p-6">
          <Eyebrow class="mb-2">Hero header</Eyebrow>
          <p class="text-lg font-semibold">Dot pattern is masked with a radial fade by default.</p>
        </div>
      </div>
    </Variant>
  </Story>
</template>
