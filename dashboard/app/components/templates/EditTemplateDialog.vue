<script setup lang="ts">
import { Pencil, Loader2 } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from "~/components/ui/dialog"
import { toast } from "vue-sonner"
import type { Template } from "~/types/api"

const props = defineProps<{
  open: boolean
  template: Template
}>()

const emit = defineEmits<{
  "update:open": [value: boolean]
  updated: [template: Template]
}>()

const store = useTemplatesStore()
const { t } = useI18n()
const loading = ref(false)

const description = ref("")
const platform = ref("")

watch(() => props.open, (isOpen) => {
  if (isOpen) {
    description.value = props.template.description
    platform.value = props.template.platform
  }
})

const hasChanges = computed(() =>
  description.value !== props.template.description || platform.value !== props.template.platform,
)

const formValid = computed(() => platform.value.trim().length > 0 && hasChanges.value)

async function submit() {
  if (!formValid.value) return
  loading.value = true
  try {
    const updated = await store.updateTemplate(props.template.name, {
      description: description.value.trim(),
      platform: platform.value.trim(),
    })
    if (updated) emit("updated", updated as Template)
    emit("update:open", false)
  }
  catch {
    toast.error(t("toast.templates.updateFailed"), { description: t("toast.templates.updateFailedDesc") })
  }
  finally {
    loading.value = false
  }
}

function close() {
  if (!loading.value) emit("update:open", false)
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
            <Pencil class="size-6 text-primary" />
          </div>
          <div class="text-center">
            <DialogTitle class="text-base font-bold text-foreground">Edit {{ template.name }}</DialogTitle>
            <DialogDescription class="text-xs text-muted-foreground mt-0.5">Update template metadata.</DialogDescription>
          </div>
        </div>
      </div>

      <div class="px-6 pb-8 flex flex-col gap-5 pt-5">
        <!-- Platform -->
        <div class="flex flex-col gap-1.5">
          <Label for="edit-platform">Platform</Label>
          <Input
            id="edit-platform"
            v-model="platform"
            placeholder="e.g. paper, velocity"
            autocomplete="off"
            class="bg-glass border-glass-border"
            @keydown.enter="submit"
          />
        </div>

        <!-- Description -->
        <div class="flex flex-col gap-1.5">
          <Label for="edit-description">Description</Label>
          <Input
            id="edit-description"
            v-model="description"
            placeholder="What this template is for"
            autocomplete="off"
            class="bg-glass border-glass-border"
            @keydown.enter="submit"
          />
        </div>

        <!-- Footer -->
        <DialogFooter class="!flex-row gap-2 mt-2 pt-5 border-t border-glass-border">
          <Button variant="outline" class="border-glass-border" :disabled="loading" @click="close">
            Cancel
          </Button>
          <div class="flex-1" />
          <Button
            class="bg-primary hover:bg-primary/90 text-primary-foreground"
            :disabled="!formValid || loading"
            @click="submit"
          >
            <Loader2 v-if="loading" class="size-4 mr-1.5 animate-spin" />
            {{ loading ? 'Saving...' : 'Save Changes' }}
          </Button>
        </DialogFooter>
      </div>
    </DialogContent>
  </Dialog>
</template>
