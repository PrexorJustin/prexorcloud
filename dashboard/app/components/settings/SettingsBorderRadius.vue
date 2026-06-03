<script setup lang="ts">
import { radii } from "~/lib/theme-data"

const { t } = useI18n()
const appearance = useAppearanceStore()
</script>

<template>
  <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
    <h3 class="text-base font-semibold text-foreground mb-1">{{ t('components.settings.borderRadius.title') }}</h3>
    <p class="text-sm text-muted-foreground mb-4">{{ t('components.settings.borderRadius.subtitle') }}</p>
    <div class="grid grid-cols-5 gap-3">
      <button
        v-for="r in radii"
        :key="r"
        :class="[
          'flex flex-col items-center gap-2 rounded-xl border px-3 py-3 transition-all cursor-pointer',
          appearance.radius === r
            ? 'border-primary bg-primary/10 text-foreground'
            : 'border-glass-border text-muted-foreground hover:text-foreground hover:border-foreground/20',
        ]"
        @click="appearance.setRadius(r)"
      >
        <div
          class="size-8 border-2 border-current"
          :style="{ borderRadius: `${r * 0.75}rem` }"
        />
        <span class="text-xs font-medium">{{ r }}</span>
      </button>
    </div>
  </div>
</template>
