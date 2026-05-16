<script setup lang="ts">
import { Plus, FileCode, Loader2, ArrowRight, ArrowLeft, Check, Search, Server, Network, Package } from "lucide-vue-next"
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
import AddVersionDialog from "~/components/catalog/AddVersionDialog.vue"
import type { CatalogEntry } from "~/types/api"

const store = useTemplatesStore()
const catalogStore = useCatalogStore()
const { t } = useI18n()

const open = ref(false)
const loading = ref(false)
const catalogDialogOpen = ref(false)
const step = ref(1)
const platformSearch = ref("")

const name = ref("")
const description = ref("")
const platform = ref("")

const platformColors: Record<string, { dot: string; icon: string }> = {
  paper: { dot: "bg-red-400", icon: "text-red-400" },
  spigot: { dot: "bg-yellow-400", icon: "text-yellow-400" },
  purpur: { dot: "bg-violet-400", icon: "text-violet-400" },
  velocity: { dot: "bg-teal-400", icon: "text-teal-400" },
  bungeecord: { dot: "bg-amber-400", icon: "text-amber-400" },
  waterfall: { dot: "bg-blue-400", icon: "text-blue-400" },
}

const defaultColor = { dot: "bg-muted-foreground/40", icon: "text-muted-foreground" }

function getColor(p: string) {
  return platformColors[p] ?? defaultColor
}

const servers = computed(() =>
  catalogStore.entries.filter(e => e.category === "SERVER"),
)
const proxies = computed(() =>
  catalogStore.entries.filter(e => e.category === "PROXY"),
)

function filterEntries(entries: CatalogEntry[]) {
  const q = platformSearch.value.toLowerCase().trim()
  if (!q) return entries
  return entries.filter(e => e.platform.toLowerCase().includes(q))
}

const filteredServers = computed(() => filterEntries(servers.value))
const filteredProxies = computed(() => filterEntries(proxies.value))
const hasResults = computed(() => filteredServers.value.length > 0 || filteredProxies.value.length > 0)

const selectedEntry = computed(() =>
  catalogStore.entries.find(e => e.platform === platform.value) ?? null,
)

// Auto-select newly added platform and advance to step 2
watch(() => catalogStore.entries, (curr, prev) => {
  if (open.value && curr.length > (prev?.length ?? 0)) {
    const prevNames = new Set((prev ?? []).map(e => e.platform))
    const added = curr.find(e => !prevNames.has(e.platform))
    if (added) {
      platform.value = added.platform
      step.value = 2
    }
  }
})

function selectPlatform(p: string) {
  platform.value = p
}

const heroTitle = computed(() =>
  step.value === 1 ? "Choose platform" : "Template details",
)
const heroDescription = computed(() =>
  step.value === 1
    ? "Select the platform this template targets."
    : `Creating template for ${platform.value}.`,
)

const nameValid = computed(() => /^[a-z0-9_][a-z0-9_-]*$/.test(name.value) && name.value.length <= 32)
const formValid = computed(() => nameValid.value && platform.value.trim().length > 0)

const nameError = computed(() => {
  if (!name.value) return null
  if (name.value.length > 32) return "Max 32 characters"
  if (!/^[a-z0-9_][a-z0-9_-]*$/.test(name.value)) return "Lowercase letters, numbers, underscore, hyphen only"
  if (store.templates.find(t => t.name === name.value)) return "Template already exists"
  return null
})

async function submit() {
  if (!formValid.value) return
  loading.value = true
  try {
    await store.createTemplate({
      name: name.value.trim(),
      description: description.value.trim(),
      platform: platform.value.trim(),
    })
    open.value = false
  }
  catch {
    toast.error(t("toast.templates.createFailed"), { description: t("toast.templates.createFailedDesc") })
  }
  finally {
    loading.value = false
  }
}

function handleOpen(value: boolean) {
  open.value = value
  if (value) {
    step.value = 1
    name.value = ""
    description.value = ""
    platform.value = ""
    platformSearch.value = ""
    catalogStore.fetchCatalog()
  }
}
</script>

