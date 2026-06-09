import { defineStore } from "pinia"
import {
  accentColors,
  lightPresets,
  darkPresets,
  defaultGlowBlobs,
  paletteVarKeys,
  type GlowBlob,
  type AccentColor,
} from "~/lib/theme-data"

const STORAGE_KEY = "prexor-appearance"

interface CustomPalette {
  name: string
  baseColor: string
  vars: Record<string, string>
}

interface AppearanceState {
  accentColor: string
  customAccentColor: string | null
  radius: number
  lightPalettePreset: string | null
  darkPalettePreset: string | null
  customLightPalette: CustomPalette | null
  customDarkPalette: CustomPalette | null
  glowEnabled: boolean
  glowIntensity: number
  glowBlobs: GlowBlob[]
}

const defaults: AppearanceState = {
  accentColor: "Cyan",
  customAccentColor: null,
  radius: 0.75,
  lightPalettePreset: null,
  darkPalettePreset: null,
  customLightPalette: null,
  customDarkPalette: null,
  glowEnabled: true,
  glowIntensity: 100,
  glowBlobs: structuredClone(defaultGlowBlobs),
}

function loadState(): AppearanceState {
  if (typeof window === "undefined") return { ...defaults, glowBlobs: structuredClone(defaultGlowBlobs) }

  // Try loading from localStorage
  const raw = localStorage.getItem(STORAGE_KEY)
  if (raw) {
    try {
      const parsed = JSON.parse(raw)
      return { ...defaults, ...parsed, glowBlobs: parsed.glowBlobs ?? structuredClone(defaultGlowBlobs) }
    } catch { /* fall through */ }
  }

  // Migrate from legacy cookies
  const state: AppearanceState = { ...defaults, glowBlobs: structuredClone(defaultGlowBlobs) }
  const cookieColor = useCookie("prexor-theme-color").value
  const cookieRadius = useCookie("prexor-theme-radius").value
  if (cookieColor && typeof cookieColor === "string") {
    state.accentColor = cookieColor
  }
  if (cookieRadius !== null && cookieRadius !== undefined) {
    state.radius = Number(cookieRadius)
  }
  return state
}

