<script setup lang="ts">
import { ref, computed } from "vue"
import {
  Search, ArrowUp, ArrowDown, CornerDownLeft,
  Sun, Moon, Monitor,
  Box, Server, Layers, FileCode, User,
} from "lucide-vue-next"
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandQueryProbe,
  CommandSeparator,
} from "~/components/ui/command"
import { KbdHint } from "~/components/ui/kbd"
import { StatusDot } from "~/components/ui/status-dot"
import { navigation } from "~/lib/navigation"
import { useResourceSearchIndex, type ResourceHit, type ResourceKind } from "~/composables/useResourceSearchIndex"

const open = ref(false)
const query = ref("")
const router = useRouter()
const auth = useAuthStore()

const isMac = computed(() =>
  typeof navigator !== "undefined" && navigator.userAgent.includes("Mac"),
)

const colorMode = useColorMode()

const themeItems = [
  { title: "Light", icon: Sun, value: "light" },
  { title: "Dark", icon: Moon, value: "dark" },
  { title: "System", icon: Monitor, value: "system" },
]

function setTheme(value: string) {
  colorMode.preference = value
  open.value = false
}

const navGroups = computed(() =>
  navigation
    .map(group => ({
      ...group,
      items: group.items.filter(item => !item.permission || auth.can(item.permission)),
    }))
    .filter(group => group.items.length > 0),
)

const { search } = useResourceSearchIndex()
const resourceHits = computed<ResourceHit[]>(() => search(query.value))

const KIND_ICON: Record<ResourceKind, typeof Box> = {
  instance: Box,
  node:     Server,
  group:    Layers,
  template: FileCode,
  user:     User,
}

useEventListener("keydown", (e: KeyboardEvent) => {
  if (e.key === "k" && (e.metaKey || e.ctrlKey)) {
    e.preventDefault()
    open.value = !open.value
  }
})

// Allow other parts of the app (the global keyboard shortcuts dispatcher) to
// open the palette imperatively.
defineExpose({ open: () => { open.value = true } })

function navigate(url: string) {
  open.value = false
  router.push(url)
}
</script>

<template>
  <button
    type="button"
    class="inline-flex h-9 w-64 items-center gap-3 rounded-xl border border-glass-border bg-glass/60 px-3 text-sm text-muted-foreground transition-colors hover:bg-glass-hover hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 cursor-pointer"
    @click="open = true"
  >
    <Search class="size-4 shrink-0" />
    <span class="flex-1 text-left">Search…</span>
    <KbdHint class="hidden md:inline-flex">
      {{ isMac ? '⌘' : 'Ctrl' }}+K
    </KbdHint>
  </button>

  <CommandDialog :open="open" @update:open="open = $event">
    <CommandInput placeholder="Search resources, jump to a page, switch theme…" />
    <CommandQueryProbe @update:query="(v) => query = v" />

    <CommandList class="max-h-[480px]">
      <CommandEmpty>
        <div class="flex flex-col items-center py-6 text-center">
          <Search class="mb-3 size-10 text-muted-foreground/30" />
          <p class="text-sm text-muted-foreground">No results.</p>
        </div>
      </CommandEmpty>

      <!-- Resource hits — shown only when the user has typed something. -->
      <CommandGroup v-if="query && resourceHits.length > 0" heading="Resources">
        <CommandItem
          v-for="hit in resourceHits"
          :key="`${hit.kind}-${hit.id}`"
          :value="`${hit.kind}-${hit.id}`"
          class="gap-3"
          @select="navigate(hit.route)"
        >
          <div class="flex size-8 items-center justify-center rounded-lg border border-glass-border bg-glass">
            <component :is="KIND_ICON[hit.kind]" class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm mono">{{ hit.label }}</p>
            <p v-if="hit.sublabel" class="truncate text-xs text-muted-foreground">{{ hit.sublabel }}</p>
          </div>
          <StatusDot v-if="hit.statusTone" :tone="hit.statusTone" size="sm" />
        </CommandItem>
      </CommandGroup>

      <CommandSeparator v-if="query && resourceHits.length > 0" />

      <template v-for="(group, i) in navGroups" :key="group.id">
        <CommandSeparator v-if="i > 0" />
        <CommandGroup :heading="group.label">
          <CommandItem
            v-for="item in group.items"
            :key="item.title"
            :value="item.title"
            class="gap-3"
            @select="navigate(item.url)"
          >
            <div class="flex size-8 items-center justify-center rounded-lg border border-glass-border bg-glass">
              <component :is="item.icon" class="size-4 text-muted-foreground" />
            </div>
            <span>{{ item.title }}</span>
          </CommandItem>
        </CommandGroup>
      </template>

      <CommandSeparator />
      <CommandGroup heading="Theme">
        <CommandItem
          v-for="item in themeItems"
          :key="item.value"
          :value="item.title"
          class="gap-3"
          @select="setTheme(item.value)"
        >
          <div class="flex size-8 items-center justify-center rounded-lg border border-glass-border bg-glass">
            <component :is="item.icon" class="size-4 text-muted-foreground" />
          </div>
          <span>{{ item.title }}</span>
          <span v-if="colorMode.preference === item.value" class="ml-auto text-xs text-primary">Active</span>
        </CommandItem>
      </CommandGroup>
    </CommandList>

    <div class="flex items-center justify-between border-t border-glass-border px-3 py-2.5 text-xs text-muted-foreground/70">
      <div class="flex items-center gap-3">
        <span class="inline-flex items-center gap-1.5">
          <KbdHint class="size-5 px-0"><ArrowUp class="size-3" /></KbdHint>
          <KbdHint class="size-5 px-0"><ArrowDown class="size-3" /></KbdHint>
          Navigate
        </span>
        <span class="inline-flex items-center gap-1.5">
          <KbdHint class="size-5 px-0"><CornerDownLeft class="size-3" /></KbdHint>
          Select
        </span>
        <span class="inline-flex items-center gap-1.5">
          <KbdHint>Esc</KbdHint>
          Close
        </span>
      </div>
    </div>
  </CommandDialog>
</template>
