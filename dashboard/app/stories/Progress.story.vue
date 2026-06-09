<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue"
import { Progress } from "@/components/ui/progress"

const value = ref(38)
let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  timer = setInterval(() => {
    value.value = (value.value + 7) % 101
  }, 600)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <Story title="Progress" group="primitives">
    <Variant title="Static">
      <div class="space-y-3">
        <Progress :model-value="0" />
        <Progress :model-value="38" />
        <Progress :model-value="80" />
        <Progress :model-value="100" />
      </div>
    </Variant>

    <Variant title="Animated">
      <Progress :model-value="value" />
      <p class="mt-2 tabular text-xs text-muted-foreground">{{ value }} %</p>
    </Variant>
  </Story>
</template>