<template>
  <div>
    <Dialog :open="open" @update:open="handleOpen">
      <DialogTrigger as-child>
        <Button class="bg-primary hover:bg-primary/90 text-primary-foreground">
          <Plus class="size-5 mr-2" />
          New Template
        </Button>
      </DialogTrigger>
      <DialogContent class="bg-popover backdrop-blur-xl border-glass-border rounded-2xl sm:max-w-lg [&>button:last-child]:hidden overflow-hidden p-0">
        <!-- Hero -->
        <div class="relative h-32 bg-glass/40 overflow-hidden">
          <div class="absolute inset-0 bg-dot-pattern" />
          <div class="absolute inset-0 bg-gradient-to-b from-transparent via-transparent to-popover" />
          <div class="absolute inset-0 flex flex-col items-center justify-center gap-2">
            <div class="size-12 rounded-2xl bg-primary/10 border border-primary/20 flex items-center justify-center">
              <component :is="step === 1 ? Package : FileCode" class="size-6 text-primary" />
            </div>
            <div class="text-center">
              <DialogTitle class="text-base font-bold text-foreground">{{ heroTitle }}</DialogTitle>
              <DialogDescription class="text-xs text-muted-foreground mt-0.5">{{ heroDescription }}</DialogDescription>
            </div>
          </div>
        </div>

        <div class="px-6 pb-8 flex flex-col gap-5 pt-5">
          <!-- Step indicator -->
          <div class="flex items-center gap-2">
            <div :class="['h-1 flex-1 rounded-full transition-all duration-300', step >= 1 ? 'bg-primary' : 'bg-glass-border']" />
            <div :class="['h-1 flex-1 rounded-full transition-all duration-300', step >= 2 ? 'bg-primary' : 'bg-glass-border']" />
          </div>

          <!-- Step 1: Platform selection -->
          <template v-if="step === 1">
            <!-- Search -->
            <div v-if="catalogStore.entries.length > 6" class="relative">
              <Search class="absolute left-3.5 top-1/2 -translate-y-1/2 size-3.5 text-muted-foreground/50" />
              <input
                v-model="platformSearch"
                type="text"
                placeholder="Search platforms..."
                class="w-full h-9 pl-9 pr-3 bg-glass rounded-xl border border-glass-border text-foreground text-sm placeholder:text-muted-foreground/40 focus:outline-none focus:border-primary/40 transition-colors"
              >
            </div>

            <!-- Platform list -->
            <div class="max-h-64 overflow-auto styled-scrollbar -mx-1 px-1">
              <!-- Empty catalog -->
              <div v-if="!catalogStore.entries.length" class="flex flex-col items-center gap-3 py-10">
                <Package class="size-10 text-muted-foreground/20" />
                <p class="text-sm text-muted-foreground">No platforms in catalog yet</p>
                <Button
                  variant="outline"
                  size="sm"
                  class="border-glass-border h-8 text-xs"
                  @click="catalogDialogOpen = true"
                >
                  <Plus class="size-3.5 mr-1.5" />
                  Add Platform to Catalog
                </Button>
              </div>

              <!-- No search results -->
              <div v-else-if="!hasResults" class="py-10 text-center">
                <p class="text-sm text-muted-foreground">No platforms match "{{ platformSearch }}"</p>
              </div>

              <template v-else>
                <!-- Servers -->
                <template v-if="filteredServers.length">
                  <div class="flex items-center gap-2 px-1 pt-1 pb-2">
                    <Server class="size-3 text-muted-foreground/40" />
                    <span class="text-[10px] font-medium text-muted-foreground/50 uppercase tracking-widest">Servers</span>
                    <div class="flex-1 h-px bg-glass-border/30" />
                  </div>
                  <div class="grid grid-cols-2 gap-2 mb-3">
                    <button
                      v-for="entry in filteredServers"
                      :key="entry.platform"
                      type="button"
                      :class="[
                        'flex items-center gap-2.5 px-3 py-2.5 rounded-xl border text-left transition-all',
                        platform === entry.platform
                          ? 'border-primary/40 bg-primary/8 ring-1 ring-primary/15'
                          : 'border-glass-border hover:bg-glass-hover/60 hover:border-glass-border',
                      ]"
                      @click="selectPlatform(entry.platform)"
                    >
                      <div :class="['size-8 rounded-lg flex items-center justify-center shrink-0', platform === entry.platform ? 'bg-primary/10' : 'bg-glass']">
                        <div :class="['size-2.5 rounded-full transition-all', getColor(entry.platform).dot]" />
                      </div>
                      <div class="flex-1 min-w-0">
                        <p :class="['text-sm font-medium capitalize leading-tight', platform === entry.platform ? 'text-foreground' : 'text-foreground/80']">{{ entry.platform }}</p>
                        <p class="text-[10px] text-muted-foreground/50 leading-tight mt-0.5">{{ entry.versions?.length ?? 0 }} version{{ (entry.versions?.length ?? 0) !== 1 ? 's' : '' }}</p>
                      </div>
                      <Check v-if="platform === entry.platform" class="size-3.5 text-primary shrink-0" />
                    </button>
                  </div>
                </template>

                <!-- Proxies -->
                <template v-if="filteredProxies.length">
                  <div class="flex items-center gap-2 px-1 pt-1 pb-2">
                    <Network class="size-3 text-muted-foreground/40" />
                    <span class="text-[10px] font-medium text-muted-foreground/50 uppercase tracking-widest">Proxies</span>
                    <div class="flex-1 h-px bg-glass-border/30" />
                  </div>
                  <div class="grid grid-cols-2 gap-2 mb-3">
                    <button
                      v-for="entry in filteredProxies"
                      :key="entry.platform"
                      type="button"
                      :class="[
                        'flex items-center gap-2.5 px-3 py-2.5 rounded-xl border text-left transition-all',
                        platform === entry.platform
                          ? 'border-primary/40 bg-primary/8 ring-1 ring-primary/15'
                          : 'border-glass-border hover:bg-glass-hover/60 hover:border-glass-border',
                      ]"
                      @click="selectPlatform(entry.platform)"
                    >
                      <div :class="['size-8 rounded-lg flex items-center justify-center shrink-0', platform === entry.platform ? 'bg-primary/10' : 'bg-glass']">
                        <div :class="['size-2.5 rounded-full transition-all', getColor(entry.platform).dot]" />
                      </div>
                      <div class="flex-1 min-w-0">
                        <p :class="['text-sm font-medium capitalize leading-tight', platform === entry.platform ? 'text-foreground' : 'text-foreground/80']">{{ entry.platform }}</p>
                        <p class="text-[10px] text-muted-foreground/50 leading-tight mt-0.5">{{ entry.versions?.length ?? 0 }} version{{ (entry.versions?.length ?? 0) !== 1 ? 's' : '' }}</p>
                      </div>
                      <Check v-if="platform === entry.platform" class="size-3.5 text-primary shrink-0" />
                    </button>
                  </div>
                </template>
              </template>

              <!-- Add to catalog link -->
              <div v-if="catalogStore.entries.length" class="pt-1">
                <button
                  type="button"
                  class="flex items-center gap-2 w-full px-3 py-2 rounded-xl border border-dashed border-glass-border/50 text-left hover:bg-glass-hover/60 transition-all"
                  @click="catalogDialogOpen = true"
                >
                  <div class="size-8 rounded-lg bg-glass border border-dashed border-glass-border/50 flex items-center justify-center shrink-0">
                    <Plus class="size-3.5 text-muted-foreground/50" />
                  </div>
                  <span class="text-sm text-muted-foreground">Add Platform to Catalog</span>
                </button>
              </div>
            </div>
          </template>

          <!-- Step 2: Name & Description -->
          <template v-if="step === 2">
            <!-- Selected platform pill -->
            <div class="flex items-center gap-2">
              <div :class="['inline-flex items-center gap-2 px-3 py-1.5 rounded-lg border border-primary/30 bg-primary/5 text-sm']">
                <div :class="['size-2.5 rounded-full', getColor(platform).dot]" />
                <span class="font-medium text-foreground capitalize">{{ platform }}</span>
                <span class="text-[10px] text-muted-foreground/60 uppercase">{{ selectedEntry?.category }}</span>
              </div>
              <button type="button" class="text-xs text-muted-foreground hover:text-foreground transition-colors" @click="step = 1">Change</button>
            </div>

            <!-- Name -->
            <div class="flex flex-col gap-1.5">
              <Label for="template-name">Name</Label>
              <Input
                id="template-name"
                v-model="name"
                placeholder="e.g. lobby, survival_base"
                autocomplete="off"
                class="bg-glass border-glass-border font-mono"
                @keydown.enter="submit"
              />
              <p v-if="nameError" class="text-xs text-destructive">{{ nameError }}</p>
            </div>

            <!-- Description -->
            <div class="flex flex-col gap-1.5">
              <Label for="template-description">Description <span class="text-muted-foreground font-normal">(optional)</span></Label>
              <Input
                id="template-description"
                v-model="description"
                placeholder="What this template is for"
                autocomplete="off"
                class="bg-glass border-glass-border"
                @keydown.enter="submit"
              />
            </div>
          </template>

          <!-- Footer -->
          <DialogFooter class="!flex-row gap-2 mt-2 pt-5 border-t border-glass-border">
            <Button
              v-if="step === 2"
              variant="outline"
              class="border-glass-border"
              @click="step = 1"
            >
              <ArrowLeft class="size-4 mr-1.5" />
              Back
            </Button>

            <div class="flex-1" />

            <Button
              v-if="step === 1"
              class="bg-primary hover:bg-primary/90 text-primary-foreground"
              :disabled="!platform"
              @click="step = 2"
            >
              Continue
              <ArrowRight class="size-4 ml-1.5" />
            </Button>

            <Button
              v-else
              class="bg-primary hover:bg-primary/90 text-primary-foreground"
              :disabled="!formValid || loading"
              @click="submit"
            >
              <Loader2 v-if="loading" class="size-4 mr-1.5 animate-spin" />
              {{ loading ? 'Creating...' : 'Create Template' }}
            </Button>
          </DialogFooter>
        </div>
      </DialogContent>
    </Dialog>

    <!-- Catalog create dialog (overlays on top) -->
    <AddVersionDialog v-model="catalogDialogOpen" controlled />
  </div>
</template>
