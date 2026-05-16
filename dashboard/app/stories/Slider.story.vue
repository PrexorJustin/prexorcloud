<script setup lang="ts">
import { ref } from "vue"
import { Slider } from "@/components/ui/slider"

const single = ref([24])
const range = ref([8, 64])
</script>

<template>
  <Story title="Slider" group="primitives">
    <Variant title="Single value">
      <div class="w-80 space-y-2">
        <p class="text-sm text-muted-foreground">Memory: <span class="tabular text-foreground">{{ single[0] }}</span> GB</p>
        <Slider v-model="single" :min="1" :max="64" :step="1" />
      </div>
    </Variant>

    <Variant title="Range">
      <div class="w-80 space-y-2">
        <p class="text-sm text-muted-foreground">Port range: <span class="tabular text-foreground">{{ range[0] }}–{{ range[1] }}</span></p>
        <Slider v-model="range" :min="1" :max="100" :step="1" />
      </div>
    </Variant>
  </Story>
</template>
