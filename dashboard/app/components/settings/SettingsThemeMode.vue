<script setup lang="ts">
import { Sun, Moon, Monitor } from "lucide-vue-next"

const { t } = useI18n()
const colorMode = useColorMode()

const modes = computed(() => [
  { key: "light", label: t('components.settings.themeMode.light.label'), icon: Sun, desc: t('components.settings.themeMode.light.desc') },
  { key: "dark", label: t('components.settings.themeMode.dark.label'), icon: Moon, desc: t('components.settings.themeMode.dark.desc') },
  { key: "system", label: t('components.settings.themeMode.system.label'), icon: Monitor, desc: t('components.settings.themeMode.system.desc') },
])
</script>

<template>
  <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
    <h3 class="text-base font-semibold text-foreground mb-1">{{ t('components.settings.themeMode.title') }}</h3>
    <p class="text-sm text-muted-foreground mb-4">{{ t('components.settings.themeMode.subtitle') }}</p>
    <div class="grid grid-cols-3 gap-3">
      <button
        v-for="mode in modes"
        :key="mode.key"
        :class="[
          'flex flex-col items-center gap-2 p-4 rounded-xl border transition-all cursor-pointer',
          colorMode.preference === mode.key
            ? 'border-primary bg-primary/10'
            : 'border-glass-border hover:border-primary/40 hover:bg-glass-hover',
        ]"
        @click="colorMode.preference = mode.key"
      >
        <component
          :is="mode.icon"
          class="size-6"
          :class="colorMode.preference === mode.key ? 'text-primary' : 'text-muted-foreground'"
        />
        <span class="text-sm font-medium text-foreground">{{ mode.label }}</span>
        <span class="text-xs text-muted-foreground">{{ mode.desc }}</span>
      </button>
    </div>
  </div>
</template>
