<script setup lang="ts">
import { Upload, FileCode, Loader2, X } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
  DialogTrigger,
} from "~/components/ui/dialog"
import { toast } from "vue-sonner"

const store = useTemplatesStore()
const { t } = useI18n()

const open = ref(false)
const loading = ref(false)

const name = ref("")
const description = ref("")
const platform = ref("")
const file = ref<File | null>(null)
const dragging = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)

const nameValid = computed(() => /^[a-z0-9_][a-z0-9_-]*$/.test(name.value) && name.value.length <= 32)
const formValid = computed(() => nameValid.value && platform.value.trim().length > 0 && file.value !== null)

const nameError = computed(() => {
  if (!name.value) return null
  if (name.value.length > 32) return t("components.importTemplate.errorMaxChars")
  if (!/^[a-z0-9_][a-z0-9_-]*$/.test(name.value)) return t("components.importTemplate.errorNameFormat")
  if (store.templates.find(t => t.name === name.value)) return t("components.importTemplate.errorExists")
  return null
})

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function onDragOver(e: DragEvent) {
  e.preventDefault()
  dragging.value = true
}

function onDragLeave() {
  dragging.value = false
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  dragging.value = false
  const dropped = e.dataTransfer?.files?.[0]
  if (dropped && (dropped.name.endsWith(".tar.gz") || dropped.name.endsWith(".tgz"))) {
    file.value = dropped
  } else {
    toast.error(t("toast.templates.importInvalidFile"), { description: t("toast.templates.importInvalidFileDesc") })
  }
}

function onFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  const selected = input.files?.[0]
  if (selected) file.value = selected
  input.value = ""
}

function clearFile() {
  file.value = null
}

async function submit() {
  if (!formValid.value || !file.value) return
  loading.value = true
  try {
    const formData = new FormData()
    formData.append("name", name.value.trim())
    formData.append("description", description.value.trim())
    formData.append("platform", platform.value.trim())
    formData.append("file", file.value)
    await store.importTemplate(formData)
    open.value = false
  } catch {
    toast.error(t("toast.templates.importFailed"), { description: t("toast.templates.importFailedDesc") })
  } finally {
    loading.value = false
  }
}

function handleOpen(value: boolean) {
  open.value = value
  if (value) {
    name.value = ""
    description.value = ""
    platform.value = ""
    file.value = null
    dragging.value = false
  }
}
</script>

<template>
  <Dialog :open="open" @update:open="handleOpen">
    <DialogTrigger as-child>
      <Button variant="outline" class="border-glass-border">
        <Upload class="size-4 mr-2" />
        {{ t('components.importTemplate.import') }}
      </Button>
    </DialogTrigger>
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
            <DialogTitle class="text-base font-bold text-foreground">{{ t('components.importTemplate.title') }}</DialogTitle>
            <DialogDescription class="text-xs text-muted-foreground mt-0.5">{{ t('components.importTemplate.subtitle') }}</DialogDescription>
          </div>
        </div>
      </div>

      <div class="px-6 pb-8 flex flex-col gap-5 pt-5">
        <!-- File drop zone -->
        <div class="flex flex-col gap-1.5">
          <Label>{{ t('components.importTemplate.archiveFile') }}</Label>
          <div
            v-if="!file"
            :class="[
              'relative flex flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed py-8 px-4 transition-colors cursor-pointer',
              dragging ? 'border-primary bg-primary/5' : 'border-glass-border hover:border-glass-border-hover hover:bg-glass-hover/30',
            ]"
            @dragover="onDragOver"
            @dragleave="onDragLeave"
            @drop="onDrop"
            @click="fileInputRef?.click()"
          >
            <Upload :class="['size-8', dragging ? 'text-primary' : 'text-muted-foreground/40']" />
            <div class="text-center">
              <p class="text-sm text-foreground">
                {{ dragging ? t('components.importTemplate.dropHere') : t('components.importTemplate.dragOrClick') }}
              </p>
              <p class="text-xs text-muted-foreground mt-1">{{ t('components.importTemplate.fileTypeHint') }}</p>
            </div>
            <input
              ref="fileInputRef"
              type="file"
              accept=".tar.gz,.tgz"
              class="hidden"
              @change="onFileSelect"
            >
          </div>
          <div
            v-else
            class="flex items-center gap-3 rounded-xl border border-glass-border bg-glass/40 px-4 py-3"
          >
            <div class="size-9 rounded-lg bg-primary/10 flex items-center justify-center shrink-0">
              <FileCode class="size-4 text-primary" />
            </div>
            <div class="flex-1 min-w-0">
              <p class="text-sm font-medium text-foreground truncate">{{ file.name }}</p>
              <p class="text-xs text-muted-foreground">{{ formatFileSize(file.size) }}</p>
            </div>
            <button
              type="button"
              :aria-label="t('common.a11y.remove')"
              class="size-7 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors"
              @click.stop="clearFile"
            >
              <X class="size-4" />
            </button>
          </div>
        </div>

        <!-- Name -->
        <div class="flex flex-col gap-1.5">
          <Label for="import-template-name">{{ t('components.importTemplate.name') }}</Label>
          <Input
            id="import-template-name"
            v-model="name"
            :placeholder="t('components.templateForm.tagsPlaceholder')"
            autocomplete="off"
            class="bg-glass border-glass-border font-mono"
            @keydown.enter="submit"
          />
          <p v-if="nameError" class="text-xs text-destructive">{{ nameError }}</p>
        </div>

        <!-- Platform -->
        <div class="flex flex-col gap-1.5">
          <Label for="import-template-platform">{{ t('components.importTemplate.platform') }}</Label>
          <Input
            id="import-template-platform"
            v-model="platform"
            :placeholder="t('components.templateForm.platformsPlaceholder')"
            autocomplete="off"
            class="bg-glass border-glass-border"
            @keydown.enter="submit"
          />
        </div>

        <!-- Description -->
        <div class="flex flex-col gap-1.5">
          <Label for="import-template-description">{{ t('components.importTemplate.description') }} <span class="text-muted-foreground font-normal">{{ t('common.optional') }}</span></Label>
          <Input
            id="import-template-description"
            v-model="description"
            :placeholder="t('components.templateForm.descriptionPlaceholder')"
            autocomplete="off"
            class="bg-glass border-glass-border"
            @keydown.enter="submit"
          />
        </div>

        <!-- Footer -->
        <DialogFooter class="!flex-row gap-2 mt-2 pt-5 border-t border-glass-border">
          <div class="flex-1" />
          <Button
            class="bg-primary hover:bg-primary/90 text-primary-foreground"
            :disabled="!formValid || loading"
            @click="submit"
          >
            <Loader2 v-if="loading" class="size-4 mr-1.5 animate-spin" />
            {{ loading ? t('components.importTemplate.importing') : t('components.importTemplate.import') }}
          </Button>
        </DialogFooter>
      </div>
    </DialogContent>
  </Dialog>
</template>
