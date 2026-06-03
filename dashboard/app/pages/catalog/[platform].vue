<script setup lang="ts">
import { ArrowLeft, Package, Network, Star, Trash2, ArrowUpRight, Layers } from "lucide-vue-next"
import { toast } from "vue-sonner"
import type { CatalogEntry } from "~/types/api"
import { Badge } from "~/components/ui/badge"
import { Button } from "~/components/ui/button"

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const platformName = route.params.platform as string

const store = useCatalogStore()

const loading = ref(true)

async function fetchData() {
  loading.value = true
  try {
    await store.fetchCatalog()
    if (!store.entries.find(e => e.platform === platformName)) {
      toast.error(t('pages.catalogPlatform.toast.notFoundTitle'), { description: t('pages.catalogPlatform.toast.notFoundDesc', { platform: platformName }) })
      await router.push("/catalog")
    }
  } catch {
    toast.error(t('pages.catalogPlatform.toast.loadFailedTitle'), { description: t('pages.catalogPlatform.toast.loadFailedDesc') })
    await router.push("/catalog")
  } finally {
    loading.value = false
  }
}

const groupsStore = useGroupsStore()

const usedByGroups = computed(() =>
  groupsStore.groups.filter(g => g.platform.toLowerCase() === platformName.toLowerCase()),
)

onMounted(() => {
  fetchData()
  groupsStore.fetchGroups()
})

const entry = computed(() => store.entries.find(e => e.platform === platformName) ?? null)

const categoryConfig = computed<Record<string, { label: string; color: string; icon: typeof Package }>>(() => ({
  SERVER: { label: t("pages.catalog.category.server"), color: "text-success", icon: Package },
  PROXY: { label: t("pages.catalog.category.proxy"), color: "text-primary", icon: Network },
}))

const config = computed(() => {
  const fallback = categoryConfig.value.SERVER!
  return entry.value ? categoryConfig.value[entry.value.category] ?? fallback : fallback
})

const sortedVersions = computed(() =>
  entry.value
    ? [...entry.value.versions].sort((a, b) => {
        if (a.recommended && !b.recommended) return -1
        if (!a.recommended && b.recommended) return 1
        return b.version.localeCompare(a.version, undefined, { numeric: true })
      })
    : [],
)

const recommendedVersion = computed(() => entry.value?.versions.find(v => v.recommended)?.version ?? t("pages.catalogPlatform.none"))

// Delete confirmation
const confirmOpen = ref(false)
const pendingDeleteVersion = ref("")
const deleting = ref(false)

function requestDelete(version: string) {
  pendingDeleteVersion.value = version
  confirmOpen.value = true
}

async function onConfirmDelete() {
  deleting.value = true
  try {
    await store.deleteVersion(platformName, pendingDeleteVersion.value)
    confirmOpen.value = false
    // If no versions left, go back
    if (entry.value && entry.value.versions.length === 0) {
      await router.push("/catalog")
    }
  } catch {
    toast.error(t('pages.catalogPlatform.toast.actionFailedTitle'), { description: t('pages.catalogPlatform.toast.actionFailedDesc') })
  } finally {
    deleting.value = false
  }
}

const marking = ref<string | null>(null)

