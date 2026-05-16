<script setup lang="ts">
import { AlertTriangle, ArrowLeft, Home } from 'lucide-vue-next'
import { Button } from '~/components/ui/button'

const props = defineProps<{ error: { statusCode: number; message: string } }>()
</script>

<template>
  <div class="flex min-h-svh items-center justify-center bg-background p-4">
    <div class="mx-auto max-w-md text-center">
      <div class="mx-auto mb-6 flex size-16 items-center justify-center rounded-2xl bg-destructive/10">
        <AlertTriangle class="size-7 text-destructive" />
      </div>
      <h1 class="text-5xl font-bold tracking-tighter text-foreground">
        {{ props.error.statusCode }}
      </h1>
      <p class="mt-3 text-base text-muted-foreground">
        {{ props.error.message || 'Something went wrong' }}
      </p>
      <div class="mt-8 flex items-center justify-center gap-3">
        <Button variant="outline" @click="$router.back()">
          <ArrowLeft class="mr-2 size-4" />
          Go back
        </Button>
        <Button @click="clearError({ redirect: '/' })">
          <Home class="mr-2 size-4" />
          Home
        </Button>
      </div>
    </div>
  </div>
</template>
