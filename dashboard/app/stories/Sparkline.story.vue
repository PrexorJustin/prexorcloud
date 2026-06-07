<script setup lang="ts">
import { Sparkline } from "@/components/ui/sparkline"

const sample = Array.from({ length: 32 }, (_, i) => Math.round(50 + 30 * Math.sin(i / 3) + Math.random() * 12))
const flat = Array(20).fill(8)
const climbing = Array.from({ length: 24 }, (_, i) => i * 3 + Math.random() * 5)
</script>

<template>
  <Story title="Sparkline" group="primitives">
    <Variant title="Default (primary, area)">
      <div class="w-64"><Sparkline :data="sample" tone="primary" /></div>
    </Variant>
    <Variant title="Tones">
      <div class="grid w-64 grid-cols-1 gap-3">
        <Sparkline :data="sample" tone="primary" />
        <Sparkline :data="sample" tone="secondary" />
        <Sparkline :data="sample" tone="success" />
        <Sparkline :data="sample" tone="warning" />
        <Sparkline :data="sample" tone="destructive" />
        <Sparkline :data="sample" tone="muted" />
      </div>
    </Variant>
    <Variant title="No area, climbing">
      <div class="w-64"><Sparkline :data="climbing" :area="false" tone="success" /></div>
    </Variant>
    <Variant title="Flat">
      <div class="w-64"><Sparkline :data="flat" tone="muted" /></div>
    </Variant>
    <Variant title="Taller">
      <div class="w-64"><Sparkline :data="sample" tone="primary" :height="80" /></div>
    </Variant>
  </Story>
</template>
