<script setup lang="ts">
import {
  Plus, Layers, Loader2, ArrowRight, ArrowLeft, Check, Search,
  Server, Network, Package, Activity, Box, Cpu,
  Users, Sparkles, Gauge, FileCode, X,
  GitBranch, Globe, Terminal, Route,
  ChevronDown, Rocket, CircleDot, Eye, Pencil, Settings2, Shield, Clock,
} from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { Input } from "~/components/ui/input"
import { Label } from "~/components/ui/label"
import { Badge } from "~/components/ui/badge"
import { Separator } from "~/components/ui/separator"
import { Switch } from "~/components/ui/switch"
import { Slider } from "~/components/ui/slider"
import { Dialog, DialogContent, DialogTitle, DialogDescription, DialogTrigger } from "~/components/ui/dialog"
import { toast } from "vue-sonner"
import { focusFirst } from "~/lib/a11y/focus"
import type { CatalogEntry } from "~/types/api"

const { t } = useI18n()
const store = useGroupsStore()
const catalogStore = useCatalogStore()
const templateStore = useTemplatesStore()
const nodesStore = useNodesStore()

const open = ref(false)
const loading = ref(false)
const step = ref(1)
const direction = ref<"forward" | "backward">("forward")

// ── Step 1: Identity ────────────────────────
const platformSearch = ref("")
const platform = ref("")
const name = ref("")
const platformVersion = ref("")
const jarFile = ref("server.jar")

// ── Step 2: Scaling ─────────────────────────
const scalingMode = ref<"DYNAMIC" | "STATIC" | "MANUAL">("DYNAMIC")
const minInstances = ref(0)
const maxInstances = ref(10)
const maxPlayers = ref(100)
const isStatic = ref(false)
const staticInstanceNames = ref("")
const scaleUpThreshold = ref(80)
const scaleDownAfterSeconds = ref(300)
const scaleCooldownSeconds = ref(60)

// ── Step 3: Resources & Networking ──────────
const memoryMb = ref(1024)
const jvmArgs = ref("")
const envPairs = ref<{ key: string; value: string }[]>([])
const routing = ref("LOWEST_PLAYERS")
const portRangeStart = ref(30000)
const portRangeEnd = ref(30100)

// ── Step 4: Lifecycle & Deployment ──────────
const startupTimeoutSeconds = ref(120)
const shutdownGraceSeconds = ref(30)
const drainOnShutdown = ref(false)
const maxLifetimeSeconds = ref(0)
const updateStrategy = ref<"ROLLING" | "ON_NEW" | "CANARY" | "MANUAL">("ROLLING")
const protectedPaths = ref("")

// ── Step 5: Orchestration & Templates ───────
const parent = ref("")
const dependsOn = ref("")
const startupWeight = ref(0)
const fallbackGroup = ref("")
const bedrockProxyGroup = ref("")
const defaultGroup = ref(false)
const maintenance = ref(false)
const extraTemplates = ref<string[]>([])
const nodeAffinity = ref("")
const nodeAntiAffinity = ref("")
const spreadConstraint = ref("")
const priority = ref(0)

// Not implemented in backend — sent with defaults
const predictiveScaling = ref(false)
const scaleUpMargin = ref(20)
const burstCeiling = ref(0)

// ── Platform helpers ────────────────────────
const serverEntries = computed(() => catalogStore.entries.filter(e => e.category === "SERVER"))
const proxyEntries = computed(() => catalogStore.entries.filter(e => e.category === "PROXY"))
function filterEntries(entries: CatalogEntry[]) {
  const q = platformSearch.value.toLowerCase().trim()
  return q ? entries.filter(e => e.platform.toLowerCase().includes(q)) : entries
}
const filteredServers = computed(() => filterEntries(serverEntries.value))
const filteredProxies = computed(() => filterEntries(proxyEntries.value))
const hasResults = computed(() => filteredServers.value.length > 0 || filteredProxies.value.length > 0)
const selectedEntry = computed(() => catalogStore.entries.find(e => e.platform === platform.value) ?? null)
const isProxy = computed(() => selectedEntry.value?.category === "PROXY")
const isGeyser = computed(() => platform.value.toUpperCase() === "GEYSER")
// Java proxy groups a Geyser front-door can forward Bedrock players to (exclude other Geyser groups).
const proxyPlatformNames = computed(() => new Set(proxyEntries.value.map(e => e.platform.toUpperCase())))
const proxyGroupNames = computed(() =>
  store.groups
    .filter((g) => {
      const plat = (g.platform ?? "").toUpperCase()
      return proxyPlatformNames.value.has(plat) && plat !== "GEYSER"
    })
    .map(g => g.name),
)
const availableVersions = computed(() => selectedEntry.value?.versions ?? [])
const recommendedVersion = computed(() => availableVersions.value.find(v => v.recommended)?.version ?? availableVersions.value[0]?.version ?? "")

const mandatoryTemplateNames = computed(() => {
  const list = ["base"]
  if (platform.value) list.push(`base-${platform.value.toLowerCase()}`)
  if (name.value) list.push(name.value)
  return list
})
const mandatoryTemplateStatus = computed(() =>
  mandatoryTemplateNames.value.map(n => ({ name: n, exists: templateStore.templates.some(t => t.name === n) })),
)
const availableExtraTemplates = computed(() => {
  const mandatory = new Set(mandatoryTemplateNames.value)
  return templateStore.templates.filter(t => !mandatory.has(t.name))
})
const otherGroupNames = computed(() => store.groups.map(g => g.name))
const connectedNodeNames = computed(() => nodesStore.nodes.filter(n => n.type === "CONNECTED").map(n => n.id))

// ── Presets ──────────────────────────────────
interface Preset { key: string; label: string; desc: string; icon: Component; color: string; border: string; bg: string; iconBg: string; apply: () => void }
const presets = computed<Preset[]>(() => [
  {
    key: "lobby", label: t('components.createGroup.presets.lobby.label'), desc: t('components.createGroup.presets.lobby.desc'), icon: Users,
    color: "text-success", border: "border-success/40", bg: "bg-success/5", iconBg: "bg-success/10",
    apply() {
      // Scaling: always 1 static instance, 100 player slots
      scalingMode.value = "STATIC"; isStatic.value = true
      minInstances.value = 1; maxInstances.value = 1; maxPlayers.value = 100
      // Resources: standard 1G, spread players across lobbies
      memoryMb.value = 1024; routing.value = "LOWEST_PLAYERS"
      // Lifecycle: standard timers, no drain (lobby is always available)
      startupTimeoutSeconds.value = 120; shutdownGraceSeconds.value = 30
      maxLifetimeSeconds.value = 0; drainOnShutdown.value = false
      updateStrategy.value = "ROLLING"
      // Orchestration: this IS the default group players land on
      defaultGroup.value = true; maintenance.value = false
      // Reset dynamic-only values
      scaleUpThreshold.value = 80; scaleDownAfterSeconds.value = 300; scaleCooldownSeconds.value = 60
    },
  },
  {
    key: "game", label: t('components.createGroup.presets.game.label'), desc: t('components.createGroup.presets.game.desc'), icon: Rocket,
    color: "text-primary", border: "border-primary/40", bg: "bg-primary/5", iconBg: "bg-primary/10",
    apply() {
      // Scaling: dynamic 0–20 instances, 50 players each, scale at 80%
      scalingMode.value = "DYNAMIC"; isStatic.value = false
      minInstances.value = 0; maxInstances.value = 20; maxPlayers.value = 50
      scaleUpThreshold.value = 80; scaleDownAfterSeconds.value = 300; scaleCooldownSeconds.value = 60
      // Resources: 2G for game servers, fill games before opening new ones
      memoryMb.value = 2048; routing.value = "FILL_FIRST"
      // Lifecycle: longer shutdown for world save, don't update running games
      startupTimeoutSeconds.value = 120; shutdownGraceSeconds.value = 60
      maxLifetimeSeconds.value = 0; drainOnShutdown.value = false
      updateStrategy.value = "ON_NEW"
      // Orchestration: not a default group, not a proxy
      defaultGroup.value = false; maintenance.value = false
    },
  },
  {
    key: "proxy", label: t('components.createGroup.presets.proxy.label'), desc: t('components.createGroup.presets.proxy.desc'), icon: Network,
    color: "text-warning", border: "border-warning/40", bg: "bg-warning/5", iconBg: "bg-warning/10",
    apply() {
      // Scaling: 1–3 static proxies, 500 connections each
      scalingMode.value = "STATIC"; isStatic.value = true
      minInstances.value = 1; maxInstances.value = 3; maxPlayers.value = 500
      // Resources: lightweight 512M, round-robin across proxies
      memoryMb.value = 512; routing.value = "ROUND_ROBIN"
      // Lifecycle: proxies boot fast, need time to drain players on shutdown
      startupTimeoutSeconds.value = 60; shutdownGraceSeconds.value = 60
      maxLifetimeSeconds.value = 0; drainOnShutdown.value = true
      updateStrategy.value = "ROLLING"
      // Orchestration: proxies can't be default groups (players connect TO them)
      defaultGroup.value = false; maintenance.value = false
      // Reset dynamic-only values
      scaleUpThreshold.value = 80; scaleDownAfterSeconds.value = 300; scaleCooldownSeconds.value = 60
    },
  },
])
const activePreset = ref<string | null>(null)
function applyPreset(preset: Preset) { activePreset.value = preset.key; preset.apply() }

