<script setup lang="ts">
import {
  Plus,
  Server,
  Network,
  FileCode,
  ArrowRight,
  ArrowLeft,
  Loader2,
  Package,
  Download,
  Sparkles
} from "lucide-vue-next"
import {Button} from "~/components/ui/button"
import {Input} from "~/components/ui/input"
import {Label} from "~/components/ui/label"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "~/components/ui/dialog"
import {toast} from "vue-sonner"

const props = defineProps<{
  platform?: string
  controlled?: boolean
  modelValue?: boolean
}>()

const emit = defineEmits<{
  "update:modelValue": [value: boolean]
}>()

const store = useCatalogStore()
const { t } = useI18n()

const internalOpen = ref(false)
const open = computed({
  get: () => props.controlled ? (props.modelValue ?? false) : internalOpen.value,
  set: (v: boolean) => {
    internalOpen.value = v
    if (props.controlled) emit("update:modelValue", v)
  },
})
const loading = ref(false)
const step = ref(1)
const direction = ref<"forward" | "backward">("forward")

const platformName = ref("")
const version = ref("")
const downloadUrl = ref("")
const category = ref<"SERVER" | "PROXY">("SERVER")
const configFormat = ref("")

// When a platform prop is passed, we skip step 1
const totalSteps = computed(() => props.platform ? 1 : 2)
const currentStep = computed(() => props.platform ? 1 : step.value)

const isNewPlatform = computed(() => {
  const name = props.platform ?? platformName.value
  return !store.entries.find(e => e.platform === name)
})

const configFormatsByCategory: Record<string, string[]> = {
  SERVER: ["paper", "spigot"],
  PROXY: ["velocity", "bungeecord"],
}

const configFormatOptions = computed(() => configFormatsByCategory[category.value])

const stepOneValid = computed(() => {
  if (props.platform) return true
  return platformName.value.trim().length > 0
})

const stepTwoValid = computed(() => {
  return version.value.trim().length > 0 && downloadUrl.value.trim().length > 0
})

function nextStep() {
  if (!stepOneValid.value) return
  direction.value = "forward"
  step.value = 2
}

function prevStep() {
  direction.value = "backward"
  step.value = 1
}

async function submit() {
  if (!stepTwoValid.value) return

  const name = (props.platform ?? platformName.value.trim()).toLowerCase()
  loading.value = true
  try {
    await store.addVersion(name, {
      version: version.value.trim(),
      downloadUrl: downloadUrl.value.trim(),
      ...(isNewPlatform.value ? {category: category.value, configFormat: configFormat.value || undefined} : {}),
    })
    open.value = false
  } catch {
    toast.error(t("toast.catalog.addVersionFailed"), {
      description: t("toast.catalog.addVersionFailedDesc"),
    })
  } finally {
    loading.value = false
  }
}

function handleOpen(value: boolean) {
  open.value = value
  if (value) {
    step.value = 1
    direction.value = "forward"
    platformName.value = ""
    version.value = ""
    downloadUrl.value = ""
    category.value = "SERVER"
    configFormat.value = ""
  }
}
</script>

<template>
  <Dialog :open="open" @update:open="handleOpen">
    <DialogTrigger v-if="!controlled" as-child>
      <Button v-if="!platform" class="bg-primary hover:bg-primary/90 text-primary-foreground">
        <Plus class="size-5 mr-2"/>
        {{ t('components.addVersion.addPlatform') }}
      </Button>
      <Button v-else variant="ghost" size="sm" class="text-xs text-muted-foreground h-7 px-2">
        <Plus class="size-3 mr-1"/>
        {{ t('components.addVersion.add') }}
      </Button>
    </DialogTrigger>
    <DialogContent
        class="bg-popover backdrop-blur-xl border-glass-border rounded-2xl sm:max-w-xl [&>button:last-child]:hidden overflow-hidden p-0">
      <!-- Hero: Gradient orbs + grid overlay -->
      <div class="relative h-36 bg-glass/40 overflow-hidden">
        <!-- Floating gradient orbs -->
        <div class="absolute top-4 left-1/4 size-28 rounded-full bg-primary/20 blur-3xl animate-pulse"/>
        <div
            class="absolute bottom-2 right-1/4 size-24 rounded-full bg-primary/15 blur-3xl animate-pulse [animation-delay:1s]"/>
        <div
            class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 size-32 rounded-full bg-primary/10 blur-3xl animate-pulse [animation-delay:2s]"/>
        <!-- Fine grid overlay -->
        <div
class="absolute inset-0"
             style="background-image: linear-gradient(oklch(0.75 0.18 55 / 0.04) 1px, transparent 1px), linear-gradient(90deg, oklch(0.75 0.18 55 / 0.04) 1px, transparent 1px); background-size: 12px 12px;"/>
        <div class="absolute inset-0 bg-linear-to-b from-transparent via-transparent to-popover"/>
        <div class="absolute inset-0 flex flex-col items-center justify-center gap-2">
          <!-- Icon badge with glow -->
          <div class="relative">
            <div class="absolute inset-0 bg-primary/30 blur-xl rounded-full scale-150"/>
            <div
                class="relative size-12 rounded-2xl bg-primary/10 border border-primary/20 flex items-center justify-center">
              <component
