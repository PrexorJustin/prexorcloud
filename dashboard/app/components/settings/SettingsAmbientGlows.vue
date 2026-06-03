<script setup lang="ts">
import type { Component } from "vue"
import { Plus, Trash2, GripVertical, RotateCcw, Move, Droplets, Eye, RotateCw, Maximize2, Zap, Expand, X } from "lucide-vue-next"
import { Switch } from "~/components/ui/switch"
import { Button } from "~/components/ui/button"
import type { GlowBlob } from "~/lib/theme-data"

const { t } = useI18n()
const appearance = useAppearanceStore()

const glowColorOptions = computed(() => [
  { key: "primary", label: t('components.glows.colors.primary') },
  { key: "secondary", label: t('components.glows.colors.secondary') },
  { key: "success", label: t('components.glows.colors.success') },
  { key: "warning", label: t('components.glows.colors.warning') },
  { key: "destructive", label: t('components.glows.colors.danger') },
])

function blobCssColor(color: string) {
  return color.startsWith("#") ? color : `var(--${color})`
}

function blobMixPercent(blob: { opacity: number; intensity?: number }) {
  const raw = blob.opacity * 3.3 * ((blob.intensity ?? 100) / 100) * (appearance.glowIntensity / 100)
  return Math.min(100, Math.max(0, raw))
}

const expandedBlob = ref<number | null>(null)
const previewExpanded = ref(false)

function toggleBlobCollapse(index: number) {
  expandedBlob.value = expandedBlob.value === index ? null : index
}

function updateBlobPosition(e: PointerEvent, index: number) {
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  appearance.updateGlowBlob(index, {
    x: Math.round(Math.max(0, Math.min(100, ((e.clientX - rect.left) / rect.width) * 100))),
    y: Math.round(Math.max(0, Math.min(100, ((e.clientY - rect.top) / rect.height) * 100))),
  })
}

