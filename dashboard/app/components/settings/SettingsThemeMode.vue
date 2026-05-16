<script setup lang="ts">
import { Sun, Moon, Monitor } from "lucide-vue-next"

const colorMode = useColorMode()

const modes = [
  { key: "light", label: "Light", icon: Sun, desc: "Always light" },
  { key: "dark", label: "Dark", icon: Moon, desc: "Always dark" },
  { key: "system", label: "System", icon: Monitor, desc: "Match OS setting" },
]
</script>

<template>
  <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
    <h3 class="text-base font-semibold text-foreground mb-1">Theme Mode</h3>
    <p class="text-sm text-muted-foreground mb-4">Choose between light, dark, or system preference</p>
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
