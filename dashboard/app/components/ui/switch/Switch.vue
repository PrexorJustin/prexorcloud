<script setup lang="ts">
import type { SwitchRootEmits, SwitchRootProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
import {
  SwitchRoot,
  SwitchThumb,
  useForwardPropsEmits,
} from "reka-ui"
import { cn } from "@/lib/utils"

const props = defineProps<SwitchRootProps & { class?: HTMLAttributes["class"] }>()

const emits = defineEmits<SwitchRootEmits>()

const delegatedProps = reactiveOmit(props, "class")

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <SwitchRoot
    v-bind="forwarded"
    :class="cn(
      'switch-root peer inline-flex h-6 w-11 shrink-0 cursor-pointer items-center rounded-full transition-all duration-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:cursor-not-allowed disabled:opacity-50',
      props.class,
    )"
  >
    <SwitchThumb
      :class="cn('switch-thumb pointer-events-none block h-5 w-5 rounded-full ring-0 transition-all duration-300 data-[state=checked]:translate-x-5')"
    >
      <slot name="thumb" />
    </SwitchThumb>
  </SwitchRoot>
</template>

<style scoped>
/* ── Track ──────────────────────────────────── */
.switch-root {
  background: color-mix(in srgb, var(--glass-border) 80%, transparent);
  border: 1.5px solid color-mix(in srgb, var(--glass-border) 60%, transparent);
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.2);
}

/* Checked: neon glow track */
.switch-root[data-state="checked"] {
  background: linear-gradient(
    135deg,
    var(--primary) 0%,
    color-mix(in srgb, var(--primary) 80%, #a78bfa) 100%
  );
  border-color: color-mix(in srgb, var(--primary) 60%, transparent);
  box-shadow:
    0 0 12px var(--primary-glow),
    0 0 4px var(--primary-glow),
    inset 0 1px 1px rgba(255, 255, 255, 0.15);
}

.switch-root:hover[data-state="checked"] {
  box-shadow:
    0 0 18px var(--primary-glow),
    0 0 6px var(--primary-glow),
    inset 0 1px 1px rgba(255, 255, 255, 0.15);
}

.switch-root:hover[data-state="unchecked"] {
  border-color: color-mix(in srgb, var(--glass-border) 90%, transparent);
  background: color-mix(in srgb, var(--glass-border) 100%, transparent);
}

/* ── Thumb ──────────────────────────────────── */
.switch-thumb {
  background: radial-gradient(circle at 40% 35%, #ffffff 0%, #f0f0f0 60%, #e0e0e0 100%);
  box-shadow:
    0 1px 4px rgba(0, 0, 0, 0.2),
    inset 0 1px 1px rgba(255, 255, 255, 0.8);
}

/* Checked thumb: add glow */
.switch-thumb[data-state="checked"] {
  box-shadow:
    0 0 8px var(--primary-glow),
    0 1px 4px rgba(0, 0, 0, 0.2),
    inset 0 1px 1px rgba(255, 255, 255, 0.8);
}
</style>