// Filter presets by selected platform category
const visiblePresets = computed(() => {
  if (!platform.value) return [] // no platform → no presets
  if (isProxy.value) return presets.value.filter(p => p.key === "proxy")
  return presets.value.filter(p => p.key !== "proxy") // server → lobby + game
})

// ── Validation ──────────────────────────────
const nameError = computed(() => {
  if (!name.value) return null
  if (name.value.length > 32) return t('components.createGroup.validation.maxChars')
  if (!/^[a-z0-9_][a-z0-9_-]*$/.test(name.value)) return t('components.createGroup.validation.nameFormat')
  if (store.groups.find(g => g.name === name.value)) return t('components.createGroup.validation.exists')
  return null
})
const nameValid = computed(() => name.value.length > 0 && !nameError.value)
const stepValid = computed(() => {
  switch (step.value) {
    case 1: return nameValid.value && (!!platform.value || jarFile.value.trim().length > 0)
    case 2: return scalingMode.value === "MANUAL" || minInstances.value <= maxInstances.value
    case 3: return memoryMb.value >= 128 && portRangeStart.value <= portRangeEnd.value
    case 4: return true
    case 5: return true
    case 6: return true
    default: return false
  }
})

// ── Steps ───────────────────────────────────
const steps = computed(() => [
  { label: t('components.createGroup.steps.identity.label'), desc: t('components.createGroup.steps.identity.desc'), icon: Layers },
  { label: t('components.createGroup.steps.scaling.label'), desc: t('components.createGroup.steps.scaling.desc'), icon: Activity },
  { label: t('components.createGroup.steps.resources.label'), desc: t('components.createGroup.steps.resources.desc'), icon: Cpu },
  { label: t('components.createGroup.steps.lifecycle.label'), desc: t('components.createGroup.steps.lifecycle.desc'), icon: Clock },
  { label: t('components.createGroup.steps.orchestration.label'), desc: t('components.createGroup.steps.orchestration.desc'), icon: GitBranch },
  { label: t('components.createGroup.steps.review.label'), desc: t('components.createGroup.steps.review.desc'), icon: Eye },
])

// ── Config options ──────────────────────────
const scalingModes = computed(() => [
  { key: "DYNAMIC" as const, label: t('components.createGroup.scalingModes.dynamic.label'), desc: t('components.createGroup.scalingModes.dynamic.desc'), icon: Activity, color: "text-success", border: "border-success/40", bg: "bg-success/5", shadow: "shadow-success/30", iconBg: "bg-success/10" },
  { key: "STATIC" as const, label: t('components.createGroup.scalingModes.static.label'), desc: t('components.createGroup.scalingModes.static.desc'), icon: Box, color: "text-primary", border: "border-primary/40", bg: "bg-primary/5", shadow: "shadow-primary/30", iconBg: "bg-primary/10" },
  { key: "MANUAL" as const, label: t('components.createGroup.scalingModes.manual.label'), desc: t('components.createGroup.scalingModes.manual.desc'), icon: Gauge, color: "text-warning", border: "border-warning/40", bg: "bg-warning/5", shadow: "shadow-warning/30", iconBg: "bg-warning/10" },
])
const routingOptions = computed(() => [
  { key: "LOWEST_PLAYERS", label: t('components.createGroup.routing.lowestPlayers.label'), desc: t('components.createGroup.routing.lowestPlayers.desc'), icon: Users },
  { key: "ROUND_ROBIN", label: t('components.createGroup.routing.roundRobin.label'), desc: t('components.createGroup.routing.roundRobin.desc'), icon: Route },
  { key: "RANDOM", label: t('components.createGroup.routing.random.label'), desc: t('components.createGroup.routing.random.desc'), icon: Sparkles },
  { key: "FILL_FIRST", label: t('components.createGroup.routing.fillFirst.label'), desc: t('components.createGroup.routing.fillFirst.desc'), icon: Box },
])
const updateStrategies = computed(() => [
  { key: "ROLLING" as const, label: t('components.createGroup.strategies.rolling.label'), desc: t('components.createGroup.strategies.rolling.desc') },
  { key: "ON_NEW" as const, label: t('components.createGroup.strategies.onNew.label'), desc: t('components.createGroup.strategies.onNew.desc') },
  { key: "CANARY" as const, label: t('components.createGroup.strategies.canary.label'), desc: t('components.createGroup.strategies.canary.desc') },
  { key: "MANUAL" as const, label: t('components.createGroup.strategies.manual.label'), desc: t('components.createGroup.strategies.manual.desc') },
])
const memoryPresets = computed(() => [
  { mb: 512, label: "512M", hint: t('components.createGroup.memHints.light') }, { mb: 1024, label: "1G", hint: t('components.createGroup.memHints.standard') },
  { mb: 2048, label: "2G", hint: t('components.createGroup.memHints.moderate') }, { mb: 4096, label: "4G", hint: t('components.createGroup.memHints.heavy') },
  { mb: 8192, label: "8G", hint: t('components.createGroup.memHints.extreme') },
])

// ── Helpers ─────────────────────────────────
function formatMem(mb: number) { return mb >= 1024 ? `${(mb / 1024).toFixed(mb % 1024 === 0 ? 0 : 1)}G` : `${mb}M` }
function onInstanceRangeChange(values: number[]) { minInstances.value = values[0]!; maxInstances.value = values[1]! }
const fmtPct = (v: number) => `${v}%`
const fmtSec = (v: number) => `${v}s`
const fmtLifetime = (v: number) => v === 0 ? '∞' : `${v}s`
const fmtMemSlider = (v: number) => formatMem(v)
function addEnvPair() { envPairs.value.push({ key: "", value: "" }) }
function removeEnvPair(i: number) { envPairs.value.splice(i, 1) }
function splitTags(s: string): string[] { return s.split(",").map(t => t.trim()).filter(Boolean) }
function clamp(v: number, min: number, max: number) { return Math.max(min, Math.min(max, Math.round(v))) }
function onInlineEdit(event: Event, setter: (v: number) => void, min: number, max: number) {
  const el = event.target as HTMLInputElement; const raw = Number(el.value)
  if (!Number.isNaN(raw)) setter(clamp(raw, min, max)); el.blur()
}

// ── Navigation ──────────────────────────────
function goNext() {
  if (!stepValid.value) return
  if (step.value === 2) isStatic.value = scalingMode.value === "STATIC"
  direction.value = "forward"; step.value++
}
function goBack() { direction.value = "backward"; step.value-- }
function goToStep(target: number) { if (target < step.value) { direction.value = "backward"; step.value = target } }

// A11y (WCAG 2.4.3): when a step finishes transitioning in, move focus to its
// first control so keyboard/SR users follow the change instead of being left on
// the Next/Back button of the previous step. Fires only on step swaps — the
// dialog's own open-focus is handled by the Reka-UI DialogContent.
function onStepEntered(el: Element) { focusFirst(el as HTMLElement) }

const showPlacement = ref(false)

