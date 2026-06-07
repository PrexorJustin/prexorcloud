<script setup lang="ts">
import type { Component } from 'vue'
import { AlertTriangle, Loader2, Puzzle } from 'lucide-vue-next'
import { Button } from '~/components/ui/button'

const { t } = useI18n()
const route = useRoute()
const moduleStore = useModuleStore()

const ModuleComponent = shallowRef<Component | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const slug = computed(() => {
  const params = route.params.slug
  return Array.isArray(params) ? params.join('/') : params || ''
})

watch(slug, async (s) => {
  loading.value = true
  error.value = null
  ModuleComponent.value = null

  const resolved = moduleStore.resolveRoute(s)
  if (!resolved) {
    error.value = t('pages.moduleLoader.notFound')
    loading.value = false
    return
  }

  try {
    const exports = await moduleStore.ensureLoaded(resolved.moduleName)
    const comp = exports[resolved.componentName]
    if (!comp) {
      error.value = t('pages.moduleLoader.componentNotFound', { component: resolved.componentName, module: resolved.moduleName })
    }
    else {
      ModuleComponent.value = comp
    }
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : t('pages.moduleLoader.loadFailed')
  }
  finally {
    loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <!-- Loading -->
  <div v-if="loading" class="flex h-64 flex-col items-center justify-center gap-4">
    <div class="flex size-12 items-center justify-center rounded-2xl border border-glass-border bg-glass/60">
      <Loader2 class="size-6 animate-spin text-muted-foreground" />
    </div>
    <p class="text-sm text-muted-foreground">{{ t('pages.moduleLoader.loading') }}</p>
  </div>

  <div v-else-if="error" class="flex h-64 flex-col items-center justify-center gap-4">
    <div class="flex size-12 items-center justify-center rounded-2xl border border-destructive/30 bg-destructive/10">
      <AlertTriangle class="size-6 text-destructive" />
    </div>
    <div class="text-center">
      <p class="text-sm font-semibold">{{ t('pages.moduleLoader.cantLoad') }}</p>
      <p class="mt-1 text-sm text-muted-foreground">{{ error }}</p>
    </div>
    <Button variant="outline" size="sm" @click="navigateTo('/')">{{ t('pages.moduleLoader.backToOverview') }}</Button>
  </div>

  <ModuleErrorBoundary v-else-if="ModuleComponent">
    <component :is="ModuleComponent" />
  </ModuleErrorBoundary>

  <div v-else class="flex h-64 flex-col items-center justify-center gap-4">
    <div class="flex size-12 items-center justify-center rounded-2xl border border-glass-border bg-glass/60">
      <Puzzle class="size-6 text-muted-foreground" />
    </div>
    <p class="text-sm text-muted-foreground">{{ t('pages.moduleLoader.noModule') }}</p>
  </div>
</template>