:is="platform ? Download : currentStep === 1 ? Package : Download"
                         class="size-6 text-primary"/>
            </div>
          </div>
          <DialogHeader class="text-center sm:text-center gap-0.5">
            <DialogTitle class="text-base font-bold text-foreground">
              {{ platform ? t('components.addVersion.titleAddVersionTo', { platform }) : currentStep === 1 ? t('components.addVersion.titleNewPlatform') : t('components.addVersion.titleAddInitialVersion') }}
            </DialogTitle>
            <DialogDescription class="text-xs text-muted-foreground">
              {{
                platform
                    ? t('components.addVersion.descRegister')
                    : currentStep === 1
                        ? t('components.addVersion.descConfigure')
                        : t('components.addVersion.descSetupFirst', { platform: platformName })
              }}
            </DialogDescription>
          </DialogHeader>
        </div>
      </div>

      <div class="px-8 pb-8 flex flex-col gap-5 pt-5">

        <!-- Step indicator: numbered circles connected by line -->
        <div v-if="!platform" class="flex items-center justify-center gap-0">
          <template v-for="s in totalSteps" :key="s">
            <div class="flex items-center">
              <div
                  :class="[
                'size-6 rounded-full flex items-center justify-center text-xs font-semibold transition-all duration-300',
                s <= currentStep
                  ? 'bg-primary text-primary-foreground ring-4 ring-primary/15'
                  : 'bg-glass-border text-muted-foreground',
              ]"
              >
                {{ s }}
              </div>
              <span
                  :class="[
                'ml-1.5 text-xs font-medium transition-colors duration-300',
                s <= currentStep ? 'text-foreground' : 'text-muted-foreground',
              ]"
              >
              {{ s === 1 ? t('components.addVersion.stepPlatform') : t('components.addVersion.stepVersion') }}
            </span>
            </div>
            <!-- Connector line between steps -->
            <div
                v-if="s < totalSteps"
                :class="[
              'mx-3 h-px w-12 transition-colors duration-300',
              s < currentStep ? 'bg-primary' : 'bg-glass-border',
            ]"
            />
          </template>
        </div>

        <!-- Step content with slide transitions -->
        <div class="relative overflow-hidden py-1 -my-1 px-1 -mx-1">
          <Transition :name="direction === 'forward' ? 'slide-left' : 'slide-right'" mode="out-in">
            <!-- Step 1: Platform details (new platform only) -->
            <div v-if="!platform && step === 1" key="step-1" class="flex flex-col gap-4">
              <!-- Platform name -->
              <div class="flex flex-col gap-1.5">
                <Label for="platform-name" class="uppercase tracking-wider text-xs">{{ t('components.addVersion.platformName') }}</Label>
                <Input
                    id="platform-name"
                    v-model="platformName"
                    :placeholder="t('components.addVersion.platformNamePlaceholder')"
                    autocomplete="off"
                    class="bg-glass border-glass-border"
                    @keydown.enter="nextStep"
                />
              </div>

              <!-- Category -->
              <div class="flex flex-col gap-1.5">
                <Label class="uppercase tracking-wider text-xs">{{ t('components.addVersion.category') }}</Label>
                <div class="grid grid-cols-2 gap-2">
                  <button
                      :class="[
                    'flex items-center gap-2.5 px-3 py-2.5 rounded-xl border text-left transition-all hover:-translate-y-0.5',
                    category === 'SERVER'
                      ? 'border-success/40 bg-success/5 shadow-[0_0_20px_-4px] shadow-success/30'
                      : 'border-glass-border hover:bg-glass-hover',
                  ]"
                      @click="category = 'SERVER'; configFormat = ''"
                  >
                    <div
                        :class="['size-9 rounded-xl flex items-center justify-center', category === 'SERVER' ? 'bg-success/10' : 'bg-glass']">
                      <Server
class="size-4 shrink-0"
                              :class="category === 'SERVER' ? 'text-success' : 'text-muted-foreground'"/>
                    </div>
                    <div>
                      <p
class="text-sm font-medium"
                         :class="category === 'SERVER' ? 'text-foreground' : 'text-muted-foreground'">{{ t('components.addVersion.server') }}</p>
                      <p class="text-[10px] text-muted-foreground">{{ t('components.addVersion.gameServers') }}</p>
                    </div>
                  </button>
                  <button
                      :class="[
                    'flex items-center gap-2.5 px-3 py-2.5 rounded-xl border text-left transition-all hover:-translate-y-0.5',
                    category === 'PROXY'
                      ? 'border-primary/40 bg-primary/5 shadow-[0_0_20px_-4px] shadow-primary/30'
                      : 'border-glass-border hover:bg-glass-hover',
                  ]"
                      @click="category = 'PROXY'; configFormat = ''"
                  >
                    <div
                        :class="['size-9 rounded-xl flex items-center justify-center', category === 'PROXY' ? 'bg-primary/10' : 'bg-glass']">
                      <Network