// ── Submit ──────────────────────────────────
async function submit() {
  loading.value = true
  try {
    const env: Record<string, string> = {}
    for (const p of envPairs.value) { if (p.key.trim()) env[p.key.trim()] = p.value }
    const plat = platform.value.trim()
    for (const tpl of mandatoryTemplateStatus.value) {
      if (!tpl.exists) await templateStore.createTemplate({ name: tpl.name, description: tpl.name === "base" ? t('components.createGroup.tplDesc.base') : tpl.name.startsWith("base-") ? t('components.createGroup.tplDesc.basePlatform', { platform: plat }) : t('components.createGroup.tplDesc.group', { name: tpl.name }), platform: plat })
    }
    await store.createGroup({
      name: name.value.trim(), parent: parent.value.trim() || null, platform: plat.toUpperCase(),
      platformVersion: platformVersion.value.trim() || recommendedVersion.value, jarFile: jarFile.value.trim(),
      templates: [...extraTemplates.value],
      scalingMode: scalingMode.value, minInstances: minInstances.value, maxInstances: maxInstances.value,
      maxPlayers: maxPlayers.value, scaleUpThreshold: scaleUpThreshold.value / 100,
      scaleDownAfterSeconds: scaleDownAfterSeconds.value, scaleCooldownSeconds: scaleCooldownSeconds.value,
      predictiveScaling: predictiveScaling.value, scaleUpMargin: scaleUpMargin.value / 100, burstCeiling: burstCeiling.value,
      routing: routing.value, portRangeStart: portRangeStart.value, portRangeEnd: portRangeEnd.value,
      startupTimeoutSeconds: startupTimeoutSeconds.value, shutdownGraceSeconds: shutdownGraceSeconds.value,
      drainOnShutdown: drainOnShutdown.value, maxLifetimeSeconds: maxLifetimeSeconds.value,
      static: isStatic.value, staticInstanceNames: splitTags(staticInstanceNames.value),
      protectedPaths: splitTags(protectedPaths.value), fallbackGroup: fallbackGroup.value.trim() || null,
      bedrockProxyGroup: isGeyser.value ? bedrockProxyGroup.value.trim() || null : null,
      defaultGroup: defaultGroup.value, dependsOn: splitTags(dependsOn.value), startupWeight: startupWeight.value,
      maintenance: maintenance.value, updateStrategy: updateStrategy.value,
      nodeAffinity: splitTags(nodeAffinity.value), nodeAntiAffinity: splitTags(nodeAntiAffinity.value),
      spreadConstraint: spreadConstraint.value.trim(), priority: priority.value,
      memoryMb: memoryMb.value, jvmArgs: splitTags(jvmArgs.value), env: Object.keys(env).length ? env : {},
    } as any)
    open.value = false
  }
  catch { toast.error(t('components.createGroup.toast.createFailedTitle'), { description: t('components.createGroup.toast.createFailedDesc') }) }
  finally { loading.value = false }
}

function handleOpen(value: boolean) {
  open.value = value
  if (value) {
    step.value = 1; direction.value = "forward"; activePreset.value = null
    platform.value = ""; platformSearch.value = ""; name.value = ""; parent.value = ""
    platformVersion.value = ""; jarFile.value = "server.jar"; extraTemplates.value = []
    scalingMode.value = "DYNAMIC"; minInstances.value = 0; maxInstances.value = 10
    maxPlayers.value = 100; memoryMb.value = 1024; scaleUpThreshold.value = 80
    scaleDownAfterSeconds.value = 300; scaleCooldownSeconds.value = 60
    isStatic.value = false; staticInstanceNames.value = ""
    routing.value = "LOWEST_PLAYERS"; portRangeStart.value = 30000; portRangeEnd.value = 30100
    startupTimeoutSeconds.value = 120; shutdownGraceSeconds.value = 30
    drainOnShutdown.value = false; maxLifetimeSeconds.value = 0; protectedPaths.value = ""
    fallbackGroup.value = ""; bedrockProxyGroup.value = ""; defaultGroup.value = false; dependsOn.value = ""
    startupWeight.value = 0; maintenance.value = false; updateStrategy.value = "ROLLING"
    nodeAffinity.value = ""; nodeAntiAffinity.value = ""; spreadConstraint.value = ""
    priority.value = 0; jvmArgs.value = ""; envPairs.value = []
    predictiveScaling.value = false; scaleUpMargin.value = 20; burstCeiling.value = 0
    showPlacement.value = false
    catalogStore.fetchCatalog(); templateStore.fetchTemplates(); nodesStore.fetchNodes()
  }
}

function selectPlatform(p: string) {
  platform.value = p; activePreset.value = null
  nextTick(() => { if (!platformVersion.value || platformVersion.value === recommendedVersion.value) platformVersion.value = recommendedVersion.value })
}

// ── Review step ─────────────────────────────
const reviewCards = computed(() => [
  { icon: Layers, title: t('components.createGroup.steps.identity.label'), step: 1, rows: [
    [t('components.createGroup.review.name'), name.value],
    [t('components.createGroup.review.platform'), `${platform.value || t('components.createGroup.review.custom')} ${platformVersion.value || recommendedVersion.value}`],
    ...(parent.value ? [[t('components.createGroup.review.parent'), parent.value]] : []),
    [t('components.createGroup.review.templates'), [...mandatoryTemplateNames.value, ...extraTemplates.value].join(' → ')],
  ]},
  { icon: Activity, title: t('components.createGroup.steps.scaling.label'), step: 2, rows: [
    [t('components.createGroup.review.mode'), scalingMode.value],
    [t('components.createGroup.review.instances'), scalingMode.value === 'MANUAL' ? t('components.createGroup.review.onDemand') : `${minInstances.value}–${maxInstances.value}`],
    [t('components.createGroup.review.players'), t('components.createGroup.review.playersPerInstance', { count: maxPlayers.value })],
    ...(scalingMode.value === 'DYNAMIC' ? [[t('components.createGroup.review.scaleUp'), `${scaleUpThreshold.value}%`]] : []),
  ]},
  { icon: Cpu, title: t('components.createGroup.review.resourcesNetwork'), step: 3, rows: [
    [t('components.createGroup.review.memory'), formatMem(memoryMb.value)],
    [t('components.createGroup.review.routing'), routing.value.replace(/_/g, ' ').toLowerCase()],
    [t('components.createGroup.review.ports'), `${portRangeStart.value}–${portRangeEnd.value}`],
    ...(jvmArgs.value ? [[t('components.createGroup.review.jvm'), jvmArgs.value]] : []),
    ...(envPairs.value.length ? [[t('components.createGroup.review.envVars'), t('components.createGroup.review.varCount', { count: envPairs.value.length }, envPairs.value.length)]] : []),
  ]},
  { icon: Clock, title: t('components.createGroup.review.lifecycleDeployment'), step: 4, rows: [
    [t('components.createGroup.review.strategy'), updateStrategy.value],
    [t('components.createGroup.review.startup'), `${startupTimeoutSeconds.value}s`],
    [t('components.createGroup.review.drain'), drainOnShutdown.value ? t('components.createGroup.yes') : t('components.createGroup.no')],
    ...(maxLifetimeSeconds.value > 0 ? [[t('components.createGroup.review.maxLife'), `${maxLifetimeSeconds.value}s`]] : []),
  ]},
  { icon: GitBranch, title: t('components.createGroup.steps.orchestration.label'), step: 5, rows: [
    ...(dependsOn.value ? [[t('components.createGroup.review.dependsOn'), dependsOn.value]] : []),
    ...(defaultGroup.value ? [[t('components.createGroup.review.defaultGroup'), t('components.createGroup.yes')]] : []),
    ...(maintenance.value ? [[t('components.createGroup.review.maintenance'), t('components.createGroup.yes')]] : []),
  ]},
])
</script>

<template>
  <Dialog :open="open" @update:open="handleOpen">
    <DialogTrigger as-child>
      <Button class="bg-primary hover:bg-primary/90 text-primary-foreground"><Plus class="size-5 mr-2" /> {{ t('components.createGroup.newGroup') }}</Button>
    </DialogTrigger>
    <DialogContent class="bg-popover backdrop-blur-xl border-glass-border rounded-2xl sm:max-w-4xl [&>button:last-child]:hidden overflow-hidden p-0 max-h-[90vh] flex flex-col" :aria-describedby="undefined">
      <DialogTitle class="sr-only">{{ t('components.createGroup.dialogTitle') }}</DialogTitle>
      <div class="flex flex-1 min-h-0">

        <!-- ── Stepper sidebar ── -->
        <div class="w-56 shrink-0 border-r border-glass-border bg-glass/30 flex flex-col">
          <div class="px-5 pt-6 pb-4">
            <div class="flex items-center gap-2.5">
              <div class="size-8 rounded-xl bg-primary/10 border border-primary/20 flex items-center justify-center"><Package class="size-4 text-primary" /></div>
              <div><p class="text-sm font-bold text-foreground">{{ t('components.createGroup.newGroup') }}</p><p class="text-[10px] text-muted-foreground">{{ t('components.createGroup.setupWizard') }}</p></div>
            </div>
          </div>
          <Separator class="bg-glass-border" />
          <nav class="flex-1 px-3 py-3 overflow-y-auto styled-scrollbar">
            <ol class="flex flex-col gap-0.5">
              <li v-for="(s, i) in steps" :key="i">
                <button
type="button" :disabled="i + 1 > step"
                  :class="['w-full flex items-center gap-3 px-3 py-2 rounded-xl text-left transition-all duration-200',
                    i + 1 === step ? 'bg-primary/10 border border-primary/20' : i + 1 < step ? 'hover:bg-glass-hover/60 cursor-pointer' : 'opacity-40 cursor-default']"
                  @click="goToStep(i + 1)">
                  <div
