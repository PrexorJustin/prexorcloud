<script setup lang="ts">
const ramps = [
  { name: "cyan",   label: "Cyan (primary)" },
  { name: "violet", label: "Violet (category)" },
  { name: "slate",  label: "Slate (dark neutral)" },
  { name: "sand",   label: "Sand (light neutral)" },
  { name: "green",  label: "Green (success)" },
  { name: "amber",  label: "Amber (warning)" },
  { name: "red",    label: "Red (destructive)" },
]

const semantic = [
  "background", "foreground",
  "primary", "secondary",
  "muted", "muted-foreground",
  "accent", "accent-foreground",
  "destructive", "success", "warning", "info",
  "border", "input", "ring",
  "glass", "glass-hover", "glass-border",
  "sidebar", "sidebar-foreground", "sidebar-primary", "sidebar-accent", "sidebar-border",
]
</script>

<template>
  <Story title="Tokens" group="tokens">
    <Variant title="Color ramps">
      <div v-for="ramp in ramps" :key="ramp.name" class="mb-4">
        <p class="eyebrow mb-2">{{ ramp.label }}</p>
        <div class="grid grid-cols-12 gap-1">
          <div
            v-for="step in 12"
            :key="step"
            class="h-12 rounded-md border border-glass-border flex items-end justify-center pb-1 text-[10px] mono"
            :style="{ background: `var(--${ramp.name}-${step})`, color: step >= 8 ? 'var(--slate-1)' : 'var(--slate-12)' }"
          >{{ step }}</div>
        </div>
      </div>
    </Variant>

    <Variant title="Semantic tokens">
      <div class="grid grid-cols-2 gap-2">
        <div
          v-for="name in semantic"
          :key="name"
          class="flex items-center gap-3 rounded-md border border-glass-border bg-glass/40 p-2"
        >
          <span class="size-8 rounded-md border border-glass-border" :style="{ background: `var(--${name})` }" />
          <span class="mono text-xs">{{ name }}</span>
        </div>
      </div>
    </Variant>

    <Variant title="Typography">
      <div class="space-y-3">
        <p class="eyebrow">Eyebrow / 12px / tracking-widest</p>
        <h1 class="text-gradient-title text-2xl font-bold">Page title — 24px gradient</h1>
        <h2 class="text-xl font-semibold">Card title — 20px semibold</h2>
        <p class="text-base">Body — 16px Inter, line-height 1.5.</p>
        <p class="text-sm text-muted-foreground">Body small — 14px muted.</p>
        <p class="mono text-sm tabular">JetBrains Mono — 14px tabular 0123456789</p>
      </div>
    </Variant>

    <Variant title="Spacing">
      <div class="space-y-2">
        <div v-for="n in [1,2,3,4,5,6,8,10,12,16,24]" :key="n" class="flex items-center gap-3">
          <span class="mono text-xs w-12 text-muted-foreground">--space-{{ n }}</span>
          <span class="h-3 rounded bg-primary/40 border border-primary/60" :style="{ width: `var(--space-${n})` }" />
        </div>
      </div>
    </Variant>

    <Variant title="Radius">
      <div class="grid grid-cols-3 gap-3">
        <div v-for="r in ['xs','sm','md','lg','xl','2xl','full']" :key="r" class="flex flex-col items-center gap-2">
          <div class="size-16 bg-primary/20 border border-primary/50" :style="{ borderRadius: `var(--radius-${r})` }" />
          <span class="mono text-xs text-muted-foreground">--radius-{{ r }}</span>
        </div>
      </div>
    </Variant>

    <Variant title="Shadows">
      <div class="grid grid-cols-2 gap-6 p-4">
        <div v-for="s in ['sm','md','lg','xl']" :key="s" class="flex flex-col items-center gap-2">
          <div class="size-20 rounded-xl bg-glass border border-glass-border" :style="{ boxShadow: `var(--shadow-${s})` }" />
          <span class="mono text-xs text-muted-foreground">--shadow-{{ s }}</span>
        </div>
        <div v-for="g in ['primary','success','warning','destructive']" :key="g" class="flex flex-col items-center gap-2">
          <div class="size-20 rounded-xl bg-glass border border-glass-border" :style="{ boxShadow: `var(--shadow-glow-${g})` }" />
          <span class="mono text-xs text-muted-foreground">--shadow-glow-{{ g }}</span>
        </div>
      </div>
    </Variant>
  </Story>
</template>