function onPositionDown(e: PointerEvent, index: number) {
  updateBlobPosition(e, index);
  (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}

function onPositionMove(e: PointerEvent, index: number) {
  if (!(e.currentTarget as HTMLElement).hasPointerCapture(e.pointerId)) return
  updateBlobPosition(e, index)
}

const sliders = computed<ReadonlyArray<{ key: keyof GlowBlob; label: string; icon: Component; min: number; max: number; step: number; unit: string; fallback?: number }>>(() => [
  { key: "size", label: t('components.glows.sliders.size'), icon: Maximize2, min: 100, max: 1500, step: 10, unit: "px" },
  { key: "blur", label: t('components.glows.sliders.blur'), icon: Droplets, min: 20, max: 300, step: 5, unit: "px" },
  { key: "opacity", label: t('components.glows.sliders.opacity'), icon: Eye, min: 1, max: 30, step: 1, unit: "%" },
  { key: "intensity", label: t('components.glows.sliders.intensity'), icon: Zap, min: 0, max: 300, step: 5, unit: "%", fallback: 100 },
  { key: "rotate", label: t('components.glows.sliders.rotate'), icon: RotateCw, min: 0, max: 360, step: 1, unit: "°" },
  { key: "scaleX", label: t('components.glows.sliders.scaleX'), icon: Maximize2, min: 30, max: 300, step: 5, unit: "%" },
  { key: "scaleY", label: t('components.glows.sliders.scaleY'), icon: Maximize2, min: 30, max: 300, step: 5, unit: "%" },
])
</script>

<template>
  <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
    <!-- Header -->
    <div class="flex items-center justify-between mb-1">
      <h3 class="text-base font-semibold text-foreground">{{ t('components.glows.title') }}</h3>
      <Switch
        :model-value="appearance.glowEnabled"
        @update:model-value="appearance.setGlowEnabled"
      />
    </div>
    <p class="text-sm text-muted-foreground mb-5">{{ t('components.glows.subtitle') }}</p>

    <template v-if="appearance.glowEnabled">
      <!-- Blob list -->
      <div class="flex flex-col gap-2 mb-4">
        <div
          v-for="(blob, i) in appearance.glowBlobs"
          :key="i"
          class="rounded-xl border transition-all overflow-hidden"
          :class="expandedBlob === i ? 'border-primary/30 bg-primary/5' : 'border-glass-border hover:border-glass-border/80'"
        >
          <!-- Blob header — mini visual summary -->
          <div
            class="flex items-center gap-3 p-3 cursor-pointer select-none group"
            @click="toggleBlobCollapse(i)"
          >
            <GripVertical class="size-3.5 text-muted-foreground/40 shrink-0" />

            <!-- Color dot with glow preview -->
            <div class="relative">
              <div
                class="size-6 rounded-full ring-2 ring-offset-1 ring-offset-background transition-all"
                :class="expandedBlob === i ? 'ring-primary' : 'ring-transparent group-hover:ring-glass-border'"
                :style="{ backgroundColor: blobCssColor(blob.color) }"
              />
              <div
                class="absolute inset-0 rounded-full blur-md opacity-50"
                :style="{ backgroundColor: blobCssColor(blob.color) }"
              />
            </div>

            <!-- Info -->
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2">
                <span class="text-xs font-semibold text-foreground">{{ t('components.glows.blob', { n: i + 1 }) }}</span>
                <span class="text-[10px] text-muted-foreground/60 font-mono">{{ blob.x }},{{ blob.y }}</span>
              </div>
              <!-- Mini property pills -->
              <div class="flex gap-1.5 mt-0.5">
                <span class="text-[9px] text-muted-foreground/50">{{ blob.size }}px</span>
                <span class="text-[9px] text-muted-foreground/30">/</span>
                <span class="text-[9px] text-muted-foreground/50">blur {{ blob.blur }}</span>
                <span class="text-[9px] text-muted-foreground/30">/</span>
                <span class="text-[9px] text-muted-foreground/50">{{ blob.opacity }}% op</span>
              </div>
            </div>

            <!-- Delete -->
            <button
              v-if="appearance.glowBlobs.length > 1"
              class="p-1.5 rounded-lg text-muted-foreground/40 hover:text-destructive hover:bg-destructive/10 transition-all opacity-0 group-hover:opacity-100"
              @click.stop="appearance.removeGlowBlob(i)"
            >
              <Trash2 class="size-3.5" />
            </button>
          </div>

          <!-- Expanded editor -->
          <div v-show="expandedBlob === i" class="px-4 pb-4 pt-1">
            <div class="grid grid-cols-[180px_1fr] gap-5">
              <!-- Left: Position pad + Color -->
              <div class="flex flex-col gap-3">
                <!-- Position pad -->
                <div>
                  <div class="flex items-center gap-1.5 text-[10px] text-muted-foreground mb-1.5">
                    <Move class="size-3" />
                    <span class="uppercase tracking-wider font-medium">{{ t('components.glows.position') }}</span>
                  </div>
                  <div
                    class="relative w-full aspect-[4/3] rounded-lg bg-background/50 border border-glass-border cursor-crosshair overflow-hidden"
                    @pointerdown.prevent="onPositionDown($event, i)"
                    @pointermove.prevent="onPositionMove($event, i)"
                  >
                    <!-- Grid lines -->
                    <div class="absolute inset-0 pointer-events-none">
                      <div class="absolute top-1/4 left-0 right-0 h-px bg-foreground/[0.03]" />
                      <div class="absolute top-1/2 left-0 right-0 h-px bg-foreground/[0.06]" />
                      <div class="absolute top-3/4 left-0 right-0 h-px bg-foreground/[0.03]" />
                      <div class="absolute left-1/4 top-0 bottom-0 w-px bg-foreground/[0.03]" />
                      <div class="absolute left-1/2 top-0 bottom-0 w-px bg-foreground/[0.06]" />
                      <div class="absolute left-3/4 top-0 bottom-0 w-px bg-foreground/[0.03]" />
                    </div>
                    <!-- Blob preview shadow -->
                    <div
                      class="absolute rounded-full pointer-events-none blur-xl opacity-30"
                      :style="{
                        left: `${blob.x}%`, top: `${blob.y}%`,
                        width: '40%', height: '40%',
                        transform: 'translate(-50%, -50%)',
                        backgroundColor: blobCssColor(blob.color),
                      }"
                    />
                    <!-- Dot -->
                    <div
                      class="absolute size-3 rounded-full border-2 border-white shadow-lg -translate-x-1/2 -translate-y-1/2 pointer-events-none transition-[left,top] duration-75"
                      :style="{ left: `${blob.x}%`, top: `${blob.y}%`, backgroundColor: blobCssColor(blob.color) }"
                    />
                    <!-- Coordinates -->
                    <div class="absolute bottom-1 right-1.5 text-[9px] font-mono text-muted-foreground/40 pointer-events-none">
                      {{ blob.x }}, {{ blob.y }}
                    </div>
                  </div>
                </div>

                <!-- Color palette -->
                <div>
                  <div class="text-[10px] text-muted-foreground uppercase tracking-wider font-medium mb-1.5">{{ t('components.glows.color') }}</div>
                  <div class="grid grid-cols-3 gap-1">
                    <button
                      v-for="c in glowColorOptions"
                      :key="c.key"
                      class="flex items-center gap-1.5 px-2 py-1 rounded-lg text-[10px] transition-all"
                      :class="blob.color === c.key
                        ? 'bg-primary/15 text-foreground ring-1 ring-primary/30'
                        : 'text-muted-foreground hover:text-foreground hover:bg-glass'"
                      @click="appearance.updateGlowBlob(i, { color: c.key })"
                    >
                      <span class="size-2.5 rounded-full shrink-0" :style="{ backgroundColor: `var(--${c.key})` }" />
                      {{ c.label }}
                    </button>
                    <!-- Custom color -->
                    <label
                      class="flex items-center gap-1.5 px-2 py-1 rounded-lg text-[10px] cursor-pointer transition-all"
                      :class="blob.color.startsWith('#')
                        ? 'bg-primary/15 text-foreground ring-1 ring-primary/30'
                        : 'text-muted-foreground hover:text-foreground hover:bg-glass'"
                    >
                      <span
                        class="size-2.5 rounded-full shrink-0 border border-white/10"
                        :style="{ backgroundColor: blob.color.startsWith('#') ? blob.color : '#6366f1' }"
                      />
                      {{ t('components.glows.custom') }}
                      <input
                        type="color"
                        :value="blob.color.startsWith('#') ? blob.color : '#6366f1'"
                        class="sr-only"
                        @input="appearance.updateGlowBlob(i, { color: ($event.target as HTMLInputElement).value })"
                      >
                    </label>
                  </div>
                </div>
              </div>

              <!-- Right: Sliders -->
              <div class="grid grid-cols-2 gap-x-5 gap-y-2 content-start">
                <div v-for="s in sliders" :key="s.key">
                  <div class="flex items-center justify-between mb-0.5">
                    <div class="flex items-center gap-1.5 text-[10px] text-muted-foreground">
                      <component :is="s.icon" class="size-3 opacity-50" />
                      {{ s.label }}
                    </div>
                    <span class="text-[10px] font-mono text-muted-foreground/60 tabular-nums">
                      {{ blob[s.key] ?? s.fallback ?? 0 }}{{ s.unit }}
                    </span>
                  </div>
                  <input
                    type="range"
                    :value="blob[s.key] ?? s.fallback ?? 0"
                    :min="s.min"
                    :max="s.max"
                    :step="s.step"
                    class="w-full accent-primary h-1.5"
                    @input="appearance.updateGlowBlob(i, { [s.key]: Number(($event.target as HTMLInputElement).value) })"
                  >
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Live preview (compact) -->
      <div
        class="relative h-24 rounded-xl bg-background border border-glass-border overflow-hidden mb-4 cursor-pointer group/preview"
        @click="previewExpanded = true"
      >
        <!-- Blobs -->
        <div
          v-for="(blob, j) in appearance.glowBlobs"
          :key="j"
          class="absolute rounded-full transition-all duration-300"
          :style="{
            left: `${blob.x}%`,
            top: `${blob.y}%`,
            transform: `translate(-50%, -50%) rotate(${blob.rotate}deg) scaleX(${blob.scaleX / 100}) scaleY(${blob.scaleY / 100})`,
            width: `${blob.size / 8}px`,
            height: `${blob.size / 8}px`,
            filter: `blur(${blob.blur / 8}px)`,
            backgroundColor: `color-mix(in srgb, ${blobCssColor(blob.color)} ${blobMixPercent(blob)}%, transparent)`,
          }"
        />
        <!-- Mini layout wireframe -->
        <div class="absolute inset-0 pointer-events-none flex text-[0]">
          <div class="w-[15%] h-full border-r border-foreground/[0.04] flex flex-col p-1.5 gap-1">
            <div class="h-1 rounded-full w-3/4 bg-foreground/[0.04]" />
            <div class="h-0.5 rounded-full w-full bg-foreground/[0.03]" />
            <div class="h-0.5 rounded-full w-4/5 bg-foreground/[0.03]" />
          </div>
          <div class="flex-1 flex flex-col">
            <div class="h-4 border-b border-foreground/[0.04]" />
            <div class="flex-1 p-1.5 flex flex-col gap-1">
              <div class="grid grid-cols-3 gap-1">
                <div class="h-3 rounded-sm border border-foreground/[0.03]" />
                <div class="h-3 rounded-sm border border-foreground/[0.03]" />
                <div class="h-3 rounded-sm border border-foreground/[0.03]" />
              </div>
            </div>
          </div>
        </div>
        <!-- Expand hint -->
        <div class="absolute inset-0 flex items-center justify-center bg-background/0 group-hover/preview:bg-background/40 transition-colors">
          <div class="opacity-0 group-hover/preview:opacity-100 transition-opacity flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-glass/80 backdrop-blur border border-glass-border text-[10px] text-muted-foreground">
            <Expand class="size-3" />
            {{ t('components.glows.expandPreview') }}
          </div>
        </div>
      </div>

      <!-- Expanded preview (floating overlay) -->
      <Teleport to="body">
        <Transition
          enter-active-class="transition-all duration-200 ease-out"
          leave-active-class="transition-all duration-150 ease-in"
          enter-from-class="opacity-0 scale-95"
          enter-to-class="opacity-100 scale-100"
          leave-from-class="opacity-100 scale-100"
          leave-to-class="opacity-0 scale-95"
        >
          <div v-if="previewExpanded" class="fixed inset-0 z-50 flex items-center justify-center p-8" @click.self="previewExpanded = false">
            <!-- Backdrop -->
            <div class="absolute inset-0 bg-background/80 backdrop-blur-sm" @click="previewExpanded = false" />
            <!-- Preview card -->
            <div class="relative w-full max-w-2xl">
              <!-- Close -->
              <button
                class="absolute -top-10 right-0 z-10 p-1.5 rounded-lg bg-glass/80 border border-glass-border text-muted-foreground hover:text-foreground transition-colors"
                @click="previewExpanded = false"
              >
                <X class="size-4" />
              </button>
              <!-- Dashboard mockup with glows -->
              <div class="relative aspect-video rounded-2xl border border-glass-border bg-background shadow-2xl overflow-hidden">
                <!-- Glow blobs -->
                <div
                  v-for="(blob, j) in appearance.glowBlobs"
                  :key="j"
                  class="absolute rounded-full transition-all duration-300"
                  :style="{
                    left: `${blob.x}%`,
                    top: `${blob.y}%`,
                    transform: `translate(-50%, -50%) rotate(${blob.rotate}deg) scaleX(${blob.scaleX / 100}) scaleY(${blob.scaleY / 100})`,
                    width: `${blob.size / 4}px`,
                    height: `${blob.size / 4}px`,
                    filter: `blur(${blob.blur / 4}px)`,
                    backgroundColor: `color-mix(in srgb, ${blobCssColor(blob.color)} ${blobMixPercent(blob)}%, transparent)`,
                  }"
                />
                <!-- Dashboard layout overlay -->
                <div class="absolute inset-0 flex text-[0]">
                  <!-- Sidebar -->
                  <div class="w-[18%] h-full bg-sidebar/60 backdrop-blur border-r border-glass-border flex flex-col p-3 gap-2">
                    <div class="h-3 rounded-sm w-3/4 mb-1 bg-sidebar-accent/60" />
                    <div class="h-2 rounded-full w-full bg-sidebar-accent/40" />
                    <div class="h-2 rounded-full w-4/5 bg-sidebar-accent/40" />
                    <div class="h-2 rounded-full w-full bg-sidebar-accent/30" />
                    <div class="h-2 rounded-full w-3/5 bg-sidebar-accent/30" />
                    <div class="flex-1" />
                    <div class="h-2 rounded-full w-2/3 bg-sidebar-accent/20" />
                  </div>
                  <!-- Main -->
                  <div class="flex-1 flex flex-col">
                    <!-- Header -->
                    <div class="h-8 border-b border-glass-border bg-sidebar/30 backdrop-blur flex items-center px-3 gap-2">
                      <div class="h-1.5 rounded-full w-16 bg-muted-foreground/20" />
                      <div class="flex-1" />
                      <div class="size-4 rounded-full bg-muted-foreground/10" />
                      <div class="size-4 rounded-full bg-muted-foreground/10" />
                    </div>
                    <!-- Content -->
                    <div class="flex-1 p-4 flex flex-col gap-3">
                      <!-- Stat cards -->
                      <div class="grid grid-cols-3 gap-3">
                        <div class="h-12 rounded-lg bg-glass/40 backdrop-blur border border-glass-border p-2 flex flex-col justify-between">
                          <div class="h-1 rounded-full w-1/2 bg-muted-foreground/20" />
                          <div class="h-2 rounded-full w-3/4" style="background-color: var(--primary); opacity: 0.5" />
                        </div>
                        <div class="h-12 rounded-lg bg-glass/40 backdrop-blur border border-glass-border p-2 flex flex-col justify-between">
                          <div class="h-1 rounded-full w-2/3 bg-muted-foreground/20" />
                          <div class="h-2 rounded-full w-1/2 bg-muted-foreground/10" />
                        </div>
                        <div class="h-12 rounded-lg bg-glass/40 backdrop-blur border border-glass-border p-2 flex flex-col justify-between">
                          <div class="h-1 rounded-full w-1/3 bg-muted-foreground/20" />
                          <div class="h-2 rounded-full w-2/3 bg-muted-foreground/10" />
                        </div>
                      </div>
                      <!-- Main card -->
                      <div class="flex-1 rounded-lg bg-glass/40 backdrop-blur border border-glass-border p-3 flex flex-col">
                        <div class="h-1.5 rounded-full w-24 bg-muted-foreground/20 mb-2" />
                        <div class="flex-1 flex items-end gap-1.5 px-2">
                          <div v-for="h in [40, 65, 50, 80, 60, 75, 45, 70, 55, 85, 65, 50]" :key="h" class="flex-1 rounded-t-sm" :style="{ height: `${h}%`, backgroundColor: 'var(--primary)', opacity: 0.3 }" />
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </Transition>
      </Teleport>

      <!-- Actions -->
      <div class="flex items-center justify-between">
        <Button
          variant="outline"
          size="sm"
          class="text-xs border-glass-border text-muted-foreground hover:text-foreground"
          @click="appearance.addGlowBlob(); expandedBlob = appearance.glowBlobs.length - 1"
        >
          <Plus class="size-3 mr-1.5" />
          {{ t('components.glows.addBlob') }}
        </Button>
        <Button variant="ghost" size="sm" class="text-xs text-muted-foreground" @click="appearance.resetGlows(); expandedBlob = null">
          <RotateCcw class="size-3 mr-1.5" />
          {{ t('components.glows.reset') }}
        </Button>
      </div>
    </template>
  </div>
</template>