:class="['size-6 rounded-lg flex items-center justify-center shrink-0 transition-all duration-200',
                    i + 1 === step ? 'bg-primary text-primary-foreground shadow-[0_0_10px_-2px] shadow-primary/40' : i + 1 < step ? 'bg-success/15 text-success' : 'bg-glass text-muted-foreground']">
                    <Check v-if="i + 1 < step" class="size-3" />
                    <component :is="s.icon" v-else class="size-3" />
                  </div>
                  <div class="min-w-0">
                    <p :class="['text-[11px] font-medium truncate', i + 1 <= step ? 'text-foreground' : 'text-muted-foreground']">{{ s.label }}</p>
                    <p class="text-[9px] text-muted-foreground/50 truncate">{{ s.desc }}</p>
                  </div>
                </button>
                <div v-if="i < steps.length - 1" class="flex justify-start pl-5 py-0.5">
                  <div :class="['w-px h-2 transition-colors duration-300', i + 1 < step ? 'bg-success/30' : 'bg-glass-border/50']" />
                </div>
              </li>
            </ol>
          </nav>
        </div>

        <!-- ── Content ── -->
        <div class="flex-1 flex flex-col min-h-0">
          <div class="flex-1 overflow-y-auto styled-scrollbar px-8 py-6">
            <Transition :name="direction === 'forward' ? 'slide-left' : 'slide-right'" mode="out-in" @after-enter="onStepEntered">

              <!-- ═══════ STEP 1: Identity ═══════ -->
              <div v-if="step === 1" key="s1" class="flex flex-col gap-5">
                <div class="flex flex-col gap-2">
                  <div class="flex items-center justify-between">
                    <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s1.platform') }}</Label>
                    <button v-if="platform" type="button" class="text-[11px] text-primary hover:text-primary/80 transition-colors flex items-center gap-1" @click="platform = ''; platformVersion = ''"><X class="size-2.5" /> {{ t('components.createGroup.s1.clear') }}</button>
                  </div>
                  <div v-if="!platform && catalogStore.entries.length > 4" class="relative">
                    <Search class="absolute left-3 top-1/2 -translate-y-1/2 size-3.5 text-muted-foreground/40" />
                    <input v-model="platformSearch" type="text" :placeholder="t('components.createGroup.s1.searchPlatforms')" class="w-full h-9 pl-9 pr-3 bg-glass/60 rounded-xl border border-glass-border text-foreground text-xs placeholder:text-muted-foreground/40 focus:outline-none focus:border-primary/40 transition-colors" >
                  </div>
                  <div v-if="!platform" class="flex flex-col gap-3">
                    <div v-if="!hasResults && platformSearch" class="py-8 text-center"><Search class="size-5 text-muted-foreground/30 mx-auto mb-2" /><p class="text-xs text-muted-foreground">{{ t('components.createGroup.s1.noMatch', { q: platformSearch }) }}</p></div>
                    <template v-if="hasResults">
                      <template v-for="[label, catIcon, catColor, entries] in ([[t('components.createGroup.s1.servers'), Server, 'success', filteredServers], [t('components.createGroup.s1.proxies'), Network, 'primary', filteredProxies]] as const)" :key="label">
                        <template v-if="entries.length">
                          <div class="flex items-center gap-2 pt-1"><component :is="catIcon" class="size-3 text-muted-foreground/40" /><span class="text-[10px] font-medium text-muted-foreground/50 uppercase tracking-widest">{{ label }}</span><div class="flex-1 h-px bg-glass-border/30" /></div>
                          <div class="grid grid-cols-2 gap-2">
                            <button v-for="entry in entries" :key="entry.platform" type="button" class="group/plat flex items-center gap-3 px-3.5 py-3 rounded-xl border border-glass-border bg-glass/20 hover:bg-glass-hover/60 hover:border-primary/30 text-left transition-all hover:-translate-y-0.5" @click="selectPlatform(entry.platform)">
                              <div :class="['size-10 rounded-xl flex items-center justify-center shrink-0 transition-colors', catColor === 'success' ? 'bg-success/8 group-hover/plat:bg-success/15' : 'bg-primary/8 group-hover/plat:bg-primary/15']"><component :is="catIcon" :class="['size-4.5', catColor === 'success' ? 'text-success/70 group-hover/plat:text-success' : 'text-primary/70 group-hover/plat:text-primary']" /></div>
                              <div class="flex-1 min-w-0"><p class="text-sm font-semibold capitalize text-foreground/80 group-hover/plat:text-foreground truncate">{{ entry.platform }}</p><p class="text-[10px] text-muted-foreground/50 mt-0.5">{{ t('components.createGroup.s1.versionCount', { count: entry.versions.length }, entry.versions.length) }}<span v-if="entry.versions.find(v => v.recommended)" class="text-muted-foreground/40"> · {{ entry.versions.find(v => v.recommended)?.version }}</span></p></div>
                              <ArrowRight class="size-3.5 text-muted-foreground/20 group-hover/plat:text-primary/50 transition-colors shrink-0" />
                            </button>
                          </div>
                        </template>
                      </template>
                    </template>
                    <button v-if="!platformSearch" type="button" class="flex items-center gap-3 px-3.5 py-3 rounded-xl border border-dashed border-glass-border/50 hover:bg-glass-hover/40 text-left transition-all group/custom" @click="platform = ''; platformVersion = ''">
                      <div class="size-10 rounded-xl bg-glass flex items-center justify-center shrink-0"><Terminal class="size-4.5 text-muted-foreground/40 group-hover/custom:text-muted-foreground/60 transition-colors" /></div>
                      <div><p class="text-sm font-medium text-muted-foreground/60 group-hover/custom:text-muted-foreground transition-colors">{{ t('components.createGroup.s1.customPlatform') }}</p><p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s1.byoJar') }}</p></div>
                    </button>
                  </div>
                  <div v-if="platform" class="rounded-xl border border-primary/25 bg-primary/5 overflow-hidden">
                    <div class="flex items-center gap-3 px-4 py-3">
                      <div :class="['size-10 rounded-xl flex items-center justify-center', isProxy ? 'bg-primary/15' : 'bg-success/15']"><component :is="isProxy ? Network : Server" :class="['size-4.5', isProxy ? 'text-primary' : 'text-success']" /></div>
                      <div class="flex-1 min-w-0"><p class="text-sm font-bold text-foreground capitalize">{{ platform }}</p><p class="text-[10px] text-muted-foreground">{{ isProxy ? t('components.createGroup.proxy') : t('components.createGroup.server') }} · {{ t('components.createGroup.s1.versionsLabel', { count: availableVersions.length }) }}</p></div>
                      <Badge variant="outline" class="text-[10px] border-primary/20 text-primary shrink-0">{{ t('components.createGroup.s1.selected') }}</Badge>
                    </div>
                    <div class="px-4 py-3 border-t border-primary/15 bg-primary/3">
                      <p class="text-[10px] text-muted-foreground/60 uppercase tracking-wider font-medium mb-2">{{ t('components.createGroup.s1.version') }}</p>
                      <div v-if="availableVersions.length" class="flex flex-wrap gap-1.5">
                        <button
v-for="v in availableVersions" :key="v.version" type="button"
                          :class="['inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs font-mono transition-all hover:-translate-y-0.5',
                            platformVersion === v.version ? 'border-primary/40 bg-primary/15 ring-1 ring-primary/20 text-foreground' : 'border-glass-border/50 bg-popover/50 text-muted-foreground hover:bg-glass-hover/60 hover:text-foreground']"
                          @click="platformVersion = v.version">{{ v.version }}<span v-if="v.recommended" class="inline-flex items-center gap-0.5 text-[9px] text-success font-sans font-medium"><CircleDot class="size-2.5" /> {{ t('components.createGroup.s1.rec') }}</span></button>
                      </div>
                      <div v-else><Input v-model="platformVersion" :placeholder="t('components.createGroup.s1.versionPlaceholder')" autocomplete="off" class="bg-glass border-glass-border font-mono h-8 text-xs" /></div>
                    </div>
                  </div>
                </div>
                <div v-if="visiblePresets.length" class="flex flex-col gap-2">
                  <Label class="uppercase tracking-wider text-[10px] text-muted-foreground/60">{{ t('components.createGroup.s1.quickStart') }}</Label>
                  <div :class="['grid gap-2', visiblePresets.length === 1 ? 'grid-cols-1 max-w-xs' : visiblePresets.length === 2 ? 'grid-cols-2' : 'grid-cols-3']">
                    <button
v-for="preset in visiblePresets" :key="preset.key" type="button"
                      :class="['flex items-center gap-2.5 px-3 py-2.5 rounded-xl border text-left transition-all hover:-translate-y-0.5',
                        activePreset === preset.key ? [preset.border, preset.bg] : 'border-glass-border hover:bg-glass-hover/60']"
                      @click="applyPreset(preset)">
                      <div :class="['size-8 rounded-lg flex items-center justify-center', activePreset === preset.key ? preset.iconBg : 'bg-glass']"><component :is="preset.icon" :class="['size-3.5', activePreset === preset.key ? preset.color : 'text-muted-foreground']" /></div>
                      <div><p :class="['text-xs font-medium', activePreset === preset.key ? 'text-foreground' : 'text-muted-foreground']">{{ preset.label }}</p><p class="text-[10px] text-muted-foreground/50">{{ preset.desc }}</p></div>
                    </button>
                  </div>
                </div>
                <Separator class="bg-glass-border" />
                <div class="flex flex-col gap-1.5">
                  <Label for="g-name" class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s1.groupName') }}</Label>
                  <Input id="g-name" v-model="name" :placeholder="t('components.createGroup.s1.namePlaceholder')" autocomplete="off" class="bg-glass border-glass-border" />
                  <p v-if="nameError" class="text-xs text-destructive">{{ nameError }}</p>
                </div>

                <!-- Template layer — derived from platform + name, shown here so the user sees the connection -->
                <div v-if="name && (platform || jarFile !== 'server.jar')" class="flex flex-col gap-1.5">
                  <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s1.templateLayer') }}</Label>
                  <div class="flex items-center gap-2 p-3 rounded-xl border border-glass-border bg-glass/40 overflow-x-auto">
                    <template v-for="(tpl, i) in mandatoryTemplateStatus" :key="tpl.name">
                      <div
