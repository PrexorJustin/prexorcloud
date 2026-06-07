<script setup lang="ts">
import { AlertTriangle } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog"

const props = withDefaults(defineProps<{
  open: boolean
  title?: string
  description?: string
  confirmLabel?: string
  confirmVariant?: "default" | "destructive"
  loading?: boolean
}>(), {
  confirmVariant: "destructive",
  loading: false,
})

const emit = defineEmits<{
  "update:open": [value: boolean]
  confirm: []
}>()

const { t } = useI18n()

const resolvedTitle = computed(() => props.title ?? t("confirmDialog.title"))
const resolvedDescription = computed(() => props.description ?? t("confirmDialog.description"))
const resolvedConfirmLabel = computed(() => props.confirmLabel ?? t("common.confirm"))

function close() {
  if (!props.loading) emit("update:open", false)
}
</script>

<template>
  <Dialog :open="open" @update:open="close">
    <DialogContent class="bg-popover backdrop-blur-xl border-glass-border rounded-2xl sm:max-w-md [&>button:last-child]:hidden">
      <DialogHeader>
        <div class="flex items-center gap-3">
          <div class="size-10 rounded-xl bg-destructive/20 flex items-center justify-center shrink-0">
            <AlertTriangle class="size-5 text-destructive" />
          </div>
          <div>
            <DialogTitle>{{ resolvedTitle }}</DialogTitle>
            <DialogDescription class="mt-1">{{ resolvedDescription }}</DialogDescription>
          </div>
        </div>
      </DialogHeader>
      <DialogFooter class="mt-4 gap-2">
        <Button variant="outline" class="border-glass-border" :disabled="loading" @click="close">
          {{ t('common.cancel') }}
        </Button>
        <Button
          :class="confirmVariant === 'destructive'
            ? 'bg-destructive hover:bg-destructive/90 text-destructive-foreground'
            : 'bg-primary hover:bg-primary/90 text-primary-foreground'"
          :disabled="loading"
          @click="$emit('confirm')"
        >
          {{ loading ? t('common.pleaseWait') : resolvedConfirmLabel }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
