<script setup lang="ts">
import { Database, FileArchive, Rocket, Package, RefreshCw, Flame } from 'lucide-vue-next'
import { toast } from 'vue-sonner'
import type { NodeCacheStatus } from '~/types/api'
import { Badge } from '~/components/ui/badge'
import { Button } from '~/components/ui/button'

const props = defineProps<{
  nodeId: string
  cache: NodeCacheStatus | null
  loading: boolean
}>()

const emit = defineEmits<{
  refresh: []
}>()

const { t } = useI18n()

function formatBytes(bytes: number): string {
  if (bytes >= 1073741824) return `${(bytes / 1073741824).toFixed(1)} GB`
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

async function refresh() {
  await useApiClient().POST('/api/v1/nodes/{id}/cache/refresh', { params: { path: { id: props.nodeId } } })
  toast.success(t('toast.nodes.cacheRefreshRequested'))
  emit('refresh')
}

async function preWarm() {
  await useApiClient().POST('/api/v1/nodes/{id}/cache/warm', { params: { path: { id: props.nodeId } } })
  toast.success(t('toast.nodes.cachePrewarmStarted'))
}
</script>

<template>
  <div class="flex flex-col gap-5">
    <!-- Cache header -->
    <div class="flex items-center justify-between">
      <div>
        <h3 class="text-lg font-semibold text-foreground">{{ t('components.nodeCache.title') }}</h3>
        <p class="text-sm text-muted-foreground mt-0.5">
          {{ t('components.nodeCache.subtitle') }}
          <template v-if="cache"> &mdash; {{ formatBytes(cache.totalSizeBytes) }} {{ t('components.nodeCache.total') }}</template>
        </p>
      </div>
      <div class="flex items-center gap-2">
        <Button variant="outline" size="sm" class="border-glass-border" @click="refresh">
          <RefreshCw class="size-3.5 mr-1.5" /> {{ t('components.nodeCache.refresh') }}
        </Button>
        <Button variant="outline" size="sm" class="border-glass-border" @click="preWarm">
          <Flame class="size-3.5 mr-1.5" /> {{ t('components.nodeCache.preWarm') }}
        </Button>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="grid grid-cols-1 sm:grid-cols-3 gap-4">
      <div v-for="i in 3" :key="i" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6 animate-pulse">
        <div class="h-5 bg-glass rounded w-32 mb-4" />
        <div class="space-y-3">
          <div class="h-4 bg-glass rounded w-full" />
          <div class="h-4 bg-glass rounded w-3/4" />
        </div>
      </div>
    </div>

    <template v-else-if="cache">
      <!-- Cache summary cards -->
      <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-5">
          <div class="flex items-center gap-3 mb-2">
            <div class="size-10 rounded-xl bg-primary/20 flex items-center justify-center">
              <FileArchive class="size-5 text-primary" />
            </div>
            <p class="text-sm text-muted-foreground">{{ t('components.nodeCache.templates') }}</p>
          </div>
          <p class="text-3xl font-bold text-foreground tabular-nums">{{ cache.templates.length }}</p>
        </div>
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-5">
          <div class="flex items-center gap-3 mb-2">
            <div class="size-10 rounded-xl bg-secondary/20 flex items-center justify-center">
              <Package class="size-5 text-secondary" />
            </div>
            <p class="text-sm text-muted-foreground">{{ t('components.nodeCache.jars') }}</p>
          </div>
          <p class="text-3xl font-bold text-foreground tabular-nums">{{ cache.jars.length }}</p>
        </div>
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-5">
          <div class="flex items-center gap-3 mb-2">
            <div class="size-10 rounded-xl bg-success/20 flex items-center justify-center">
              <Rocket class="size-5 text-success" />
            </div>
            <p class="text-sm text-muted-foreground">{{ t('components.nodeCache.bootstraps') }}</p>
          </div>
          <p class="text-3xl font-bold text-foreground tabular-nums">{{ cache.bootstraps.length }}</p>
        </div>
      </div>

      <!-- Templates table -->
      <div v-if="cache.templates.length > 0" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
        <h3 class="text-lg font-semibold text-foreground mb-4 flex items-center gap-2">
          <FileArchive class="size-5 text-muted-foreground" />
          {{ t('components.nodeCache.templates') }}
        </h3>
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b border-glass-border text-muted-foreground">
                <th class="text-left py-2 pr-4 font-medium">{{ t('components.nodeCache.colName') }}</th>
                <th class="text-right py-2 pr-4 font-medium">{{ t('components.nodeCache.colSize') }}</th>
                <th class="text-right py-2 font-medium">{{ t('components.nodeCache.colLastUsed') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in cache.templates" :key="t.name" class="border-b border-glass-border/50 last:border-0">
                <td class="py-2.5 pr-4 font-mono text-foreground">{{ t.name }}</td>
                <td class="py-2.5 pr-4 text-right text-muted-foreground tabular-nums">{{ formatBytes(t.sizeBytes) }}</td>
                <td class="py-2.5 text-right text-muted-foreground">{{ new Date(t.lastUsed).toLocaleString() }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- JARs table -->
      <div v-if="cache.jars.length > 0" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
        <h3 class="text-lg font-semibold text-foreground mb-4 flex items-center gap-2">
          <Package class="size-5 text-muted-foreground" />
          {{ t('components.nodeCache.jars') }}
        </h3>
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b border-glass-border text-muted-foreground">
                <th class="text-left py-2 pr-4 font-medium">{{ t('components.nodeCache.colPlatform') }}</th>
                <th class="text-left py-2 pr-4 font-medium">{{ t('components.nodeCache.colVersion') }}</th>
                <th class="text-left py-2 pr-4 font-medium">{{ t('components.nodeCache.colFile') }}</th>
                <th class="text-right py-2 pr-4 font-medium">{{ t('components.nodeCache.colSize') }}</th>
                <th class="text-right py-2 font-medium">{{ t('components.nodeCache.colCachedAt') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="j in cache.jars" :key="j.jarFile" class="border-b border-glass-border/50 last:border-0">
                <td class="py-2.5 pr-4 text-foreground">
                  <Badge variant="outline" class="text-xs">{{ j.platform }}</Badge>
                </td>
                <td class="py-2.5 pr-4 text-foreground">{{ j.version }}</td>
                <td class="py-2.5 pr-4 font-mono text-muted-foreground text-xs">{{ j.jarFile }}</td>
                <td class="py-2.5 pr-4 text-right text-muted-foreground tabular-nums">{{ formatBytes(j.sizeBytes) }}</td>
                <td class="py-2.5 text-right text-muted-foreground">{{ new Date(j.cachedAt).toLocaleString() }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Bootstraps table -->
      <div v-if="cache.bootstraps.length > 0" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
        <h3 class="text-lg font-semibold text-foreground mb-4 flex items-center gap-2">
          <Rocket class="size-5 text-muted-foreground" />
          {{ t('components.nodeCache.bootstraps') }}
        </h3>
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b border-glass-border text-muted-foreground">
                <th class="text-left py-2 pr-4 font-medium">{{ t('components.nodeCache.colFormat') }}</th>
                <th class="text-left py-2 pr-4 font-medium">{{ t('components.nodeCache.colVersion') }}</th>
                <th class="text-left py-2 pr-4 font-medium">{{ t('components.nodeCache.colCds') }}</th>
                <th class="text-right py-2 font-medium">{{ t('components.nodeCache.colSize') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in cache.bootstraps" :key="`${b.configFormat}-${b.version}`" class="border-b border-glass-border/50 last:border-0">
                <td class="py-2.5 pr-4 text-foreground">{{ b.configFormat }}</td>
                <td class="py-2.5 pr-4 text-foreground">{{ b.version }}</td>
                <td class="py-2.5 pr-4">
                  <Badge :variant="b.hasCds ? 'default' : 'outline'" :class="b.hasCds ? 'bg-success/20 text-success border-success/30' : ''">
                    {{ b.hasCds ? t('components.nodeCache.yes') : t('components.nodeCache.no') }}
                  </Badge>
                </td>
                <td class="py-2.5 text-right text-muted-foreground tabular-nums">{{ formatBytes(b.sizeBytes) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Empty state -->
      <div
v-if="cache.templates.length === 0 && cache.jars.length === 0 && cache.bootstraps.length === 0"
        class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border py-16 flex flex-col items-center justify-center text-center"
      >
        <Database class="size-12 text-muted-foreground/30 mb-3" />
        <p class="text-foreground font-semibold text-lg">{{ t('components.nodeCache.emptyTitle') }}</p>
        <p class="text-muted-foreground mt-1">{{ t('components.nodeCache.emptyDescription') }}</p>
      </div>
    </template>
  </div>
</template>