class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg border text-xs font-medium font-mono shrink-0"
                        :class="tpl.exists ? 'border-success/30 bg-success/5 text-success' : 'border-warning/30 bg-warning/5 text-warning'">
                        <FileCode class="size-3" /> {{ tpl.name }} <Check v-if="tpl.exists" class="size-3" /><Plus v-else class="size-3" />
                      </div>
                      <ArrowRight v-if="i < mandatoryTemplateStatus.length - 1" class="size-3 text-muted-foreground/40 shrink-0" />
                    </template>
                  </div>
                  <p class="text-[10px] text-muted-foreground/50"><span class="text-success">{{ t('components.createGroup.s1.green') }}</span> {{ t('components.createGroup.s1.legendExists') }}, <span class="text-warning">{{ t('components.createGroup.s1.yellow') }}</span> {{ t('components.createGroup.s1.legendAuto') }}</p>
                </div>

                <div v-if="!platform" class="flex flex-col gap-1.5">
                  <Label for="g-jar" class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s1.jarFile') }}</Label>
                  <Input id="g-jar" v-model="jarFile" :placeholder="t('components.createGroup.s1.jarPlaceholder')" autocomplete="off" class="bg-glass border-glass-border font-mono" />
                  <p class="text-[11px] text-warning/80">{{ t('components.createGroup.s1.jarHint') }}</p>
                </div>
              </div>

              <!-- ═══════ STEP 2: Scaling ═══════ -->
              <div v-else-if="step === 2" key="s2" class="flex flex-col gap-5">
                <div class="flex flex-col gap-2">
                  <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s2.scalingMode') }}</Label>
                  <div class="grid grid-cols-3 gap-2">
                    <button
v-for="mode in scalingModes" :key="mode.key" type="button"
                      :class="['flex flex-col items-center gap-2 px-3 py-3.5 rounded-xl border text-center transition-all hover:-translate-y-0.5',
                        scalingMode === mode.key ? [mode.border, mode.bg, 'shadow-[0_0_20px_-4px]', mode.shadow] : 'border-glass-border hover:bg-glass-hover/60']"
                      @click="scalingMode = mode.key; activePreset = null">
                      <div :class="['size-9 rounded-xl flex items-center justify-center', scalingMode === mode.key ? mode.iconBg : 'bg-glass']"><component :is="mode.icon" :class="['size-4', scalingMode === mode.key ? mode.color : 'text-muted-foreground']" /></div>
                      <div><p :class="['text-sm font-medium', scalingMode === mode.key ? 'text-foreground' : 'text-muted-foreground']">{{ mode.label }}</p><p class="text-[10px] text-muted-foreground/60 mt-0.5">{{ mode.desc }}</p></div>
                    </button>
                  </div>
                </div>

                <template v-if="scalingMode === 'DYNAMIC'">
                  <div class="grid grid-cols-2 gap-3">
                    <div class="rounded-xl border border-glass-border bg-glass/20 p-4 flex flex-col gap-3">
                      <div><div class="flex items-center justify-between"><span class="text-[10px] text-muted-foreground/60 uppercase tracking-wider font-medium">{{ t('components.createGroup.s2.instanceRange') }}</span><span class="text-[10px] font-mono text-muted-foreground/40">0 – 50</span></div><p class="text-[10px] text-muted-foreground/40 mt-0.5">{{ t('components.createGroup.s2.instanceRangeHint') }}</p></div>
                      <Slider :model-value="[minInstances, maxInstances]" :min="0" :max="50" :step="1" :min-steps-between-thumbs="1" show-tooltip @update:model-value="onInstanceRangeChange($event!)" />
                      <div class="flex items-center justify-between text-[11px]">
                        <span class="text-muted-foreground flex items-center gap-1">{{ t('components.createGroup.min') }} <input type="number" :value="minInstances" :min="0" :max="50" class="inline-number-input" @change="onInlineEdit($event, v => { minInstances = v; if (v > maxInstances) maxInstances = v }, 0, 50)" @keydown.enter="($event.target as HTMLInputElement).blur()" ></span>
                        <span class="text-muted-foreground flex items-center gap-1">{{ t('components.createGroup.max') }} <input type="number" :value="maxInstances" :min="0" :max="50" class="inline-number-input" @change="onInlineEdit($event, v => { maxInstances = v; if (v < minInstances) minInstances = v }, 0, 50)" @keydown.enter="($event.target as HTMLInputElement).blur()" ></span>
                      </div>
                    </div>
                    <div class="rounded-xl border border-glass-border bg-glass/20 p-4 flex flex-col gap-3">
                      <div><div class="flex items-center justify-between"><span class="text-[10px] text-muted-foreground/60 uppercase tracking-wider font-medium">{{ t('components.createGroup.s2.playersInstance') }}</span><span class="text-[10px] font-mono text-muted-foreground/40">10 – 500</span></div><p class="text-[10px] text-muted-foreground/40 mt-0.5">{{ t('components.createGroup.s2.slotsHint') }}</p></div>
                      <Slider :model-value="[maxPlayers]" :min="10" :max="500" :step="10" show-tooltip @update:model-value="maxPlayers = $event![0]!" />
                      <div class="flex items-center justify-between text-[11px]">
                        <span class="text-muted-foreground flex items-center gap-1">{{ t('components.createGroup.max') }} <input type="number" :value="maxPlayers" :min="1" :max="500" class="inline-number-input" @change="onInlineEdit($event, v => maxPlayers = v, 1, 500)" @keydown.enter="($event.target as HTMLInputElement).blur()" ></span>
                        <span class="text-muted-foreground">= <span class="font-mono font-semibold text-foreground">{{ maxInstances * maxPlayers }}</span> {{ t('components.createGroup.total') }}</span>
                      </div>
                    </div>
                  </div>
                  <div class="rounded-xl border border-glass-border bg-glass/20 p-4 flex flex-col gap-3">
                    <div><div class="flex items-center justify-between"><span class="text-[10px] text-muted-foreground/60 uppercase tracking-wider font-medium">{{ t('components.createGroup.s2.scaleUpThreshold') }}</span><span class="text-[10px] font-mono text-muted-foreground/40">10% – 100%</span></div><p class="text-[10px] text-muted-foreground/40 mt-0.5">{{ t('components.createGroup.s2.newInstanceWhen', { filled: Math.round(maxPlayers * scaleUpThreshold / 100), total: maxPlayers }) }}</p></div>
                    <Slider :model-value="[scaleUpThreshold]" :min="10" :max="100" :step="5" show-tooltip :format-tooltip="fmtPct" @update:model-value="scaleUpThreshold = $event![0]!" />
                  </div>
                  <div class="grid grid-cols-2 gap-3">
                    <div class="rounded-xl border border-glass-border bg-glass/20 p-4 flex flex-col gap-2">
                      <div><div class="flex items-center justify-between"><span class="text-[10px] text-muted-foreground/60 uppercase tracking-wider font-medium">{{ t('components.createGroup.s2.scaleDownDelay') }}</span><span class="text-[10px] font-mono text-muted-foreground/40">0 – 900s</span></div><p class="text-[10px] text-muted-foreground/40 mt-0.5">{{ t('components.createGroup.s2.scaleDownHint') }}</p></div>
                      <Slider :model-value="[scaleDownAfterSeconds]" :min="0" :max="900" :step="30" show-tooltip :format-tooltip="fmtSec" @update:model-value="scaleDownAfterSeconds = $event![0]!" />
                    </div>
                    <div class="rounded-xl border border-glass-border bg-glass/20 p-4 flex flex-col gap-2">
                      <div><div class="flex items-center justify-between"><span class="text-[10px] text-muted-foreground/60 uppercase tracking-wider font-medium">{{ t('components.createGroup.s2.cooldown') }}</span><span class="text-[10px] font-mono text-muted-foreground/40">0 – 300s</span></div><p class="text-[10px] text-muted-foreground/40 mt-0.5">{{ t('components.createGroup.s2.cooldownHint') }}</p></div>
                      <Slider :model-value="[scaleCooldownSeconds]" :min="0" :max="300" :step="10" show-tooltip :format-tooltip="fmtSec" @update:model-value="scaleCooldownSeconds = $event![0]!" />
                    </div>
                  </div>
                  <!-- Predictive scaling — not implemented -->
                  <div class="flex items-center justify-between p-3 rounded-xl border border-glass-border bg-glass/20 opacity-50 cursor-not-allowed">
                    <div class="flex items-center gap-2.5">
                      <Sparkles class="size-3.5 text-muted-foreground/40" />
                      <div><p class="text-xs font-medium text-muted-foreground">{{ t('components.createGroup.s2.predictive') }}</p><p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s2.predictiveHint') }}</p></div>
                    </div>
                    <Badge variant="outline" class="text-[9px] text-muted-foreground border-glass-border">{{ t('components.createGroup.comingSoon') }}</Badge>
                  </div>
                </template>

                <template v-if="scalingMode === 'STATIC'">
                  <div class="grid grid-cols-2 gap-3">
                    <div class="rounded-xl border border-glass-border bg-glass/20 p-4 flex flex-col gap-3">
                      <div><div class="flex items-center justify-between"><span class="text-[10px] text-muted-foreground/60 uppercase tracking-wider font-medium">{{ t('components.createGroup.s2.instanceCount') }}</span><span class="text-[10px] font-mono text-muted-foreground/40">1 – 50</span></div><p class="text-[10px] text-muted-foreground/40 mt-0.5">{{ t('components.createGroup.s2.fixedHint') }}</p></div>
                      <Slider :model-value="[minInstances]" :min="1" :max="50" :step="1" show-tooltip @update:model-value="minInstances = $event![0]!; maxInstances = $event![0]!" />
                      <div class="flex items-center gap-1 text-[11px] text-muted-foreground">{{ t('components.createGroup.s2.running') }} <input type="number" :value="minInstances" :min="1" :max="50" class="inline-number-input" @change="onInlineEdit($event, v => { minInstances = v; maxInstances = v }, 1, 50)" @keydown.enter="($event.target as HTMLInputElement).blur()" > {{ t('components.createGroup.s2.instanceWord', minInstances) }}</div>
                    </div>
                    <div class="rounded-xl border border-glass-border bg-glass/20 p-4 flex flex-col gap-3">
                      <div><div class="flex items-center justify-between"><span class="text-[10px] text-muted-foreground/60 uppercase tracking-wider font-medium">{{ t('components.createGroup.s2.playersInstance') }}</span><span class="text-[10px] font-mono text-muted-foreground/40">10 – 500</span></div><p class="text-[10px] text-muted-foreground/40 mt-0.5">{{ t('components.createGroup.s2.slotsHint') }}</p></div>
                      <Slider :model-value="[maxPlayers]" :min="10" :max="500" :step="10" show-tooltip @update:model-value="maxPlayers = $event![0]!" />
                      <div class="flex items-center justify-between text-[11px]"><span class="text-muted-foreground flex items-center gap-1">{{ t('components.createGroup.max') }} <input type="number" :value="maxPlayers" :min="1" :max="500" class="inline-number-input" @change="onInlineEdit($event, v => maxPlayers = v, 1, 500)" @keydown.enter="($event.target as HTMLInputElement).blur()" ></span><span class="text-muted-foreground">= <span class="font-mono font-semibold text-foreground">{{ minInstances * maxPlayers }}</span> {{ t('components.createGroup.total') }}</span></div>
                    </div>
                  </div>
                  <div class="flex flex-col gap-1.5">
                    <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s2.instanceNames') }} <span class="normal-case text-muted-foreground font-normal tracking-normal">{{ t('components.createGroup.optional') }}</span></Label>
                    <Input v-model="staticInstanceNames" :placeholder="t('components.createGroup.s2.instanceNamesPlaceholder')" autocomplete="off" class="bg-glass border-glass-border font-mono" />
                    <p class="text-[11px] text-muted-foreground/50">{{ staticInstanceNames.trim() ? t('components.createGroup.s2.staticHintNames', { count: splitTags(staticInstanceNames).length }, splitTags(staticInstanceNames).length) : t('components.createGroup.s2.staticHintAuto', { name: name || 'group' }) }}</p>
                  </div>
                </template>

                <template v-if="scalingMode === 'MANUAL'">
                  <div class="rounded-xl border border-glass-border bg-glass/20 p-4 flex flex-col gap-3">
                    <div><div class="flex items-center justify-between"><span class="text-[10px] text-muted-foreground/60 uppercase tracking-wider font-medium">{{ t('components.createGroup.s2.playersInstance') }}</span><span class="text-[10px] font-mono text-muted-foreground/40">10 – 500</span></div><p class="text-[10px] text-muted-foreground/40 mt-0.5">{{ t('components.createGroup.s2.manualSlotsHint') }}</p></div>
                    <Slider :model-value="[maxPlayers]" :min="10" :max="500" :step="10" show-tooltip @update:model-value="maxPlayers = $event![0]!" />
                  </div>
                </template>
              </div>

              <!-- ═══════ STEP 3: Resources & Networking ═══════ -->
              <div v-else-if="step === 3" key="s3" class="flex flex-col gap-5">
                <div class="flex flex-col gap-2">
                  <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s3.memory') }}</Label>
                  <p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s3.memoryHint') }}</p>
                  <div class="grid grid-cols-5 gap-2">
                    <button