class="size-4 shrink-0"
                               :class="category === 'PROXY' ? 'text-primary' : 'text-muted-foreground'"/>
                    </div>
                    <div>
                      <p
class="text-sm font-medium"
                         :class="category === 'PROXY' ? 'text-foreground' : 'text-muted-foreground'">{{ t('components.addVersion.proxy') }}</p>
                      <p class="text-[10px] text-muted-foreground">{{ t('components.addVersion.networkProxies') }}</p>
                    </div>
                  </button>
                </div>
              </div>

              <!-- Config Format: pill buttons -->
              <div class="flex flex-col gap-1.5">
                <Label class="uppercase tracking-wider text-xs">{{ t('components.addVersion.configFormat') }}</Label>
                <div class="flex flex-wrap items-center gap-2">
                  <button
                      v-for="fmt in configFormatOptions"
                      :key="fmt"
                      :class="[
                    'inline-flex items-center gap-2 px-4 py-2 rounded-full border text-sm font-medium capitalize transition-all',
                    configFormat === fmt
                      ? 'border-primary/40 bg-primary/5 text-foreground shadow-[0_0_20px_-4px] shadow-primary/30'
                      : 'border-glass-border text-muted-foreground hover:bg-glass-hover',
                  ]"
                      @click="configFormat = fmt"
                  >
                    <FileCode
class="size-4 shrink-0"
                              :class="configFormat === fmt ? 'text-primary' : 'text-muted-foreground'"/>
                    {{ fmt }}
                  </button>
                </div>
              </div>
            </div>

            <!-- Step 2: Version details (or only step when adding to existing platform) -->
            <div v-else-if="platform || step === 2" key="step-2" class="flex flex-col gap-4">
              <!-- Platform badge pill (new platform flow) -->
              <div v-if="!platform && isNewPlatform" class="flex items-center gap-2">
              <span
                  class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-primary/10 border border-primary/20 text-xs font-medium text-primary">
                <Sparkles class="size-3"/>
                <span class="uppercase">{{ platformName }}</span>
              </span>
              </div>

              <!-- Version -->
              <div class="flex flex-col gap-1.5">
                <Label for="version" class="uppercase tracking-wider text-xs">{{ t('components.addVersion.version') }}</Label>
                <Input
                    id="version"
                    v-model="version"
                    placeholder="e.g. 1.21.4"
                    autocomplete="off"
                    class="bg-glass border-glass-border font-mono"
                    @keydown.enter="submit"
                />
              </div>

              <!-- Download URL -->
              <div class="flex flex-col gap-1.5">
                <Label for="download-url" class="uppercase tracking-wider text-xs">{{ t('components.addVersion.downloadUrl') }}</Label>
                <Input
                    id="download-url"
                    v-model="downloadUrl"
                    placeholder="https://api.papermc.io/v2/..."
                    autocomplete="off"
                    class="bg-glass border-glass-border font-mono text-xs"
                    @keydown.enter="submit"
                />
              </div>
            </div>
          </Transition>
        </div>

        <!-- Footer -->
        <DialogFooter class="flex-row! gap-2 mt-2 pt-5 border-t border-glass-border">
          <!-- Back button (step 2 of new platform flow only) -->
          <Button
              v-if="!platform && step === 2"
              variant="outline"
              class="border-glass-border"
              @click="prevStep"
          >
            <ArrowLeft class="size-4 mr-1.5"/>
            {{ t('components.addVersion.back') }}
          </Button>

          <div class="flex-1"/>

          <!-- Next / Submit -->
          <Button
              v-if="!platform && step === 1"
              class="bg-primary hover:bg-primary/90 text-primary-foreground hover:shadow-[0_4px_16px_-4px] hover:shadow-primary/30"
              :disabled="!stepOneValid"
              @click="nextStep"
          >
            {{ t('components.addVersion.continue') }}
            <ArrowRight class="size-4 ml-1.5"/>
          </Button>

          <Button
              v-else
              class="bg-primary hover:bg-primary/90 text-primary-foreground hover:shadow-[0_4px_16px_-4px] hover:shadow-primary/30"
              :disabled="!stepTwoValid || loading"
              @click="submit"
          >
            <Loader2 v-if="loading" class="size-4 mr-1.5 animate-spin"/>
            {{ loading ? t('components.addVersion.adding') : t('components.addVersion.addVersion') }}
          </Button>
        </DialogFooter>
      </div>
    </DialogContent>
  </Dialog>
</template>

<style scoped>
/* Slide-left: new content slides in from right, old slides out to left */
.slide-left-enter-active,
.slide-left-leave-active {
  transition: all 0.25s ease;
}

.slide-left-enter-from {
  opacity: 0;
  transform: translateX(24px);
}

.slide-left-leave-to {
  opacity: 0;
  transform: translateX(-24px);
}

/* Slide-right: new content slides in from left, old slides out to right */
.slide-right-enter-active,
.slide-right-leave-active {
  transition: all 0.25s ease;
}

.slide-right-enter-from {
  opacity: 0;
  transform: translateX(-24px);
}

.slide-right-leave-to {
  opacity: 0;
  transform: translateX(24px);
}
</style>
