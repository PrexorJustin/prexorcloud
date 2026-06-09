<script setup lang="ts">
import { Plus, Trash2, RefreshCw, ArrowRight, Loader2 } from "lucide-vue-next"
import { Button } from "@/components/ui/button"
</script>

<template>
  <Story title="Button" group="primitives">
    <Variant title="Variants">
      <div class="flex flex-wrap items-center gap-3">
        <Button>Primary</Button>
        <Button variant="secondary">Secondary</Button>
        <Button variant="outline">Outline</Button>
        <Button variant="ghost">Ghost</Button>
        <Button variant="link">Link</Button>
        <Button variant="destructive">Destructive</Button>
      </div>
    </Variant>

    <Variant title="Sizes">
      <div class="flex flex-wrap items-center gap-3">
        <Button size="sm">Small</Button>
        <Button>Default</Button>
        <Button size="lg">Large</Button>
        <Button size="icon" aria-label="Add"><Plus class="size-4" /></Button>
      </div>
    </Variant>

    <Variant title="With icons">
      <div class="flex flex-wrap items-center gap-3">
        <Button><Plus class="mr-2 size-4" /> Start instance</Button>
        <Button variant="outline"><RefreshCw class="mr-2 size-4" /> Refresh</Button>
        <Button variant="outline" class="border-destructive/50 text-destructive hover:bg-destructive/10">
          <Trash2 class="mr-2 size-4" /> Delete
        </Button>
        <Button class="group">
          Continue
          <ArrowRight class="ml-2 size-4 transition-transform group-hover:translate-x-0.5" />
        </Button>
      </div>
    </Variant>

    <Variant title="Loading + disabled">
      <div class="flex flex-wrap items-center gap-3">
        <Button disabled><Loader2 class="mr-2 size-4 animate-spin" /> Saving…</Button>
        <Button disabled>Disabled</Button>
        <Button variant="outline" disabled>Disabled outline</Button>
      </div>
    </Variant>

    <Variant title="Tone-tinted outlines (used in danger zones)">
      <div class="flex flex-wrap items-center gap-3">
        <Button variant="outline" class="border-warning/50 text-warning hover:bg-warning/10">Drain</Button>
        <Button variant="outline" class="border-warning/50 text-warning hover:bg-warning/10">Cordon</Button>
        <Button variant="outline" class="border-destructive/50 text-destructive hover:bg-destructive/10">Force-stop</Button>
      </div>
    </Variant>
  </Story>
</template>