export const useAppearanceStore = defineStore("appearance", () => {
  const colorMode = useColorMode()
  const initial = loadState()

  const accentColor = ref(initial.accentColor)
  const customAccentColor = ref<string | null>(initial.customAccentColor)
  const radius = ref(initial.radius)
  const lightPalettePreset = ref(initial.lightPalettePreset)
  const darkPalettePreset = ref(initial.darkPalettePreset)
  const customLightPalette = ref<CustomPalette | null>(initial.customLightPalette)
  const customDarkPalette = ref<CustomPalette | null>(initial.customDarkPalette)
  const glowEnabled = ref(initial.glowEnabled)
  const glowIntensity = ref(initial.glowIntensity)
  const glowBlobs = ref<GlowBlob[]>(initial.glowBlobs)

  // ── Persistence ─────────────────────────────
  const stateForStorage = computed(() => JSON.stringify({
    accentColor: accentColor.value,
    customAccentColor: customAccentColor.value,
    radius: radius.value,
    lightPalettePreset: lightPalettePreset.value,
    darkPalettePreset: darkPalettePreset.value,
    customLightPalette: customLightPalette.value,
    customDarkPalette: customDarkPalette.value,
    glowEnabled: glowEnabled.value,
    glowIntensity: glowIntensity.value,
    glowBlobs: glowBlobs.value,
  }))

  watch(stateForStorage, (json) => {
    localStorage.setItem(STORAGE_KEY, json)
  })

  // ── Accent Color ────────────────────────────
  function resolveColor(name: string): AccentColor | undefined {
    return accentColors.find(c => c.name === name)
  }

  function applyPrimaryColor(hex: string) {
    const root = document.documentElement.style
    root.setProperty("--primary", hex)
    root.setProperty("--ring", hex)
    root.setProperty("--primary-glow", `${hex}40`)
    root.setProperty("--sidebar-primary", hex)
    root.setProperty("--sidebar-ring", hex)
    root.setProperty("--chart-1", hex)
  }

  function applyAccentColor() {
    if (accentColor.value === "Custom" && customAccentColor.value) {
      applyPrimaryColor(customAccentColor.value)
      return
    }
    const color = resolveColor(accentColor.value)
    if (!color) return
    const isDark = colorMode.value === "dark"
    applyPrimaryColor(isDark ? color.value : color.light)
  }

  function setAccentColor(name: string) {
    accentColor.value = name
    applyAccentColor()
  }

  function setCustomAccentColor(hex: string) {
    customAccentColor.value = hex
    accentColor.value = "Custom"
    applyPrimaryColor(hex)
  }

  // ── Radius ──────────────────────────────────
  function applyRadius() {
    document.documentElement.style.setProperty("--ui-radius", `${radius.value}rem`)
  }

  function setRadius(r: number) {
    radius.value = r
    applyRadius()
  }

  // ── Palette Presets ─────────────────────────
  function applyPaletteVars(vars: Record<string, string>) {
    const root = document.documentElement.style
    for (const [key, value] of Object.entries(vars)) {
      root.setProperty(key, value)
    }
  }

  function clearPaletteVars() {
    const root = document.documentElement.style
    for (const key of paletteVarKeys) {
      root.removeProperty(key)
    }
  }

  function applyActivePalette() {
    const isDark = colorMode.value === "dark"
    const presetName = isDark ? darkPalettePreset.value : lightPalettePreset.value
    if (!presetName) {
      clearPaletteVars()
      return
    }
    // Check custom palette
    if (presetName === "Custom") {
      const custom = isDark ? customDarkPalette.value : customLightPalette.value
      if (custom) {
        applyPaletteVars(custom.vars)
      }
      return
    }
    const presets = isDark ? darkPresets : lightPresets
    const preset = presets.find(p => p.name === presetName)
    if (preset) {
      applyPaletteVars(preset.vars)
    }
  }

  function applyPalette(name: string) {
    const isDark = colorMode.value === "dark"
    if (isDark) {
      darkPalettePreset.value = name
    } else {
      lightPalettePreset.value = name
    }
    applyActivePalette()
  }

  function resetPalette() {
    const isDark = colorMode.value === "dark"
    if (isDark) {
      darkPalettePreset.value = null
    } else {
      lightPalettePreset.value = null
    }
    clearPaletteVars()
  }

  // ── Custom Palettes ────────────────────────
  function setCustomPalette(mode: "light" | "dark", baseColor: string, vars: Record<string, string>, name?: string) {
    const existing = mode === "dark" ? customDarkPalette.value : customLightPalette.value
    const palette: CustomPalette = { name: name || existing?.name || "Custom", baseColor, vars }
    if (mode === "dark") {
      customDarkPalette.value = palette
      darkPalettePreset.value = "Custom"
    } else {
      customLightPalette.value = palette
      lightPalettePreset.value = "Custom"
    }
    // Apply if we're in this mode
    if ((mode === "dark") === (colorMode.value === "dark")) {
      applyPaletteVars(vars)
    }
  }

  // ── Glows ───────────────────────────────────
  function setGlowEnabled(enabled: boolean) {
    glowEnabled.value = enabled
  }

  function setGlowIntensity(intensity: number) {
    glowIntensity.value = intensity
  }

  function addGlowBlob() {
    glowBlobs.value.push({
      x: 50, y: 50, size: 400, blur: 120, opacity: 5, intensity: 100,
      color: "primary", scaleX: 100, scaleY: 100, rotate: 0,
    })
  }

  function removeGlowBlob(index: number) {
    if (glowBlobs.value.length > 1) {
      glowBlobs.value.splice(index, 1)
    }
  }

  function updateGlowBlob(index: number, updates: Partial<GlowBlob>) {
    const blob = glowBlobs.value[index]
    if (!blob) return
    Object.assign(blob, updates)
  }

  function resetGlows() {
    glowEnabled.value = true
    glowIntensity.value = 100
    glowBlobs.value = structuredClone(defaultGlowBlobs)
  }

  // ── Restore All ─────────────────────────────
  function restoreAll() {
    applyAccentColor()
    applyRadius()
    applyActivePalette()
  }

  // Re-apply on color mode change (accent color differs between light/dark)
  watch(() => colorMode.value, () => {
    restoreAll()
  })

  // Apply on mount
  onMounted(restoreAll)

  // ── Active palette name (computed) ──────────
  const activePalettePreset = computed(() => {
    const isDark = colorMode.value === "dark"
    return isDark ? darkPalettePreset.value : lightPalettePreset.value
  })

  // ── Navigation hints (transient, not persisted) ──
  const pendingSettingsTab = ref<string | null>(null)
  const pendingSettingsSubTab = ref<string | null>(null)

  function navigateToSettings(tab: string, subTab?: string) {
    pendingSettingsTab.value = tab
    pendingSettingsSubTab.value = subTab ?? null
  }

  function consumeSettingsNav(): { tab: string | null; subTab: string | null } {
    const result = { tab: pendingSettingsTab.value, subTab: pendingSettingsSubTab.value }
    pendingSettingsTab.value = null
    pendingSettingsSubTab.value = null
    return result
  }

  return {
    accentColor,
    customAccentColor,
    radius,
    lightPalettePreset,
    darkPalettePreset,
    activePalettePreset,
    customLightPalette,
    customDarkPalette,
    glowEnabled,
    glowIntensity,
    glowBlobs,
    setAccentColor,
    setCustomAccentColor,
    setRadius,
    applyPalette,
    resetPalette,
    setCustomPalette,
    setGlowEnabled,
    setGlowIntensity,
    addGlowBlob,
    removeGlowBlob,
    updateGlowBlob,
    resetGlows,
    restoreAll,
    navigateToSettings,
    consumeSettingsNav,
  }
})