async function markRecommended(version: string) {
  marking.value = version
  try {
    await store.markRecommended(platformName, version)
  } catch {
    toast.error(t('pages.catalogPlatform.toast.actionFailedTitle'), { description: t('pages.catalogPlatform.toast.actionFailedDesc') })
  } finally {
    marking.value = null
  }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <!-- Header -->
    <div class="flex items-center gap-4">
      <Button variant="ghost" size="icon" class="size-9 shrink-0" @click="router.push('/catalog')">
        <ArrowLeft class="size-5" />
      </Button>
      <div class="flex-1 min-w-0">
        <p class="eyebrow mb-1">{{ t('pages.catalogPlatform.platform') }}</p>
        <h1 class="text-2xl font-bold tracking-tight text-gradient-title mono">{{ platformName }}</h1>
        <p v-if="entry" class="mt-0.5 text-sm text-muted-foreground">{{ entry.configFormat ?? t('pages.catalogPlatform.unknownFormat') }}</p>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="grid grid-cols-1 lg:grid-cols-2 gap-5">
      <div v-for="i in 2" :key="i" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6 animate-pulse">
        <div class="h-5 bg-glass rounded w-32 mb-4" />
        <div class="flex flex-col gap-3"><div class="h-4 bg-glass rounded" /><div class="h-4 bg-glass rounded w-3/4" /><div class="h-4 bg-glass rounded w-1/2" /></div>
      </div>
    </div>

    <template v-else-if="entry">
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-5">
        <!-- Platform Info -->
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
          <h2 class="mb-4 flex items-center gap-2 text-base font-semibold"><component :is="config.icon" class="size-4" /> {{ t('pages.catalogPlatform.platform') }}</h2>
          <div class="flex flex-col gap-3">
            <div class="flex justify-between">
              <span class="text-sm text-muted-foreground">{{ t('pages.catalogPlatform.info.category') }}</span>
              <Badge variant="outline" :class="['text-xs', config.color]">{{ config.label }}</Badge>
            </div>
            <div class="flex justify-between">
              <span class="text-sm text-muted-foreground">{{ t('pages.catalogPlatform.info.configFormat') }}</span>
              <span class="text-sm text-foreground">{{ entry.configFormat ?? t('pages.catalogPlatform.na') }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-sm text-muted-foreground">{{ t('pages.catalogPlatform.info.recommended') }}</span>
              <span class="text-sm font-medium text-primary">{{ recommendedVersion }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-sm text-muted-foreground">{{ t('pages.catalogPlatform.info.template') }}</span>
              <NuxtLink
                :to="`/templates/base-${entry.platform}`"
                class="inline-flex items-center gap-1 text-sm font-medium text-primary hover:text-primary/80 transition-colors"
              >
                base-{{ entry.platform }}
                <ArrowUpRight class="size-3" />
              </NuxtLink>
            </div>
          </div>
        </div>

        <!-- Versions -->
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
          <div class="flex items-center justify-between mb-4">
            <h2 class="flex items-center gap-2 text-base font-semibold"><Star class="size-4" /> {{ t('pages.catalogPlatform.versions') }}</h2>
            <CatalogAddVersionDialog :platform="entry.platform" />
          </div>
          <div v-if="sortedVersions.length === 0" class="text-center py-6">
            <p class="text-sm text-muted-foreground">{{ t('pages.catalogPlatform.noVersions') }}</p>
          </div>
          <div v-else class="flex flex-col gap-2 max-h-96 overflow-auto styled-scrollbar pr-1">
            <div
              v-for="version in sortedVersions"
              :key="version.version"
              class="flex items-center gap-3 p-3 rounded-xl border border-glass-border hover:bg-glass-hover transition-colors group/version"
            >
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2">
                  <span class="text-sm font-medium text-foreground" :class="version.recommended ? 'text-primary' : ''">
                    {{ version.version }}
                  </span>
                  <Star v-if="version.recommended" class="size-3 text-primary fill-primary" />
                </div>
                <a
                  :href="version.downloadUrl"
                  target="_blank"
                  rel="noopener"
                  class="text-[10px] text-muted-foreground/60 font-mono truncate block hover:text-muted-foreground transition-colors mt-0.5"
                  @click.stop
                >
                  {{ version.downloadUrl }}
                </a>
              </div>
              <div class="flex items-center gap-1 opacity-0 group-hover/version:opacity-100 transition-opacity">
                <button
                  v-if="!version.recommended"
                  class="p-1.5 rounded-lg text-muted-foreground hover:text-primary hover:bg-primary/10 transition-colors"
                  :title="t('pages.catalogPlatform.markRecommended')"
                  :disabled="marking === version.version"
                  @click.stop="markRecommended(version.version)"
                >
                  <Star class="size-3.5" />
                </button>
                <button
                  class="p-1.5 rounded-lg text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
                  :title="t('pages.catalogPlatform.deleteVersion')"
                  @click.stop="requestDelete(version.version)"
                >
                  <Trash2 class="size-3.5" />
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Used By Groups -->
      <div v-if="usedByGroups.length > 0" class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
        <h2 class="mb-4 flex items-center gap-2 text-base font-semibold"><Layers class="size-4" /> {{ t('pages.catalogPlatform.usedByGroups') }}</h2>
        <div class="flex flex-wrap gap-2">
          <NuxtLink
            v-for="g in usedByGroups"
            :key="g.name"
            :to="`/groups/${g.name}`"
            class="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg border border-glass-border hover:bg-glass-hover hover:border-primary/30 transition-all text-sm font-medium text-foreground"
          >
            {{ g.name }}
            <span class="text-xs text-muted-foreground">{{ g.platformVersion }}</span>
          </NuxtLink>
        </div>
      </div>
    </template>

    <ConfirmDialog
      :open="confirmOpen"
      :title="t('pages.catalogPlatform.confirmDeleteTitle')"
      :description="t('pages.catalogPlatform.confirmDeleteDesc', { version: pendingDeleteVersion, platform: platformName })"
      :confirm-label="t('pages.catalogPlatform.delete')"
      :loading="deleting"
      @update:open="confirmOpen = $event"
      @confirm="onConfirmDelete"
    />
  </div>
</template>
