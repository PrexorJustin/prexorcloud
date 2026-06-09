<script setup lang="ts">
import {Plus, Key, Loader2} from "lucide-vue-next"
import type {JoinTokenCreated} from "~/types/api"
import {Button} from "~/components/ui/button"
import {Input} from "~/components/ui/input"
import {Label} from "~/components/ui/label"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
  DialogTrigger,
} from "~/components/ui/dialog"
import {toast} from "vue-sonner"

const { t } = useI18n()
const { open, loading, error, reset } = useDialogState()

const nodeName = ref("")
const ttlMinutes = ref(60)

const ttlPresets = [
  {label: "15m", value: 15},
  {label: "30m", value: 30},
  {label: "1h", value: 60},
  {label: "2h", value: 120},
  {label: "4h", value: 240},
  {label: "6h", value: 360},
]

const ttlDisplay = computed(() => {
  const m = ttlMinutes.value
  if (m < 60) return t('components.addNode.minutes', { count: m })
  if (m < 1440) return t('components.addNode.hours', { count: (m / 60).toFixed(m % 60 === 0 ? 0 : 1) })
  return t('components.addNode.days', { count: (m / 1440).toFixed(m % 1440 === 0 ? 0 : 1) })
})

const formValid = computed(() => nodeName.value.trim().length > 0)

async function generateToken() {
  if (!formValid.value) return
  loading.value = true
  error.value = ""
  try {
    const { data: result } = await useApiClient().POST('/api/v1/admin/tokens', {
      body: { nodeId: nodeName.value.trim(), ttlSeconds: ttlMinutes.value * 60 },
    })

    // Auto-copy to clipboard and close
    await navigator.clipboard.writeText((result as JoinTokenCreated).joinToken)
    await useNodesStore().fetchNodes()
    open.value = false

    toast.success(t('components.addNode.copiedTitle'), {
      description: t('components.addNode.copiedDesc', { name: nodeName.value.trim(), ttl: ttlDisplay.value }),
    })
  } catch {
    error.value = t('components.addNode.genFailed')
  } finally {
    loading.value = false
  }
}

function handleOpen(value: boolean) {
  open.value = value
  if (!value) {
    reset()
    nodeName.value = ""
    ttlMinutes.value = 60
  }
}
</script>

<template>
  <Dialog :open="open" @update:open="handleOpen">
    <DialogTrigger as-child>
      <Button class="bg-primary hover:bg-primary/90 text-primary-foreground">
        <Plus class="size-5 mr-2"/>
        {{ t('components.addNode.generateToken') }}
      </Button>
    </DialogTrigger>
    <DialogContent
        class="bg-popover backdrop-blur-xl border-glass-border rounded-2xl sm:max-w-lg [&>button:last-child]:hidden overflow-hidden p-0">
      <!-- Hero: Dot pattern with centered icon -->
      <div class="relative h-32 bg-glass/40 overflow-hidden">
        <div class="absolute inset-0 bg-dot-pattern"/>
        <div class="absolute inset-0 bg-gradient-to-b from-transparent via-transparent to-popover"/>
        <div class="absolute inset-0 flex flex-col items-center justify-center gap-2">
          <div class="size-12 rounded-2xl bg-primary/10 border border-primary/20 flex items-center justify-center">
            <Key class="size-6 text-primary"/>
          </div>
          <div class="text-center">
            <DialogTitle class="text-base font-bold text-foreground">{{ t('components.addNode.dialogTitle') }}</DialogTitle>
            <DialogDescription class="text-xs text-muted-foreground mt-0.5">
              {{ t('components.addNode.dialogDesc') }}
            </DialogDescription>
          </div>
        </div>
      </div>

      <div class="px-6 pb-8 flex flex-col gap-5 pt-5">
        <!-- Node Name -->
        <div class="flex flex-col gap-2">
          <Label for="node-name">{{ t('components.addNode.nodeName') }}</Label>
          <Input
              id="node-name"
              v-model="nodeName"
              :placeholder="t('components.addNode.namePlaceholder')"
              autocomplete="off"
              name="node-name-unique"
              class="bg-glass border-glass-border"
              @keydown.enter="generateToken"
          />
        </div>

        <!-- TTL Slider -->
        <div class="flex flex-col gap-3">
          <div class="flex items-center justify-between">
            <Label>{{ t('components.addNode.tokenTtl') }}</Label>
            <span class="text-sm text-foreground font-medium">{{ ttlDisplay }}</span>
          </div>
          <input
              v-model.number="ttlMinutes"
              type="range"
              :min="5"
              :max="360"
              :step="5"
              class="w-full accent-primary h-1.5 rounded-full appearance-none bg-glass-border cursor-pointer [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:size-4 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-primary [&::-webkit-slider-thumb]:cursor-pointer"
          >
          <!-- Preset hints -->
          <div class="flex gap-2">
            <button
                v-for="preset in ttlPresets"
                :key="preset.value"
                :class="[
                'px-2.5 py-1 text-xs rounded-lg transition-all',
                ttlMinutes === preset.value
                  ? 'bg-primary text-primary-foreground'
                  : 'bg-glass text-muted-foreground hover:text-foreground hover:bg-glass-hover',
              ]"
                @click="ttlMinutes = preset.value"
            >
              {{ preset.label }}
            </button>
          </div>
        </div>

        <!-- Error -->
        <p v-if="error" class="text-sm text-destructive">{{ error }}</p>

        <!-- Footer -->
        <DialogFooter class="flex-row! gap-2 mt-2 pt-5 border-t border-glass-border">
          <div class="flex-1"/>
          <Button
              class="bg-primary hover:bg-primary/90 text-primary-foreground"
              :disabled="!formValid || loading"
              @click="generateToken"
          >
            <Loader2 v-if="loading" class="size-4 mr-1.5 animate-spin"/>
            {{ loading ? t('components.addNode.generating') : t('components.addNode.generateCopy') }}
          </Button>
        </DialogFooter>
      </div>
    </DialogContent>
  </Dialog>
</template>