v-for="preset in memoryPresets" :key="preset.mb" type="button"
                      :class="['flex flex-col items-center gap-0.5 py-2.5 rounded-xl border transition-all hover:-translate-y-0.5',
                        memoryMb === preset.mb ? 'border-primary/40 bg-primary/8 ring-1 ring-primary/15 shadow-[0_0_20px_-4px] shadow-primary/30' : 'border-glass-border hover:bg-glass-hover/60']"
                      @click="memoryMb = preset.mb">
                      <span :class="['text-sm font-mono font-bold', memoryMb === preset.mb ? 'text-foreground' : 'text-foreground/80']">{{ preset.label }}</span>
                      <span class="text-[9px] text-muted-foreground/50">{{ preset.hint }}</span>
                    </button>
                  </div>
                  <Slider :model-value="[memoryMb]" :min="128" :max="16384" :step="128" show-tooltip :format-tooltip="fmtMemSlider" class="mt-1" @update:model-value="memoryMb = $event![0]!" />
                </div>
                <Separator class="bg-glass-border" />
                <div class="flex flex-col gap-2">
                  <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s3.routing') }}</Label>
                  <p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s3.routingHint') }}</p>
                  <div class="grid grid-cols-2 gap-2">
                    <button
v-for="opt in routingOptions" :key="opt.key" type="button"
                      :class="['flex items-center gap-2.5 px-3 py-2.5 rounded-xl border text-left transition-all hover:-translate-y-0.5',
                        routing === opt.key ? 'border-primary/40 bg-primary/8 ring-1 ring-primary/15' : 'border-glass-border hover:bg-glass-hover/60']"
                      @click="routing = opt.key">
                      <div :class="['size-8 rounded-lg flex items-center justify-center', routing === opt.key ? 'bg-primary/10' : 'bg-glass']"><component :is="opt.icon" :class="['size-3.5', routing === opt.key ? 'text-primary' : 'text-muted-foreground']" /></div>
                      <div><span :class="['text-xs font-medium', routing === opt.key ? 'text-foreground' : 'text-muted-foreground']">{{ opt.label }}</span><p class="text-[10px] text-muted-foreground/50">{{ opt.desc }}</p></div>
                    </button>
                  </div>
                </div>
                <Separator class="bg-glass-border" />
                <div class="flex flex-col gap-2">
                  <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s3.portRange') }}</Label>
                  <p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s3.portHint') }}</p>
                  <div class="grid grid-cols-2 gap-3">
                    <div class="flex flex-col gap-1.5"><Label class="text-[10px] text-muted-foreground/60">{{ t('components.createGroup.s3.start') }}</Label><Input v-model.number="portRangeStart" type="number" class="bg-glass border-glass-border font-mono" /></div>
                    <div class="flex flex-col gap-1.5"><Label class="text-[10px] text-muted-foreground/60">{{ t('components.createGroup.s3.end') }}</Label><Input v-model.number="portRangeEnd" type="number" class="bg-glass border-glass-border font-mono" /></div>
                  </div>
                  <p v-if="portRangeStart > portRangeEnd" class="text-xs text-destructive">{{ t('components.createGroup.s3.portError') }}</p>
                </div>
                <Separator class="bg-glass-border" />
                <div class="flex flex-col gap-1.5">
                  <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s3.jvmArgs') }} <span class="normal-case text-muted-foreground font-normal tracking-normal">{{ t('components.createGroup.optional') }}</span></Label>
                  <Input v-model="jvmArgs" :placeholder="t('components.createGroup.s3.jvmPlaceholder')" autocomplete="off" class="bg-glass border-glass-border font-mono text-xs" />
                </div>
                <div class="flex flex-col gap-2">
                  <div class="flex items-center justify-between">
                    <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s3.envVars') }}</Label>
                    <button type="button" class="inline-flex items-center gap-1 text-xs text-primary hover:text-primary/80 transition-colors" @click="addEnvPair"><Plus class="size-3" /> {{ t('components.createGroup.add') }}</button>
                  </div>
                  <div v-if="envPairs.length" class="flex flex-col gap-2">
                    <div v-for="(pair, i) in envPairs" :key="i" class="flex items-center gap-2">
                      <Input v-model="pair.key" :placeholder="t('components.createGroup.s3.keyPlaceholder')" autocomplete="off" class="bg-glass border-glass-border font-mono text-xs flex-1" />
                      <span class="text-xs text-muted-foreground">=</span>
                      <Input v-model="pair.value" :placeholder="t('components.createGroup.s3.valuePlaceholder')" autocomplete="off" class="bg-glass border-glass-border font-mono text-xs flex-1" />
                      <button type="button" :aria-label="t('components.createGroup.s3.removeEnvVar')" class="size-7 rounded-md flex items-center justify-center text-muted-foreground hover:text-destructive transition-colors shrink-0" @click="removeEnvPair(i)"><X class="size-3.5" /></button>
                    </div>
                  </div>
                  <p v-else class="text-[11px] text-muted-foreground/50">{{ t('components.createGroup.s3.noEnv') }}</p>
                </div>
              </div>

              <!-- ═══════ STEP 4: Lifecycle & Deployment ═══════ -->
              <div v-else-if="step === 4" key="s4" class="flex flex-col gap-5">
                <div class="rounded-xl border border-glass-border bg-glass/20 p-4 flex flex-col gap-4">
                  <span class="text-[10px] text-muted-foreground/60 uppercase tracking-wider font-medium">{{ t('components.createGroup.s4.instanceTimers') }}</span>
                  <div class="flex flex-col gap-1"><div class="flex items-center justify-between"><Label class="text-xs">{{ t('components.createGroup.s4.startupTimeout') }}</Label><span class="text-[10px] font-mono text-muted-foreground/40">10 – 600s</span></div><p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s4.startupHint') }}</p><Slider :model-value="[startupTimeoutSeconds]" :min="10" :max="600" :step="10" show-tooltip :format-tooltip="fmtSec" @update:model-value="startupTimeoutSeconds = $event![0]!" /></div>
                  <div class="flex flex-col gap-1"><div class="flex items-center justify-between"><Label class="text-xs">{{ t('components.createGroup.s4.shutdownGrace') }}</Label><span class="text-[10px] font-mono text-muted-foreground/40">0 – 120s</span></div><p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s4.graceHint') }}</p><Slider :model-value="[shutdownGraceSeconds]" :min="0" :max="120" :step="5" show-tooltip :format-tooltip="fmtSec" @update:model-value="shutdownGraceSeconds = $event![0]!" /></div>
                  <div class="flex flex-col gap-1"><div class="flex items-center justify-between"><Label class="text-xs">{{ t('components.createGroup.s4.maxLifetime') }}</Label><span class="text-[10px] font-mono text-muted-foreground/40">{{ t('components.createGroup.s4.maxLifetimeUnlimited') }}</span></div><p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s4.lifetimeHint') }}</p><Slider :model-value="[maxLifetimeSeconds]" :min="0" :max="86400" :step="300" show-tooltip :format-tooltip="fmtLifetime" @update:model-value="maxLifetimeSeconds = $event![0]!" /></div>
                </div>
                <div class="flex items-center justify-between p-3 rounded-xl border border-glass-border bg-glass/20">
                  <div><p class="text-xs font-medium text-foreground">{{ t('components.createGroup.s4.drain') }}</p><p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s4.drainHint') }}</p></div>
                  <Switch v-model="drainOnShutdown" />
                </div>
                <Separator class="bg-glass-border" />
                <div class="flex flex-col gap-2">
                  <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s4.updateStrategy') }}</Label>
                  <p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s4.strategyHint') }}</p>
                  <div class="grid grid-cols-4 gap-1.5">
                    <button
