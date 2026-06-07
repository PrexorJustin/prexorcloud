<script setup lang="ts">
import type { SliderRootEmits, SliderRootProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
import { SliderRange, SliderRoot, SliderThumb, SliderTrack, useForwardPropsEmits } from "reka-ui"
import { cn } from "@/lib/utils"

const props = defineProps<
  SliderRootProps & {
    class?: HTMLAttributes["class"]
    /** Show a floating tooltip above the thumb with the current value */
    showTooltip?: boolean
    /** Format the tooltip value (e.g. v => `${v}%`). Defaults to String(v) */
    formatTooltip?: (value: number) => string
  }
>()
const emits = defineEmits<SliderRootEmits>()

const delegatedProps = reactiveOmit(props, "class", "showTooltip", "formatTooltip")
const forwarded = useForwardPropsEmits(delegatedProps, emits)

function formatValue(index: number): string {
  const val = props.modelValue?.[index] ?? 0
  return props.formatTooltip ? props.formatTooltip(val) : String(val)
}
</script>

<template>
  <SliderRoot
    :class="cn(
      'slider-root relative flex w-full touch-none select-none items-center group/slider',
      showTooltip ? 'pt-7' : 'py-1.5',
      props.class,
    )"
    v-bind="forwarded"
  >
    <!-- Track -->
    <SliderTrack class="slider-track relative h-[6px] w-full grow rounded-full">
      <!-- Filled range with gradient -->
      <SliderRange class="slider-range absolute h-full rounded-full" />
    </SliderTrack>

    <!-- Thumb(s) -->
    <SliderThumb
      v-for="(_, key) in modelValue"
      :key="key"
      class="slider-thumb group/thumb relative flex items-center justify-center outline-none disabled:pointer-events-none disabled:opacity-50"
    >
      <!-- Floating tooltip -->
      <span
        v-if="showTooltip"
        class="slider-tooltip absolute bottom-full mb-2.5 pointer-events-none select-none"
      >
        <span class="slider-tooltip-pill relative block px-2.5 py-1 rounded-lg text-[11px] font-mono font-semibold leading-tight whitespace-nowrap">
          {{ formatValue(key) }}
          <span class="slider-tooltip-arrow absolute top-full left-1/2 -translate-x-1/2" />
        </span>
      </span>

      <!-- Neon halo (behind thumb) -->
      <span class="slider-thumb-halo" />
      <!-- Thumb disc -->
      <span class="slider-thumb-disc" />
    </SliderThumb>
  </SliderRoot>
</template>

<style scoped>
/* ── Track — recessed glass channel ────────── */
.slider-track {
  background: linear-gradient(
    180deg,
    color-mix(in srgb, var(--glass-border) 50%, transparent) 0%,
    color-mix(in srgb, var(--glass-border) 25%, transparent) 100%
  );
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.25);
}

/* ── Range — gradient fill with neon glow ──── */
.slider-range {
  background: linear-gradient(
    90deg,
    var(--primary) 0%,
    color-mix(in srgb, var(--primary) 75%, #a78bfa) 100%
  );
  box-shadow:
    0 0 6px var(--primary-glow),
    0 0 2px var(--primary-glow);
  transition: box-shadow 0.25s ease;
}

/* Glow intensifies on interaction */
.slider-root:hover .slider-range {
  box-shadow:
    0 0 10px var(--primary-glow),
    0 0 3px var(--primary-glow);
}

.slider-root:has([data-state="active"]) .slider-range {
  box-shadow:
    0 0 16px var(--primary-glow),
    0 0 4px var(--primary-glow),
    inset 0 0 4px rgba(255, 255, 255, 0.15);
}

/* ── Thumb halo — neon ring behind disc ────── */
.slider-thumb-halo {
  position: absolute;
  width: 28px;
  height: 28px;
  border-radius: 9999px;
  background: transparent;
  box-shadow: 0 0 0 0 transparent;
  transition: all 0.25s cubic-bezier(0.2, 0, 0, 1);
  pointer-events: none;
}

.group\/thumb:hover .slider-thumb-halo {
  box-shadow: 0 0 12px var(--primary-glow);
  background: color-mix(in srgb, var(--primary) 8%, transparent);
}

.group\/thumb[data-state="active"] .slider-thumb-halo {
  box-shadow: 0 0 20px var(--primary-glow), 0 0 40px color-mix(in srgb, var(--primary) 15%, transparent);
  background: color-mix(in srgb, var(--primary) 12%, transparent);
}

/* ── Thumb disc ────────────────────────────── */
.slider-thumb-disc {
  position: relative;
  display: block;
  width: 18px;
  height: 18px;
  border-radius: 9999px;
  background: radial-gradient(circle at 40% 35%, #ffffff 0%, #f0f0f0 60%, #e0e0e0 100%);
  border: 2.5px solid var(--primary);
  box-shadow:
    0 2px 6px rgba(0, 0, 0, 0.25),
    inset 0 1px 1px rgba(255, 255, 255, 0.8),
    inset 0 -1px 1px rgba(0, 0, 0, 0.05);
  transition: all 0.2s cubic-bezier(0.2, 0, 0, 1);
  cursor: grab;
}

.slider-thumb-disc:active,
.group\/thumb[data-state="active"] .slider-thumb-disc {
  cursor: grabbing;
}

.group\/thumb:hover .slider-thumb-disc {
  transform: scale(1.15);
  box-shadow:
    0 0 0 3px var(--primary-glow),
    0 2px 8px rgba(0, 0, 0, 0.3),
    inset 0 1px 1px rgba(255, 255, 255, 0.8),
    inset 0 -1px 1px rgba(0, 0, 0, 0.05);
}

.group\/thumb[data-state="active"] .slider-thumb-disc {
  transform: scale(1.2);
  border-color: var(--primary);
  box-shadow:
    0 0 0 4px var(--primary-glow),
    0 0 12px var(--primary-glow),
    0 2px 8px rgba(0, 0, 0, 0.3),
    inset 0 1px 1px rgba(255, 255, 255, 0.8);
}

.group\/thumb:focus-visible .slider-thumb-disc {
  outline: 2px solid var(--ring);
  outline-offset: 3px;
}

/* ── Tooltip ───────────────────────────────── */
.slider-tooltip {
  opacity: 0;
  transform: translateY(4px) scale(0.92);
  transition: all 0.2s cubic-bezier(0.2, 0, 0, 1);
}

.slider-root:hover .slider-tooltip,
.group\/thumb[data-state="active"] .slider-tooltip {
  opacity: 1;
  transform: translateY(0) scale(1);
}

.slider-tooltip-pill {
  color: #fff;
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--primary) 90%, #1e293b) 0%,
    color-mix(in srgb, var(--primary) 70%, #0f172a) 100%
  );
  box-shadow:
    0 4px 14px color-mix(in srgb, var(--primary) 35%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, 0.1);
  border: 1px solid color-mix(in srgb, var(--primary) 40%, transparent);
}

.slider-tooltip-arrow {
  display: block;
  width: 0;
  height: 0;
  border-left: 5px solid transparent;
  border-right: 5px solid transparent;
  border-top: 5px solid color-mix(in srgb, var(--primary) 70%, #0f172a);
}
</style>
