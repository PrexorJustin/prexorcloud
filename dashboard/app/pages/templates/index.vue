<script setup lang="ts">
import { FileCode, Package, Hash, Trash2 } from "lucide-vue-next"
import { Badge } from "~/components/ui/badge"
import { formatBytes } from "~/lib/utils"
import CreateTemplateDialog from "~/components/templates/CreateTemplateDialog.vue"

const store = useTemplatesStore()
const { t: $t } = useI18n()

const { search, viewMode, filteredItems: filteredTemplates } = useFilteredList(
  () => store.templates,
  {
    searchFields: t => [t.name, t.platform, t.description],
  },
)

const confirmDeleteName = ref<string | null>(null)
const deleting = ref(false)

onMounted(() => { store.fetchTemplates(); store.connectSse() })
onUnmounted(() => { store.disconnectSse() })

async function deleteTemplate() {
  if (!confirmDeleteName.value) return
  deleting.value = true
  try {
    await store.deleteTemplate(confirmDeleteName.value)
  } finally {
    deleting.value = false
    confirmDeleteName.value = null
  }
}
</script>

<template>
  <div class="flex flex-col gap-5 flex-1">
    <PageHeader :title="$t('pages.templates.title')" :description="$t('pages.templates.description')">
      <template #actions>
        <TemplatesImportTemplateDialog />
        <CreateTemplateDialog />
      </template>
    </PageHeader>

    <FilterToolbar
      v-model:search="search"
      :view-mode="viewMode"
      :search-placeholder="$t('pages.templates.searchPlaceholder')"
      @update:view-mode="viewMode = $event"
    />

    <div>
      <LoadingSkeleton v-if="store.loading" />

      <EmptyState
        v-else-if="filteredTemplates.length === 0"
        :icon="FileCode"
        :title="$t('pages.templates.emptyTitle')"
        :description="search ? $t('pages.templates.emptySearchHint') : $t('pages.templates.emptyCreateHint')"
      />

      <template v-else-if="viewMode === 'grid'">
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <div
            v-for="t in filteredTemplates"
            :key="t.name"
            class="relative bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border hover:bg-glass-hover hover:border-glass-border-hover p-5 cursor-pointer transition-all duration-300 select-none group"
            @click="navigateTo(`/templates/${t.name}`)"
          >
            <div class="flex items-start justify-between mb-3">
              <div class="flex items-center gap-3">
                <div class="size-10 rounded-xl bg-glass flex items-center justify-center">
                  <FileCode class="size-5 text-muted-foreground" />
                </div>
                <div>
                  <p class="font-semibold text-foreground">{{ t.name }}</p>
                  <p class="text-xs text-muted-foreground">{{ t.platform }}</p>
                </div>
              </div>
              <button
                class="size-7 rounded-lg flex items-center justify-center text-muted-foreground/0 group-hover:text-muted-foreground hover:text-destructive! hover:bg-destructive/10 transition-all"
                :title="$t('pages.templates.deleteButton')"
                @click.stop="confirmDeleteName = t.name"
              >
                <Trash2 class="size-3.5" />
              </button>
            </div>
            <p v-if="t.description" class="text-xs text-muted-foreground mb-3 line-clamp-1">{{ t.description }}</p>
            <div class="grid grid-cols-3 gap-3">
              <div class="flex items-center gap-2"><Package class="size-3.5 text-muted-foreground" /><span class="text-sm text-foreground">{{ t.platform }}</span></div>
              <div class="flex items-center gap-2"><Hash class="size-3.5 text-muted-foreground" /><span class="text-xs text-muted-foreground font-mono">{{ t.hash.slice(0, 8) }}</span></div>
              <div class="text-sm text-muted-foreground text-right tabular-nums">{{ formatBytes(t.sizeBytes) }}</div>
            </div>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border overflow-hidden">
          <div class="flex items-center h-10 px-4 border-b border-glass-border text-xs font-medium text-muted-foreground uppercase tracking-wider">
            <div class="w-44 shrink-0">{{ $t('pages.templates.columns.template') }}</div>
            <div class="w-28 shrink-0 text-center">{{ $t('pages.templates.columns.platform') }}</div>
            <div class="flex-1 min-w-0">{{ $t('pages.templates.columns.description') }}</div>
            <div class="w-32 shrink-0 text-center">{{ $t('pages.templates.columns.hash') }}</div>
            <div class="w-20 shrink-0 text-right">{{ $t('pages.templates.columns.size') }}</div>
            <div class="w-10 shrink-0" />
          </div>
          <div v-for="t in filteredTemplates" :key="t.name" class="flex items-center h-12 px-4 border-b border-glass-border/50 last:border-0 cursor-pointer transition-colors select-none hover:bg-glass-hover group" @click="navigateTo(`/templates/${t.name}`)">
            <div class="w-44 shrink-0 text-sm font-medium text-foreground truncate">{{ t.name }}</div>
            <div class="w-28 shrink-0 text-center text-sm text-muted-foreground">{{ t.platform }}</div>
            <div class="flex-1 min-w-0 text-xs text-muted-foreground truncate px-2">{{ t.description || '\u2014' }}</div>
            <div class="w-32 shrink-0 text-center text-xs font-mono text-muted-foreground">{{ t.hash.slice(0, 12) }}</div>
            <div class="w-20 shrink-0 text-right text-sm text-muted-foreground tabular-nums">{{ formatBytes(t.sizeBytes) }}</div>
            <div class="w-10 shrink-0 flex justify-end">
              <button class="size-7 rounded-lg flex items-center justify-center text-muted-foreground/0 group-hover:text-muted-foreground hover:text-destructive! hover:bg-destructive/10 transition-all" :title="$t('pages.templates.deleteButton')" @click.stop="confirmDeleteName = t.name">
                <Trash2 class="size-3.5" />
              </button>
            </div>
          </div>
        </div>
      </template>
    </div>

    <ConfirmDialog
      :open="!!confirmDeleteName"
      :title="$t('pages.templates.confirmDeleteTitle')"
      :description="$t('pages.templates.confirmDeleteDescription', { name: confirmDeleteName })"
      :confirm-label="$t('pages.templates.confirmDeleteLabel')"
      :loading="deleting"
      @update:open="confirmDeleteName = null"
      @confirm="deleteTemplate"
    />
  </div>
</template>
