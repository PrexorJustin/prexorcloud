<script setup lang="ts">
import { Toaster as Sonner } from 'vue-sonner'
</script>

<template>
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
  <Sonner
    position="bottom-center"
    theme="dark"
    :toast-options="{
      duration: 4000,
      classes: {
        toast: 'rounded-2xl !bg-card border-glass-border text-foreground',
        title: 'text-foreground',
        description: 'text-muted-foreground',
        success: '[&>[data-icon]]:text-success',
        error: '[&>[data-icon]]:text-destructive',
        warning: '[&>[data-icon]]:text-warning',
        info: '[&>[data-icon]]:text-primary',
      },
    }"
  />
</template>
