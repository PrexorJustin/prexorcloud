<script setup lang="ts">
import { Package, Network, Hash, Star, FileCode } from "lucide-vue-next"
import type { CatalogEntry } from "~/types/api"
import { Badge } from "~/components/ui/badge"

const props = defineProps<{
  entry: CatalogEntry
}>()

const categoryConfig = {
  SERVER: { label: "Server", color: "text-success", dot: "bg-success", icon: Package, gradient: "from-success/10" },
  PROXY: { label: "Proxy", color: "text-primary", dot: "bg-primary", icon: Network, gradient: "from-primary/10" },
}

const config = computed(() => categoryConfig[props.entry.category] ?? categoryConfig.SERVER)
const recommendedVersion = computed(() => props.entry.versions.find(v => v.recommended)?.version ?? "None")
</script>

<template>
  <div
    class="relative bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border hover:bg-glass-hover hover:border-glass-border-hover p-5 cursor-pointer transition-all duration-300 group select-none"
    @click="navigateTo(`/catalog/${entry.platform}`)"
  >
    <!-- Category gradient -->
    <div
class="absolute inset-0 bg-linear-to-br from-transparent to-transparent opacity-30 rounded-2xl overflow-hidden"
      :class="config.gradient"
    />

    <div class="relative z-10">
      <!-- Header: Icon + Name + Category -->
      <div class="flex items-start justify-between mb-4">
        <div class="flex items-center gap-3">
          <div class="relative">
            <div class="size-10 rounded-xl bg-glass flex items-center justify-center">
              <component :is="config.icon" class="size-5 text-muted-foreground" />
            </div>
            <div :class="['absolute -bottom-0.5 -right-0.5 size-3 rounded-full border-2 border-background', config.dot]" />
          </div>
          <div>
            <p class="font-semibold text-foreground uppercase">{{ entry.platform }}</p>
            <p class="text-xs text-muted-foreground">{{ entry.configFormat ?? 'Unknown format' }}</p>
          </div>
        </div>
        <Badge variant="outline" :class="['text-xs', config.color]">
          {{ config.label }}
        </Badge>
      </div>

      <!-- Metrics -->
      <div class="grid grid-cols-3 gap-3 mt-3">
        <div class="flex items-center gap-2">
          <Hash class="size-3.5 text-muted-foreground" />
          <span class="text-sm text-foreground tabular-nums">{{ entry.versions.length }}</span>
        </div>
        <div class="flex items-center gap-2">
          <Star class="size-3.5 text-muted-foreground" />
          <span class="text-sm text-foreground truncate">{{ recommendedVersion }}</span>
        </div>
        <div class="flex items-center gap-2">
          <FileCode class="size-3.5 text-muted-foreground" />
          <span class="text-sm text-foreground">{{ entry.configFormat ?? 'N/A' }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
