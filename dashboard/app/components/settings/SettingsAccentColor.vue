<script setup lang="ts">
import { Check, Pipette } from "lucide-vue-next"
import { accentColors } from "~/lib/theme-data"

const colorMode = useColorMode()
const appearance = useAppearanceStore()
const isDark = computed(() => colorMode.value === "dark")
</script>

<template>
  <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
    <h3 class="text-base font-semibold text-foreground mb-1">Accent Color</h3>
    <p class="text-sm text-muted-foreground mb-4">Sets the primary color across the entire dashboard</p>
    <div class="grid grid-cols-3 sm:grid-cols-5 gap-2">
      <button
        v-for="color in accentColors"
        :key="color.name"
        :class="[
          'inline-flex items-center gap-2.5 rounded-xl border px-4 py-2.5 text-sm transition-all cursor-pointer',
          appearance.accentColor === color.name
            ? 'border-primary bg-primary/10 text-foreground'
            : 'border-glass-border text-muted-foreground hover:text-foreground hover:border-foreground/20',
        ]"
        @click="appearance.setAccentColor(color.name)"
      >
        <span
          class="flex size-5 shrink-0 items-center justify-center rounded-full"
          :style="{ backgroundColor: isDark ? color.value : color.light }"
        >
          <Check v-if="appearance.accentColor === color.name" class="size-3 text-primary-foreground" />
        </span>
        {{ color.name }}
      </button>

      <!-- Custom accent color -->
      <label
        :class="[
          'inline-flex items-center gap-2.5 rounded-xl border px-4 py-2.5 text-sm transition-all cursor-pointer',
          appearance.accentColor === 'Custom'
            ? 'border-primary bg-primary/10 text-foreground'
            : 'border-dashed border-glass-border text-muted-foreground hover:text-foreground hover:border-foreground/20',
        ]"
      >
        <span class="relative flex size-8 shrink-0 items-center justify-center rounded-full overflow-hidden border border-glass-border">
          <input
            type="color"
            :value="appearance.customAccentColor ?? '#6366f1'"
            class="absolute inset-0 size-12 -m-2 cursor-pointer opacity-0"
            @input="appearance.setCustomAccentColor(($event.target as HTMLInputElement).value)"
          >
          <span v-if="appearance.accentColor === 'Custom'" class="size-full rounded-full" :style="{ backgroundColor: appearance.customAccentColor ?? '#6366f1' }" />
          <Pipette v-else class="size-4 text-muted-foreground" />
        </span>
        Custom
        <Pipette v-if="appearance.accentColor === 'Custom'" class="size-3 text-muted-foreground/50 ml-auto" />
      </label>
    </div>
  </div>
</template>