v-for="strat in updateStrategies" :key="strat.key" type="button"
                      :class="['flex flex-col px-2.5 py-2 rounded-xl border text-center transition-all hover:-translate-y-0.5',
                        updateStrategy === strat.key ? 'border-primary/40 bg-primary/8 ring-1 ring-primary/15' : 'border-glass-border hover:bg-glass-hover/60']"
                      @click="updateStrategy = strat.key">
                      <span :class="['text-xs font-medium', updateStrategy === strat.key ? 'text-foreground' : 'text-muted-foreground']">{{ strat.label }}</span>
                      <span class="text-[9px] text-muted-foreground/50 mt-0.5">{{ strat.desc }}</span>
                    </button>
                  </div>
                </div>
                <div v-if="scalingMode === 'STATIC'" class="flex flex-col gap-1.5">
                  <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s4.protectedPaths') }} <span class="normal-case text-muted-foreground font-normal tracking-normal">{{ t('components.createGroup.s4.staticOnly') }}</span></Label>
                  <Input v-model="protectedPaths" :placeholder="t('components.createGroup.s4.protectedPlaceholder')" autocomplete="off" class="bg-glass border-glass-border font-mono text-xs" />
                  <p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s4.protectedHint') }}</p>
                </div>
              </div>

              <!-- ═══════ STEP 5: Orchestration ═══════ -->
              <div v-else-if="step === 5" key="s5" class="flex flex-col gap-5">
                <!-- Extra templates (mandatory ones shown in Step 1) -->
                <div v-if="availableExtraTemplates.length" class="flex flex-col gap-2">
                  <Label class="uppercase tracking-wider text-xs">{{ t('components.createGroup.s5.additionalTemplates') }}</Label>
                  <p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s5.additionalHint') }}</p>
                  <div class="flex flex-wrap gap-1.5">
                    <button
