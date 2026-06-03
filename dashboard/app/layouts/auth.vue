<script setup lang="ts">
const { t } = useI18n()
</script>

<template>
  <div class="relative flex min-h-svh items-center justify-center overflow-hidden bg-background p-6 md:p-10">
    <!-- Ambient glows: static, drift on load only (no continuous animation per design system). -->
    <div aria-hidden="true" class="pointer-events-none absolute inset-0">
      <div
        class="absolute h-[480px] w-[480px] rounded-full blur-[140px]"
        style="left: 18%; top: 18%; transform: translate(-50%, -50%); background: color-mix(in srgb, var(--primary) 18%, transparent);"
      />
      <div
        class="absolute h-[420px] w-[420px] rounded-full blur-[140px]"
        style="right: 12%; bottom: 12%; transform: translate(50%, 50%); background: color-mix(in srgb, var(--secondary) 14%, transparent);"
      />
    </div>

    <!-- Radial fade so glows feel rooted to the center. -->
    <div
      aria-hidden="true"
      class="pointer-events-none absolute inset-0"
      style="background: radial-gradient(ellipse at center, transparent 0%, var(--background) 75%);"
    />

    <div class="relative w-full max-w-[420px]">
      <NuxtLink
        to="/"
        :aria-label="t('auth.homeAriaLabel')"
        class="mb-8 flex items-center justify-center gap-2.5 transition-opacity hover:opacity-80"
      >
        <img src="/logomark.svg" alt="" aria-hidden="true" width="32" height="32" class="size-8" >
        <span class="text-lg font-bold tracking-tight">PrexorCloud</span>
      </NuxtLink>

      <slot />
    </div>
  </div>
</template>
