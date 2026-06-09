<script setup lang="ts">
import { History, RotateCcw, Loader2, Hash, HardDrive, Calendar } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Badge } from "~/components/ui/badge"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from "~/components/ui/dialog"
import { toast } from "vue-sonner"
import type { Template, TemplateVersion } from "~/types/api"
import { formatBytes, timeAgo } from "~/lib/utils"

const props = defineProps<{
  open: boolean
  template: Template
}>()

const emit = defineEmits<{
  "update:open": [value: boolean]
  restored: []
}>()

const store = useTemplatesStore()
const { t } = useI18n()
const versions = ref<TemplateVersion[]>([])
const loading = ref(false)
const restoring = ref(false)
const confirmHash = ref<string | null>(null)

watch(() => props.open, async (isOpen) => {
  if (isOpen) {
    loading.value = true
    try {
      versions.value = await store.fetchVersions(props.template.name)
    }
    catch {
      toast.error(t("toast.templates.historyLoadFailed"), { description: t("toast.templates.historyLoadFailedDesc") })
    }
    finally {
      loading.value = false
    }
  } else {
    confirmHash.value = null
  }
})

async function rollback(hash: string) {
  restoring.value = true
  try {
    await store.rollbackToVersion(props.template.name, hash)
    emit("restored")
    emit("update:open", false)
  }
  catch {
    toast.error(t("toast.templates.restoreFailed"), { description: t("toast.templates.restoreFailedDesc") })
  }
  finally {
    restoring.value = false
    confirmHash.value = null
  }
}

function close() {
  if (!restoring.value) emit("update:open", false)
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
  })
}


</script>

<template>
  <Dialog :open="open" @update:open="close">
    <DialogContent class="bg-popover backdrop-blur-xl border-glass-border rounded-2xl sm:max-w-lg [&>button:last-child]:hidden overflow-hidden p-0">
      <!-- Hero -->
      <div class="relative h-32 bg-glass/40 overflow-hidden">
        <div class="absolute inset-0 bg-dot-pattern" />
        <div class="absolute inset-0 bg-gradient-to-b from-transparent via-transparent to-popover" />
        <div class="absolute inset-0 flex flex-col items-center justify-center gap-2">
          <div class="size-12 rounded-2xl bg-primary/10 border border-primary/20 flex items-center justify-center">
            <History class="size-6 text-primary" />
          </div>
          <div class="text-center">
            <DialogTitle class="text-base font-bold text-foreground">{{ t('components.versionHistory.title') }}</DialogTitle>
            <DialogDescription class="text-xs text-muted-foreground mt-0.5">{{ template.name }} &middot; {{ t('components.versionHistory.versionCount', { count: versions.length }, versions.length) }}</DialogDescription>
          </div>
        </div>
      </div>

      <div class="px-6 pb-6 flex flex-col gap-4 pt-4">
        <!-- Loading -->
        <div v-if="loading" class="flex items-center justify-center py-12">
          <Loader2 class="size-5 text-muted-foreground animate-spin" />
        </div>

        <!-- Empty -->
        <div v-else-if="versions.length === 0" class="text-center py-12">
          <History class="size-10 text-muted-foreground/30 mx-auto mb-3" />
          <p class="text-sm text-muted-foreground">{{ t('components.versionHistory.empty') }}</p>
        </div>

        <!-- Version list -->
        <div v-else class="flex flex-col gap-2 max-h-80 overflow-auto styled-scrollbar">
          <div
            v-for="(v, i) in versions"
            :key="v.hash"
            :class="[
              'flex items-center gap-3 px-4 py-3 rounded-xl border transition-all',
              v.hash === template.hash
                ? 'border-primary/30 bg-primary/5'
                : 'border-glass-border hover:bg-glass-hover',
            ]"
          >
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-1">
                <span class="text-xs font-mono text-muted-foreground truncate" :title="v.hash">
                  <Hash class="size-3 inline -mt-0.5" />{{ v.hash.slice(0, 12) }}
                </span>
                <Badge v-if="v.hash === template.hash" variant="outline" class="text-[10px] text-primary border-primary/30">
                  {{ t('components.versionHistory.current') }}
                </Badge>
                <Badge v-else-if="i === 0" variant="outline" class="text-[10px] text-success border-success/30">
                  {{ t('components.versionHistory.latest') }}
                </Badge>
              </div>
              <div class="flex items-center gap-3 text-[11px] text-muted-foreground">
                <span class="flex items-center gap-1"><HardDrive class="size-3" />{{ formatBytes(v.sizeBytes) }}</span>
                <span class="flex items-center gap-1"><Calendar class="size-3" />{{ formatDate(v.createdAt) }}</span>
                <span class="text-muted-foreground/60">{{ timeAgo(v.createdAt) }}</span>
              </div>
            </div>

            <!-- Rollback button -->
            <template v-if="v.hash !== template.hash">
              <Button
                v-if="confirmHash !== v.hash"
                variant="outline"
                size="sm"
                class="h-7 px-2 text-xs border-glass-border shrink-0"
                @click="confirmHash = v.hash"
              >
                <RotateCcw class="size-3 mr-1" /> {{ t('components.versionHistory.restore') }}
              </Button>
              <div v-else class="flex items-center gap-1 shrink-0">
                <Button
                  size="sm"
                  class="h-7 px-2 text-xs bg-warning hover:bg-warning/90 text-warning-foreground"
                  :disabled="restoring"
                  @click="rollback(v.hash)"
                >
                  <Loader2 v-if="restoring" class="size-3 mr-1 animate-spin" />
                  {{ t('components.versionHistory.confirm') }}
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  class="h-7 px-2 text-xs"
                  :disabled="restoring"
                  @click="confirmHash = null"
                >
                  {{ t('components.versionHistory.cancel') }}
                </Button>
              </div>
            </template>
          </div>
        </div>

        <!-- Footer -->
        <DialogFooter class="!flex-row gap-2 pt-4 border-t border-glass-border">
          <div class="flex-1" />
          <Button variant="outline" class="border-glass-border" @click="close">
            {{ t('components.versionHistory.close') }}
          </Button>
        </DialogFooter>
      </div>
    </DialogContent>
  </Dialog>
</template>
