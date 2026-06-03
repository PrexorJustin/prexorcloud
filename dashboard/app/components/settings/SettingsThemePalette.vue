<script setup lang="ts">
import { Sun, Moon, Check, RotateCcw, Paintbrush, Wand2, Pipette, Copy, ClipboardPaste, Upload, Download } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { generateLightPalette, generateDarkPalette } from "~/lib/palette-generator"
import { lightPresets, darkPresets } from "~/lib/theme-data"

const { t } = useI18n()
const colorMode = useColorMode()
const appearance = useAppearanceStore()

const isDark = computed(() => colorMode.value === "dark")
const paletteMode = ref<"light" | "dark">(isDark.value ? "dark" : "light")
const activePresets = computed(() => paletteMode.value === "dark" ? darkPresets : lightPresets)
const activePaletteForMode = computed(() => paletteMode.value === "dark" ? appearance.darkPalettePreset : appearance.lightPalettePreset)

watch(isDark, (v) => { paletteMode.value = v ? "dark" : "light" })

function setPaletteForMode(name: string) {
  if (paletteMode.value === "dark") {
    appearance.darkPalettePreset = name
  } else {
    appearance.lightPalettePreset = name
  }
  if (paletteMode.value === (isDark.value ? "dark" : "light")) {
    appearance.applyPalette(name)
  }
}

function resetPaletteForMode() {
  if (paletteMode.value === "dark") {
    appearance.darkPalettePreset = null
  } else {
    appearance.lightPalettePreset = null
  }
  if (paletteMode.value === (isDark.value ? "dark" : "light")) {
    appearance.resetPalette()
  }
}

// ── Custom palette builder ──────────────────
const showCustomBuilder = ref(false)
const customBaseColor = ref("#6366f1")
const customEditVars = ref<Record<string, string>>({})
const customPaletteName = ref(t('components.themePalette.customDefault'))

const customPaletteForMode = computed(() => {
  return paletteMode.value === "dark" ? appearance.customDarkPalette : appearance.customLightPalette
})

const colorVarGroups = computed(() => [
  {
    label: t('components.themePalette.groups.background'),
    vars: [
      { key: "--background", label: t('components.themePalette.vars.pageBackground') },
      { key: "--foreground", label: t('components.themePalette.vars.textColor') },
    ],
  },
  {
    label: t('components.themePalette.groups.cardsGlass'),
    vars: [
      { key: "--card", label: t('components.themePalette.vars.cardBackground') },
      { key: "--glass", label: t('components.themePalette.vars.glassSurface') },
      { key: "--glass-hover", label: t('components.themePalette.vars.glassHover') },
      { key: "--glass-border", label: t('components.themePalette.vars.glassBorder') },
    ],
  },
  {
    label: t('components.themePalette.groups.mutedAccent'),
    vars: [
      { key: "--muted", label: t('components.themePalette.vars.mutedBackground') },
      { key: "--muted-foreground", label: t('components.themePalette.vars.mutedText') },
      { key: "--border", label: t('components.themePalette.vars.border') },
    ],
  },
  {
    label: t('components.themePalette.groups.sidebar'),
    vars: [
      { key: "--sidebar", label: t('components.themePalette.vars.sidebarBackground') },
      { key: "--sidebar-foreground", label: t('components.themePalette.vars.sidebarText') },
      { key: "--sidebar-accent", label: t('components.themePalette.vars.sidebarAccent') },
      { key: "--sidebar-border", label: t('components.themePalette.vars.sidebarBorder') },
    ],
  },
])

function regenerateFromBase() {
  const generated = paletteMode.value === "dark"
    ? generateDarkPalette(customBaseColor.value)
    : generateLightPalette(customBaseColor.value)
  customEditVars.value = { ...generated }
}

function randomizeAndRegenerate() {
  const hue = Math.floor(Math.random() * 360)
  const sat = 50 + Math.floor(Math.random() * 40)
  const lit = 40 + Math.floor(Math.random() * 20)
  const s = sat / 100, l = lit / 100
  const a = s * Math.min(l, 1 - l)
  const f = (n: number) => {
    const k = (n + hue / 30) % 12
    const c = l - a * Math.max(Math.min(k - 3, 9 - k, 1), -1)
    return Math.round(255 * Math.max(0, Math.min(1, c))).toString(16).padStart(2, "0")
  }
  customBaseColor.value = `#${f(0)}${f(8)}${f(4)}`
}

