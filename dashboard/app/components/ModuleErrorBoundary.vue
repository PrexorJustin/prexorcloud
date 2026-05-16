<script setup lang="ts">
import { AlertTriangle } from 'lucide-vue-next'
import { Button } from '~/components/ui/button'

const error = ref<string | null>(null)

onErrorCaptured((err) => {
  error.value = err instanceof Error ? err.message : String(err)
  return false // prevent propagation
})
</script>

<template>
  <div v-if="error" class="flex flex-col items-center justify-center h-64 gap-4">
    <div class="size-12 rounded-2xl bg-destructive/10 border border-destructive/30 flex items-center justify-center">
      <AlertTriangle class="size-6 text-destructive" />
    </div>
    <div class="text-center max-w-md">
      <p class="text-sm font-medium text-foreground">Module Crashed</p>
      <p class="text-sm text-muted-foreground mt-1">{{ error }}</p>
    </div>
    <div class="flex gap-2">
      <Button variant="outline" size="sm" @click="error = null">Retry</Button>
      <Button variant="outline" size="sm" @click="navigateTo('/')">Back to Dashboard</Button>
    </div>
  </div>
  <slot v-else />
</template>
