<script setup lang="ts">
import { Upload, FileUp, Loader2, X } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from "~/components/ui/dialog"
import { toast } from "vue-sonner"
import { formatBytes } from "~/lib/utils"

const props = defineProps<{
  open: boolean
  templateName: string
  targetPath?: string
}>()

const emit = defineEmits<{
  "update:open": [value: boolean]
  uploaded: []
}>()

const store = useTemplatesStore()
const { t } = useI18n()
const uploading = ref(false)
const selectedFiles = ref<FileList | null>(null)
const dragOver = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

function close() {
  if (!uploading.value) {
    emit("update:open", false)
    selectedFiles.value = null
  }
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  dragOver.value = false
  if (e.dataTransfer?.files.length) {
    selectedFiles.value = e.dataTransfer.files
  }
}

function onDragOver(e: DragEvent) {
  e.preventDefault()
  dragOver.value = true
}

function onDragLeave() {
  dragOver.value = false
}

function onFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files?.length) {
    selectedFiles.value = input.files
  }
}

function clearSelection() {
  selectedFiles.value = null
  if (fileInput.value) fileInput.value.value = ""
}

async function upload() {
  if (!selectedFiles.value?.length) return
  uploading.value = true
  try {
    await store.uploadFiles(props.templateName, props.targetPath ?? "", selectedFiles.value)
    emit("uploaded")
    emit("update:open", false)
    selectedFiles.value = null
  }
  catch {
    toast.error(t("toast.templates.uploadFailed"), { description: t("toast.templates.uploadFailedDesc") })
  }
  finally {
    uploading.value = false
  }
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
            <Upload class="size-6 text-primary" />
          </div>
          <div class="text-center">
            <DialogTitle class="text-base font-bold text-foreground">{{ t('components.uploadFiles.title') }}</DialogTitle>
            <DialogDescription class="text-xs text-muted-foreground mt-0.5">
              {{ t('components.uploadFiles.target', { path: targetPath ? `/${targetPath}` : 'root', template: templateName }) }}
            </DialogDescription>
          </div>
        </div>
      </div>

      <div class="px-6 pb-8 flex flex-col gap-5 pt-5">
        <!-- Drop zone -->
        <div
          :class="[
            'relative border-2 border-dashed rounded-xl p-8 text-center transition-all cursor-pointer',
            dragOver
              ? 'border-primary bg-primary/5'
              : selectedFiles?.length
                ? 'border-success/40 bg-success/5'
                : 'border-glass-border hover:border-primary/40 hover:bg-glass-hover',
          ]"
          @dragover="onDragOver"
          @dragleave="onDragLeave"
          @drop="onDrop"
          @click="fileInput?.click()"
        >
          <input
            ref="fileInput"
            type="file"
            multiple
            class="hidden"
            @change="onFileSelect"
          >

          <template v-if="!selectedFiles?.length">
            <FileUp class="size-8 text-muted-foreground/40 mx-auto mb-3" />
            <p class="text-sm text-foreground font-medium">{{ t('components.uploadFiles.dropZone') }}</p>
            <p class="text-xs text-muted-foreground mt-1">{{ t('components.uploadFiles.zipHint') }}</p>
          </template>

          <template v-else>
            <div class="flex flex-col gap-1.5">
              <div
                v-for="(file, i) in Array.from(selectedFiles).slice(0, 5)"
                :key="i"
                class="flex items-center gap-2 text-sm"
              >
                <FileUp class="size-3.5 text-success shrink-0" />
                <span class="text-foreground truncate">{{ file.name }}</span>
                <span class="text-xs text-muted-foreground tabular-nums shrink-0">{{ formatBytes(file.size) }}</span>
              </div>
              <p v-if="selectedFiles.length > 5" class="text-xs text-muted-foreground mt-1">
                {{ t('components.uploadFiles.moreFiles', { n: selectedFiles.length - 5 }, selectedFiles.length - 5) }}
              </p>
            </div>
            <button
              :aria-label="t('common.a11y.remove')"
              class="absolute top-2 right-2 size-6 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors"
              @click.stop="clearSelection"
            >
              <X class="size-4" />
            </button>
          </template>
        </div>

        <!-- Footer -->
        <DialogFooter class="!flex-row gap-2 mt-2 pt-5 border-t border-glass-border">
          <Button variant="outline" class="border-glass-border" :disabled="uploading" @click="close">
            {{ t('components.uploadFiles.cancel') }}
          </Button>
          <div class="flex-1" />
          <Button
            class="bg-primary hover:bg-primary/90 text-primary-foreground"
            :disabled="!selectedFiles?.length || uploading"
            @click="upload"
          >
            <Loader2 v-if="uploading" class="size-4 mr-1.5 animate-spin" />
            {{ uploading ? t('components.uploadFiles.uploading') : t('components.uploadFiles.uploadButton', { n: selectedFiles?.length ?? 0 }, selectedFiles?.length ?? 0) }}
          </Button>
        </DialogFooter>
      </div>
    </DialogContent>
  </Dialog>
</template>