v-for="tpl in availableExtraTemplates" :key="tpl.name" type="button"
                      :class="['inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg border text-xs transition-all',
                        extraTemplates.includes(tpl.name) ? 'border-primary/40 bg-primary/8 text-foreground' : 'border-glass-border text-muted-foreground hover:bg-glass-hover/60']"
                      @click="extraTemplates.includes(tpl.name) ? extraTemplates.splice(extraTemplates.indexOf(tpl.name), 1) : extraTemplates.push(tpl.name)">
                      <FileCode class="size-3" /> {{ tpl.name }} <Check v-if="extraTemplates.includes(tpl.name)" class="size-3 text-primary" />
                    </button>
                  </div>
                </div>

                <Separator v-if="availableExtraTemplates.length" class="bg-glass-border" />

                <!-- Group relationships -->
                <div class="flex flex-col gap-3">
                  <span class="text-[10px] text-muted-foreground/60 uppercase tracking-wider font-medium">{{ t('components.createGroup.s5.relationships') }}</span>
                  <div class="grid grid-cols-2 gap-3">
                    <div class="flex flex-col gap-1.5">
                      <Label class="text-xs">{{ t('components.createGroup.s5.parentGroup') }} <span class="text-muted-foreground font-normal">{{ t('components.createGroup.s5.inheritConfig') }}</span></Label>
                      <select v-model="parent" class="h-9 px-3 bg-glass rounded-lg border border-glass-border text-foreground text-sm focus:outline-none focus:border-primary/40">
                        <option value="">{{ t('components.createGroup.none') }}</option><option v-for="g in otherGroupNames" :key="g" :value="g">{{ g }}</option>
                      </select>
                    </div>
                    <div class="flex flex-col gap-1.5">
                      <Label class="text-xs">{{ t('components.createGroup.s5.dependsOn') }} <span class="text-muted-foreground font-normal">{{ t('components.createGroup.s5.startupOrder') }}</span></Label>
                      <Input v-model="dependsOn" :placeholder="t('components.createGroup.s5.dependsPlaceholder')" autocomplete="off" class="bg-glass border-glass-border font-mono text-xs" />
                    </div>
                  </div>
                  <div class="grid grid-cols-2 gap-3">
                    <div class="flex flex-col gap-1.5">
                      <Label class="text-xs">{{ t('components.createGroup.s5.fallbackGroup') }} <span class="text-muted-foreground font-normal">{{ t('components.createGroup.s5.overflow') }}</span></Label>
                      <select v-model="fallbackGroup" class="h-9 px-3 bg-glass rounded-lg border border-glass-border text-foreground text-sm focus:outline-none focus:border-primary/40">
                        <option value="">{{ t('components.createGroup.none') }}</option><option v-for="g in otherGroupNames" :key="g" :value="g">{{ g }}</option>
                      </select>
                    </div>
                    <div class="flex flex-col gap-1.5">
                      <Label class="text-xs">{{ t('components.createGroup.s5.startupWeight') }} <span class="text-muted-foreground font-normal">{{ t('components.createGroup.s5.higherFirst') }}</span></Label>
                      <Slider :model-value="[startupWeight]" :min="0" :max="100" :step="1" show-tooltip @update:model-value="startupWeight = $event![0]!" />
                    </div>
                  </div>
                  <!-- Bedrock proxy target — Geyser front-doors forward to a Java proxy group -->
                  <div v-if="isGeyser" class="flex flex-col gap-1.5">
                    <Label class="text-xs">{{ t('components.createGroup.s5.bedrockProxyGroup') }} <span class="text-muted-foreground font-normal">{{ t('components.createGroup.s5.bedrockProxyGroupHint') }}</span></Label>
                    <select v-model="bedrockProxyGroup" class="h-9 px-3 bg-glass rounded-lg border border-glass-border text-foreground text-sm focus:outline-none focus:border-primary/40">
                      <option value="">{{ t('components.createGroup.none') }}</option><option v-for="g in proxyGroupNames" :key="g" :value="g">{{ g }}</option>
                    </select>
                  </div>
                </div>

                <Separator class="bg-glass-border" />

                <!-- Operational toggles -->
                <div class="grid grid-cols-2 gap-3">
                  <!-- Default group — not available for proxy platforms -->
                  <div :class="['flex items-center justify-between p-3 rounded-xl border border-glass-border bg-glass/20', isProxy ? 'opacity-40 cursor-not-allowed' : '']">
                    <div><p class="text-xs font-medium text-foreground">{{ t('components.createGroup.s5.defaultGroup') }}</p><p class="text-[10px] text-muted-foreground/40">{{ isProxy ? t('components.createGroup.s5.notForProxies') : t('components.createGroup.s5.entryPoint') }}</p></div>
                    <Switch v-model="defaultGroup" :disabled="isProxy" />
                  </div>
                  <div class="flex items-center justify-between p-3 rounded-xl border border-glass-border bg-glass/20">
                    <div><p class="text-xs font-medium text-foreground">{{ t('components.createGroup.s5.maintenance') }}</p><p class="text-[10px] text-muted-foreground/40">{{ t('components.createGroup.s5.maintenanceHint') }}</p></div>
                    <Switch v-model="maintenance" />
                  </div>
                </div>

                <!-- Node placement — collapsed -->
                <button type="button" class="adv-toggle" @click="showPlacement = !showPlacement">
                  <div class="flex items-center gap-2.5"><Cpu class="size-3.5 text-muted-foreground" /><span class="text-xs font-medium text-foreground">{{ t('components.createGroup.s5.nodePlacement') }}</span><Badge variant="outline" class="text-[9px] text-muted-foreground border-glass-border">{{ t('components.createGroup.optionalBadge') }}</Badge></div>
                  <ChevronDown :class="['size-3.5 text-muted-foreground transition-transform duration-200', showPlacement ? 'rotate-180' : '']" />
                </button>
                <div v-if="showPlacement" class="adv-content">
                  <div class="grid grid-cols-2 gap-3">
                    <div class="flex flex-col gap-1.5"><Label class="text-xs">{{ t('components.createGroup.s5.nodeAffinity') }} <span class="text-muted-foreground font-normal">{{ t('components.createGroup.s5.require') }}</span></Label><Input v-model="nodeAffinity" :placeholder="connectedNodeNames.slice(0, 2).join(', ') || 'node-1, node-2'" autocomplete="off" class="bg-glass border-glass-border font-mono text-xs" /></div>
                    <div class="flex flex-col gap-1.5"><Label class="text-xs">{{ t('components.createGroup.s5.nodeAntiAffinity') }} <span class="text-muted-foreground font-normal">{{ t('components.createGroup.s5.exclude') }}</span></Label><Input v-model="nodeAntiAffinity" :placeholder="t('components.createGroup.s5.excludePlaceholder')" autocomplete="off" class="bg-glass border-glass-border font-mono text-xs" /></div>
                  </div>
                  <div class="grid grid-cols-2 gap-3">
                    <div class="flex flex-col gap-1.5"><Label class="text-xs">{{ t('components.createGroup.s5.spreadConstraint') }}</Label><Input v-model="spreadConstraint" :placeholder="t('components.createGroup.s5.spreadPlaceholder')" autocomplete="off" class="bg-glass border-glass-border font-mono text-xs" /></div>
                    <div class="flex flex-col gap-1.5"><Label class="text-xs">{{ t('components.createGroup.s5.priority') }} <span class="text-muted-foreground font-normal">{{ t('components.createGroup.s5.contention') }}</span></Label><Slider :model-value="[priority]" :min="0" :max="100" :step="1" show-tooltip @update:model-value="priority = $event![0]!" /></div>
                  </div>
                </div>
              </div>

              <!-- ═══════ STEP 6: Review ═══════ -->
              <div v-else-if="step === 6" key="s6" class="flex flex-col gap-3">
                <div
v-for="(card, ci) in reviewCards" :key="ci" class="rounded-xl border border-glass-border bg-glass/30 p-4">
                  <div class="flex items-center justify-between mb-2">
                    <div class="flex items-center gap-2"><component :is="card.icon" class="size-3.5 text-primary" /><span class="text-[10px] font-medium text-muted-foreground uppercase tracking-wider">{{ card.title }}</span></div>
                    <button type="button" class="text-[10px] text-primary hover:text-primary/80 transition-colors flex items-center gap-1" @click="goToStep(card.step)"><Pencil class="size-2.5" /> {{ t('components.createGroup.review.edit') }}</button>
                  </div>
                  <div class="grid grid-cols-2 gap-x-6 gap-y-1.5 text-xs">
                    <div v-for="([label, value], ri) in card.rows" :key="ri" :class="['flex justify-between', String(value).length > 30 ? 'col-span-2' : '']">
                      <span class="text-muted-foreground">{{ label }}</span>
                      <span class="font-mono text-foreground text-right truncate max-w-48">{{ value }}</span>
                    </div>
                  </div>
                </div>
              </div>

            </Transition>
          </div>

          <!-- Footer -->
          <div class="shrink-0 px-8 pb-5">
            <div class="flex items-center gap-2 pt-4 border-t border-glass-border">
              <Button v-if="step > 1" variant="outline" class="border-glass-border" @click="goBack"><ArrowLeft class="size-4 mr-1.5" /> {{ t('components.createGroup.back') }}</Button>
              <div class="flex-1" />
              <span class="text-[10px] text-muted-foreground/50 self-center tabular-nums">{{ step }} / {{ steps.length }}</span>
              <Button v-if="step < steps.length" class="bg-primary hover:bg-primary/90 text-primary-foreground" :disabled="!stepValid" @click="goNext">{{ t('components.createGroup.continue') }} <ArrowRight class="size-4 ml-1.5" /></Button>
              <Button v-else class="bg-primary hover:bg-primary/90 text-primary-foreground" :disabled="loading" @click="submit">
                <Loader2 v-if="loading" class="size-4 mr-1.5 animate-spin" /><Rocket v-else class="size-4 mr-1.5" />
                {{ loading ? t('components.createGroup.creating') : t('components.createGroup.createGroupBtn') }}
              </Button>
            </div>
          </div>
        </div>
      </div>
    </DialogContent>
  </Dialog>
</template>

<style scoped>
.slide-left-enter-active, .slide-left-leave-active, .slide-right-enter-active, .slide-right-leave-active { transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1); }
.slide-left-enter-from { opacity: 0; transform: translateX(24px); }
.slide-left-leave-to { opacity: 0; transform: translateX(-24px); }
.slide-right-enter-from { opacity: 0; transform: translateX(-24px); }
.slide-right-leave-to { opacity: 0; transform: translateX(24px); }
.adv-toggle { display: flex; align-items: center; justify-content: space-between; width: 100%; padding: 0.625rem 0.75rem; border-radius: 0.75rem; border: 1px solid var(--glass-border); background: color-mix(in srgb, var(--glass) 30%, transparent); transition: background-color 0.15s; cursor: pointer; }
.adv-toggle:hover { background: color-mix(in srgb, var(--glass-hover) 40%, transparent); }
.adv-content { display: flex; flex-direction: column; gap: 0.75rem; padding-left: 0.75rem; border-left: 2px solid color-mix(in srgb, var(--glass-border) 50%, transparent); }
.inline-number-input { font-family: theme('fontFamily.mono'); font-weight: 600; font-size: 11px; color: var(--foreground); background: transparent; border: none; border-bottom: 1.5px dashed transparent; border-radius: 0; outline: none; width: 3.2ch; padding: 0 1px; text-align: center; font-variant-numeric: tabular-nums; transition: border-color 0.15s, background-color 0.15s; }
.inline-number-input:hover { border-bottom-color: var(--primary); cursor: text; }
.inline-number-input:focus { border-bottom-color: var(--primary); background: var(--glass); border-radius: 2px; }
</style>