watch(customBaseColor, regenerateFromBase)

function applyCustomPalette() {
  appearance.setCustomPalette(paletteMode.value, customBaseColor.value, { ...customEditVars.value }, customPaletteName.value)
  showCustomBuilder.value = false
}

function openCustomBuilder() {
  const existing = paletteMode.value === "dark" ? appearance.customDarkPalette : appearance.customLightPalette
  if (existing) {
    customBaseColor.value = existing.baseColor
    customEditVars.value = { ...existing.vars }
    customPaletteName.value = existing.name || t('components.themePalette.customDefault')
  } else {
    customPaletteName.value = t('components.themePalette.customDefault')
    regenerateFromBase()
  }
  showCustomBuilder.value = true
}

function cleanHex(hex: string): string {
  if (hex.length === 9) return hex.slice(0, 7)
  if (hex.length <= 4) return hex
  return hex.replace(/[^#0-9a-fA-F]/g, "").slice(0, 7)
}

// ── Export / Import ──────────────────────────
const editorMode = ref<"export" | "import" | null>(null)
const editorText = ref("")
const editorError = ref("")
const copyTooltip = ref("")

function openExportEditor() {
  if (editorMode.value === "export") { editorMode.value = null; return }
  const data = {
    name: customPaletteName.value,
    baseColor: customBaseColor.value,
    mode: paletteMode.value,
    vars: { ...customEditVars.value },
  }
  editorText.value = JSON.stringify(data, null, 2)
  editorError.value = ""
  editorMode.value = "export"
}

function openImportEditor() {
  if (editorMode.value === "import") { editorMode.value = null; return }
  editorText.value = ""
  editorError.value = ""
  editorMode.value = "import"
}

function uploadPaletteFile() {
  const input = document.createElement("input")
  input.type = "file"
  input.accept = ".json"
  input.onchange = () => {
    const file = input.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      editorText.value = reader.result as string
      editorError.value = ""
    }
    reader.readAsText(file)
  }
  input.click()
}

function copyEditorText() {
  navigator.clipboard.writeText(editorText.value)
  copyTooltip.value = t('components.themePalette.copied')
  setTimeout(() => { copyTooltip.value = "" }, 2000)
}

function downloadPalette() {
  const blob = new Blob([editorText.value], { type: "application/json" })
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = `${customPaletteName.value.toLowerCase().replace(/\s+/g, "-")}-palette.json`
  a.click()
  URL.revokeObjectURL(url)
}

function applyImport() {
  editorError.value = ""
  try {
    const data = JSON.parse(editorText.value)
    if (!data.baseColor || !data.vars || typeof data.vars !== "object") {
      editorError.value = t('components.themePalette.invalidFormat')
      return
    }
    customBaseColor.value = data.baseColor
    customEditVars.value = { ...data.vars }
    if (data.name) customPaletteName.value = data.name
    if (data.mode && (data.mode === "light" || data.mode === "dark")) {
      paletteMode.value = data.mode
    }
    editorMode.value = null
  } catch {
    editorError.value = t('components.themePalette.invalidJson')
  }
}
</script>

<template>
  <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
    <div class="flex items-center justify-between mb-4">
      <div>
        <h3 class="text-base font-semibold text-foreground">{{ t('components.themePalette.title') }}</h3>
        <p class="text-sm text-muted-foreground mt-0.5">{{ t('components.themePalette.subtitle') }}</p>
      </div>
      <div class="flex items-center gap-2">
        <Button
          v-if="activePaletteForMode"
          variant="ghost"
          size="sm"
          class="text-xs text-muted-foreground"
          @click="resetPaletteForMode()"
        >
          <RotateCcw class="size-3 mr-1.5" />
          {{ t('components.themePalette.reset') }}
        </Button>
      </div>
    </div>

    <!-- Mode toggle -->
    <div class="grid grid-cols-2 gap-3 mb-5">
      <button
        :class="[
          'relative flex items-center gap-3 p-3 rounded-xl border transition-all text-left overflow-hidden cursor-pointer',
          paletteMode === 'light'
            ? 'border-primary bg-primary/10'
            : 'border-glass-border hover:border-primary/40 hover:bg-glass-hover',
        ]"
        @click="paletteMode = 'light'"
      >
        <div class="flex shrink-0 rounded-lg overflow-hidden border border-black/10">
          <div class="w-6 h-8 bg-[#e4e4e4]" />
          <div class="w-6 h-8 bg-[#ffffff]" />
          <div class="w-6 h-8 bg-[#d0d0d0]" />
        </div>
        <div>
          <p class="text-sm font-medium text-foreground flex items-center gap-1.5">
            <Sun class="size-3.5" />
            {{ t('components.themePalette.lightPalettes') }}
          </p>
          <p class="text-[10px] text-muted-foreground">{{ lightPresets.length + (customPaletteForMode && paletteMode === 'light' ? 1 : 0) }} {{ t('components.themePalette.themes') }}</p>
        </div>
      </button>
      <button
        :class="[
          'relative flex items-center gap-3 p-3 rounded-xl border transition-all text-left overflow-hidden cursor-pointer',
          paletteMode === 'dark'
            ? 'border-primary bg-primary/10'
            : 'border-glass-border hover:border-primary/40 hover:bg-glass-hover',
        ]"
        @click="paletteMode = 'dark'"
      >
        <div class="flex shrink-0 rounded-lg overflow-hidden border border-white/10">
          <div class="w-6 h-8 bg-[#0a0a12]" />
          <div class="w-6 h-8 bg-[#1e293b]" />
          <div class="w-6 h-8 bg-[#0e0e1a]" />
        </div>
        <div>
          <p class="text-sm font-medium text-foreground flex items-center gap-1.5">
            <Moon class="size-3.5" />
            {{ t('components.themePalette.darkPalettes') }}
          </p>
          <p class="text-[10px] text-muted-foreground">{{ darkPresets.length + (customPaletteForMode && paletteMode === 'dark' ? 1 : 0) }} {{ t('components.themePalette.themes') }}</p>
        </div>
      </button>
    </div>

    <!-- Preset grid -->
    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
      <button
        v-for="preset in activePresets"
        :key="preset.name"
        :class="[
          'flex flex-col gap-3 p-4 rounded-xl border transition-all text-left cursor-pointer',
          activePaletteForMode === preset.name
            ? 'border-primary bg-primary/10'
            : 'border-glass-border hover:border-primary/40 hover:bg-glass-hover',
        ]"
        @click="setPaletteForMode(preset.name)"
      >
        <!-- Mini dashboard preview -->
        <div class="w-full aspect-[16/10] rounded-lg overflow-hidden border border-black/5 flex text-[0]">
          <div class="w-[22%] h-full flex flex-col p-1.5 gap-1 border-r" :style="{ backgroundColor: preset.vars['--sidebar'], borderColor: preset.vars['--sidebar-border'] }">
            <div class="h-2 rounded-sm w-3/4 mb-0.5" :style="{ backgroundColor: preset.vars['--sidebar-accent'] }" />
            <div class="h-1 rounded-full w-full" :style="{ backgroundColor: preset.vars['--sidebar-accent'] }" />
            <div class="h-1 rounded-full w-4/5" :style="{ backgroundColor: preset.vars['--sidebar-accent'] }" />
            <div class="h-1 rounded-full w-full" :style="{ backgroundColor: preset.vars['--sidebar-accent'] }" />
          </div>
          <div class="flex-1 flex flex-col" :style="{ backgroundColor: preset.vars['--background'] }">
            <div class="h-3 border-b flex items-center px-1.5" :style="{ backgroundColor: preset.vars['--sidebar'], borderColor: preset.vars['--glass-border'] }">
              <div class="h-1 rounded-full w-8" :style="{ backgroundColor: preset.vars['--muted-foreground'] }" />
            </div>
            <div class="flex-1 p-1.5 flex flex-col gap-1">
              <div class="grid grid-cols-3 gap-1">
                <div class="h-5 rounded-sm border p-0.5 flex flex-col justify-end" :style="{ backgroundColor: preset.vars['--glass'] || preset.vars['--card'], borderColor: preset.vars['--glass-border'] }">
                  <div class="h-1 rounded-full w-3/4" style="background-color: var(--primary); opacity: 0.7" />
                </div>
                <div class="h-5 rounded-sm border" :style="{ backgroundColor: preset.vars['--glass'] || preset.vars['--card'], borderColor: preset.vars['--glass-border'] }" />
                <div class="h-5 rounded-sm border" :style="{ backgroundColor: preset.vars['--glass'] || preset.vars['--card'], borderColor: preset.vars['--glass-border'] }" />
              </div>
              <div class="flex-1 rounded-sm border p-1 flex flex-col justify-end gap-0.5" :style="{ backgroundColor: preset.vars['--glass'] || preset.vars['--card'], borderColor: preset.vars['--glass-border'] }">
                <div class="h-1 rounded-full w-1/2" :style="{ backgroundColor: preset.vars['--muted-foreground'], opacity: 0.4 }" />
                <div class="flex justify-end">
                  <div class="h-2 w-5 rounded-sm" style="background-color: var(--primary)" />
                </div>
              </div>
            </div>
          </div>
        </div>
        <div>
          <div class="flex items-center gap-1.5">
            <p class="text-xs font-semibold text-foreground">{{ preset.name }}</p>
            <Check v-if="activePaletteForMode === preset.name" class="size-3 text-primary shrink-0" />
          </div>
          <p class="text-[10px] text-muted-foreground mt-0.5 line-clamp-1">{{ preset.description }}</p>
        </div>
      </button>

      <!-- Custom theme card -->
      <div
        v-if="customPaletteForMode"
        :class="[
          'relative flex flex-col gap-3 p-4 rounded-xl border transition-all text-left cursor-pointer',
          activePaletteForMode === 'Custom'
            ? 'border-primary bg-primary/10'
            : 'border-glass-border hover:border-primary/40 hover:bg-glass-hover',
        ]"
        @click="setPaletteForMode('Custom')"
      >
        <div class="w-full aspect-[16/10] rounded-lg overflow-hidden border border-black/5 flex text-[0]">
          <div class="w-[22%] h-full flex flex-col p-1.5 gap-1 border-r" :style="{ backgroundColor: customPaletteForMode.vars['--sidebar'], borderColor: customPaletteForMode.vars['--sidebar-border'] }">
            <div class="h-2 rounded-sm w-3/4 mb-0.5" :style="{ backgroundColor: customPaletteForMode.vars['--sidebar-accent'] }" />
            <div class="h-1 rounded-full w-full" :style="{ backgroundColor: customPaletteForMode.vars['--sidebar-accent'] }" />
            <div class="h-1 rounded-full w-4/5" :style="{ backgroundColor: customPaletteForMode.vars['--sidebar-accent'] }" />
          </div>
          <div class="flex-1 flex flex-col" :style="{ backgroundColor: customPaletteForMode.vars['--background'] }">
            <div class="h-3 border-b flex items-center px-1.5" :style="{ backgroundColor: customPaletteForMode.vars['--sidebar'], borderColor: customPaletteForMode.vars['--glass-border'] }">
              <div class="h-1 rounded-full w-8" :style="{ backgroundColor: customPaletteForMode.vars['--muted-foreground'] }" />
            </div>
            <div class="flex-1 p-1.5 flex flex-col gap-1">
              <div class="grid grid-cols-3 gap-1">
                <div class="h-5 rounded-sm border p-0.5 flex flex-col justify-end" :style="{ backgroundColor: customPaletteForMode.vars['--glass'] || customPaletteForMode.vars['--card'], borderColor: customPaletteForMode.vars['--glass-border'] }">
                  <div class="h-1 rounded-full w-3/4" style="background-color: var(--primary); opacity: 0.7" />
                </div>
                <div class="h-5 rounded-sm border" :style="{ backgroundColor: customPaletteForMode.vars['--glass'] || customPaletteForMode.vars['--card'], borderColor: customPaletteForMode.vars['--glass-border'] }" />
                <div class="h-5 rounded-sm border" :style="{ backgroundColor: customPaletteForMode.vars['--glass'] || customPaletteForMode.vars['--card'], borderColor: customPaletteForMode.vars['--glass-border'] }" />
              </div>
              <div class="flex-1 rounded-sm border p-1 flex flex-col justify-end gap-0.5" :style="{ backgroundColor: customPaletteForMode.vars['--glass'] || customPaletteForMode.vars['--card'], borderColor: customPaletteForMode.vars['--glass-border'] }">
                <div class="h-1 rounded-full w-1/2" :style="{ backgroundColor: customPaletteForMode.vars['--muted-foreground'], opacity: 0.4 }" />
                <div class="flex justify-end">
                  <div class="h-2 w-5 rounded-sm" style="background-color: var(--primary)" />
                </div>
              </div>
            </div>
          </div>
        </div>
        <div class="flex items-end justify-between">
          <div>
            <div class="flex items-center gap-1.5">
              <p class="text-xs font-semibold text-foreground">{{ customPaletteForMode.name || t('components.themePalette.customDefault') }}</p>
              <Check v-if="activePaletteForMode === 'Custom'" class="size-3 text-primary shrink-0" />
            </div>
            <p class="text-[10px] text-muted-foreground mt-0.5">{{ t('components.themePalette.basedOn', { color: customPaletteForMode.baseColor }) }}</p>
          </div>
          <button
            class="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] text-muted-foreground hover:text-foreground hover:bg-glass transition-colors"
            @click.stop="openCustomBuilder()"
          >
            <Paintbrush class="size-3" />
            {{ t('components.themePalette.edit') }}
          </button>
        </div>
      </div>

      <!-- Create custom -->
      <button
        v-if="!customPaletteForMode"
        class="flex flex-col items-center justify-center gap-2 p-4 rounded-xl border border-dashed border-glass-border hover:border-primary/40 hover:bg-glass-hover transition-all min-h-[120px] cursor-pointer"
        @click="openCustomBuilder()"
      >
        <div class="size-10 rounded-xl bg-glass flex items-center justify-center">
          <Wand2 class="size-5 text-muted-foreground" />
        </div>
        <span class="text-xs font-medium text-muted-foreground">{{ t('components.themePalette.createCustom') }}</span>
      </button>
    </div>

    <!-- Custom palette builder (inline) -->
    <div v-if="showCustomBuilder" class="mt-5 rounded-xl border border-primary/30 bg-primary/5 p-5">
      <div class="flex items-center justify-between mb-4">
        <div class="flex items-center gap-2">
          <Wand2 class="size-4 text-primary shrink-0" />
          <input
            v-model="customPaletteName"
            type="text"
            :placeholder="t('components.themePalette.paletteName')"
            class="text-sm font-semibold text-foreground bg-transparent border-b border-transparent hover:border-glass-border focus:border-primary focus:outline-none px-0.5 py-0 w-40 transition-colors"
          >
        </div>
        <div class="flex items-center gap-2">
          <Button variant="ghost" size="sm" class="h-7 px-2 text-xs text-muted-foreground" @click="openExportEditor">
            <Copy class="size-3 mr-1" />
            {{ t('components.themePalette.export') }}
          </Button>
          <Button variant="ghost" size="sm" class="h-7 px-2 text-xs text-muted-foreground" @click="openImportEditor">
            <ClipboardPaste class="size-3 mr-1" />
            {{ t('components.themePalette.import') }}
          </Button>
          <button class="text-xs text-muted-foreground hover:text-foreground" @click="showCustomBuilder = false">{{ t('components.themePalette.cancel') }}</button>
        </div>
      </div>

      <!-- Export / Import editor -->
      <div v-if="editorMode" class="rounded-xl border border-glass-border p-4 mb-5">
        <div class="flex items-center justify-between mb-2">
          <p class="text-xs font-semibold text-foreground">
            {{ editorMode === 'export' ? t('components.themePalette.paletteJson') : t('components.themePalette.pastePaletteJson') }}
          </p>
          <div v-if="editorMode === 'import'" class="flex items-center gap-1">
            <button class="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors" :title="t('components.themePalette.loadFromFile')" @click="uploadPaletteFile">
              <Upload class="size-3.5" />
            </button>
          </div>
          <div v-if="editorMode === 'export'" class="flex items-center gap-1">
            <div class="relative">
              <button class="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors" :title="t('components.themePalette.copyToClipboard')" @click="copyEditorText">
                <Copy class="size-3.5" />
              </button>
              <span v-if="copyTooltip" class="absolute -bottom-5 left-1/2 -translate-x-1/2 text-[9px] text-primary whitespace-nowrap">{{ copyTooltip }}</span>
            </div>
            <button class="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-glass-hover transition-colors" :title="t('components.themePalette.downloadAsFile')" @click="downloadPalette">
              <Download class="size-3.5" />
            </button>
          </div>
        </div>
        <textarea
          v-model="editorText"
          :readonly="editorMode === 'export'"
          class="w-full h-48 rounded-lg border border-glass-border bg-glass p-3 text-xs font-mono text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-primary resize-none"
          :class="editorMode === 'export' ? 'select-all cursor-default' : ''"
          :placeholder="editorMode === 'import' ? '{&quot;name&quot;:&quot;My Theme&quot;,&quot;baseColor&quot;:&quot;#6366f1&quot;,&quot;mode&quot;:&quot;dark&quot;,&quot;vars&quot;:{...}}' : ''"
        />
        <p v-if="editorError" class="text-[10px] text-destructive mt-1.5">{{ editorError }}</p>
        <div class="flex items-center justify-end gap-2 mt-3">
          <Button variant="ghost" size="sm" class="text-xs" @click="editorMode = null">{{ t('components.themePalette.close') }}</Button>
          <Button v-if="editorMode === 'import'" size="sm" class="text-xs" @click="applyImport">{{ t('components.themePalette.apply') }}</Button>
        </div>
      </div>

      <!-- Generate from base color -->
      <div class="rounded-xl border border-glass-border p-4 mb-5">
        <p class="text-xs font-semibold text-foreground mb-3">{{ t('components.themePalette.generateFromBase') }}</p>
        <div class="flex items-center gap-3">
          <input
            v-model="customBaseColor"
            type="color"
            class="size-10 rounded-lg border border-glass-border cursor-pointer bg-transparent p-0.5"
          >
          <input
            v-model="customBaseColor"
            type="text"
            class="h-9 w-28 px-3 bg-glass rounded-lg border border-glass-border text-foreground font-mono text-xs focus:outline-none focus:border-primary"
          >
          <div class="flex flex-wrap gap-1.5 flex-1">
            <button
              v-for="color in ['#6366f1', '#ec4899', '#f97316', '#10b981', '#06b6d4', '#8b5cf6', '#ef4444', '#eab308', '#3b82f6', '#14b8a6', '#f43f5e', '#a855f7']"
              :key="color"
              class="size-6 rounded-md border border-black/10 transition-transform hover:scale-110 cursor-pointer"
              :class="customBaseColor === color ? 'ring-2 ring-primary ring-offset-1 ring-offset-background' : ''"
              :style="{ backgroundColor: color }"
              @click="customBaseColor = color"
            />
          </div>
          <Button variant="outline" size="sm" class="shrink-0 text-xs border-glass-border" @click="randomizeAndRegenerate">
            <Wand2 class="size-3 mr-1.5" />
            {{ t('components.themePalette.randomize') }}
          </Button>
        </div>
      </div>

      <!-- Fine-tune + live preview -->
      <div class="grid grid-cols-1 lg:grid-cols-[1fr_320px] gap-5">
        <!-- Color editors -->
        <div class="flex flex-col gap-4">
          <p class="text-xs font-semibold text-foreground">{{ t('components.themePalette.fineTune') }}</p>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div v-for="group in colorVarGroups" :key="group.label" class="rounded-xl border border-glass-border p-3">
              <p class="text-[10px] font-medium text-muted-foreground uppercase tracking-wider mb-2">{{ group.label }}</p>
              <div class="flex flex-col gap-2">
                <div v-for="v in group.vars" :key="v.key" class="flex items-center gap-2">
                  <label class="relative size-7 shrink-0 rounded-md border border-glass-border overflow-hidden cursor-pointer">
                    <input
                      type="color"
                      :value="cleanHex(customEditVars[v.key] || '#000000')"
                      class="absolute inset-0 size-10 -m-1 cursor-pointer opacity-0"
                      @input="customEditVars[v.key] = ($event.target as HTMLInputElement).value"
                    >
                    <div class="size-full" :style="{ backgroundColor: cleanHex(customEditVars[v.key] || '#000000') }" />
                  </label>
                  <span class="text-xs text-muted-foreground flex-1">{{ v.label }}</span>
                  <input
                    :value="customEditVars[v.key] || ''"
                    type="text"
                    class="h-6 w-20 px-1.5 bg-glass rounded border border-glass-border text-foreground font-mono text-[10px] focus:outline-none focus:border-primary"
                    @change="customEditVars[v.key] = ($event.target as HTMLInputElement).value"
                  >
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Live preview (sticky) -->
        <div class="lg:sticky lg:top-20 self-start">
          <p class="text-xs font-semibold text-foreground mb-2">{{ t('components.themePalette.livePreview') }}</p>
          <div class="w-full aspect-video rounded-xl overflow-hidden border border-glass-border flex shadow-lg text-[0]">
            <div class="w-1/5 flex flex-col p-2 gap-1.5 border-r" :style="{ backgroundColor: customEditVars['--sidebar'], borderColor: customEditVars['--sidebar-border'] }">
              <div class="h-2 rounded-sm w-3/4 mb-0.5" :style="{ backgroundColor: customEditVars['--sidebar-accent'] }" />
              <div class="h-1.5 rounded-full w-full" :style="{ backgroundColor: customEditVars['--sidebar-accent'] }" />
              <div class="h-1.5 rounded-full w-4/5" :style="{ backgroundColor: customEditVars['--sidebar-accent'] }" />
              <div class="h-1.5 rounded-full w-full" :style="{ backgroundColor: customEditVars['--sidebar-accent'] }" />
            </div>
            <div class="flex-1 flex flex-col" :style="{ backgroundColor: customEditVars['--background'] }">
              <div class="h-5 border-b flex items-center px-1.5" :style="{ borderColor: customEditVars['--glass-border'], backgroundColor: customEditVars['--sidebar'] }">
                <div class="h-1 rounded-full w-10" :style="{ backgroundColor: customEditVars['--muted-foreground'] }" />
              </div>
              <div class="flex-1 p-1.5 flex flex-col gap-1">
                <div class="grid grid-cols-3 gap-1">
                  <div class="h-5 rounded-sm border p-0.5 flex flex-col justify-end" :style="{ backgroundColor: customEditVars['--glass'] || customEditVars['--card'], borderColor: customEditVars['--glass-border'] }">
                    <div class="h-0.5 rounded-full w-3/4" style="background-color: var(--primary); opacity: 0.7" />
                  </div>
                  <div class="h-5 rounded-sm border" :style="{ backgroundColor: customEditVars['--glass'] || customEditVars['--card'], borderColor: customEditVars['--glass-border'] }" />
                  <div class="h-5 rounded-sm border" :style="{ backgroundColor: customEditVars['--glass'] || customEditVars['--card'], borderColor: customEditVars['--glass-border'] }" />
                </div>
                <div class="flex-1 rounded-sm border p-1 flex flex-col justify-end" :style="{ backgroundColor: customEditVars['--glass'] || customEditVars['--card'], borderColor: customEditVars['--glass-border'] }">
                  <div class="flex justify-end">
                    <div class="h-1.5 w-4 rounded-sm" style="background-color: var(--primary)" />
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Text preview -->
          <div class="mt-3 rounded-xl border p-3 text-[0]" :style="{ backgroundColor: customEditVars['--background'], borderColor: customEditVars['--glass-border'] }">
            <div class="text-[11px] font-semibold mb-1" :style="{ color: customEditVars['--foreground'] }">{{ t('components.themePalette.sampleText') }}</div>
            <div class="text-[9px] mb-2" :style="{ color: customEditVars['--muted-foreground'] }">{{ t('components.themePalette.mutedDesc') }}</div>
            <div class="flex gap-1.5">
              <div class="h-4 px-2 rounded-md text-[8px] flex items-center font-medium text-primary-foreground" style="background-color: var(--primary)">{{ t('components.themePalette.button') }}</div>
              <div class="h-4 px-2 rounded-md text-[8px] flex items-center border" :style="{ borderColor: customEditVars['--border'], color: customEditVars['--foreground'] }">{{ t('components.themePalette.outline') }}</div>
            </div>
          </div>

          <!-- Apply button -->
          <Button class="w-full mt-4" @click="applyCustomPalette()">
            <Pipette class="size-4 mr-2" />
            {{ t('components.themePalette.applyCustom') }}
          </Button>
        </div>
      </div>
    </div>
  </div>
</template>
