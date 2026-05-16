<script setup lang="ts">
import type { CheckboxRootEmits, CheckboxRootProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
import { Check, Minus } from "lucide-vue-next"
import { CheckboxIndicator, CheckboxRoot, useForwardPropsEmits } from "reka-ui"
import { cn } from "@/lib/utils"

const props = defineProps<CheckboxRootProps & { class?: HTMLAttributes["class"] }>()
const emits = defineEmits<CheckboxRootEmits>()

const delegatedProps = reactiveOmit(props, "class")
const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <CheckboxRoot
    v-bind="forwarded"
    :class="cn(
      // Resting: visible but quiet glass-border on a translucent fill.
      'inline-grid size-4 shrink-0 place-content-center rounded-md border-2 border-glass-border-hover bg-glass/40 transition-all',
      // Hover: border tightens to primary, fill brightens.
      'hover:border-primary hover:bg-glass-hover',
      // Focus ring uses the design-system token.
      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:border-primary',
      // Disabled.
      'disabled:cursor-not-allowed disabled:opacity-50',
      // Checked + indeterminate: solid primary, faint cyan halo.
      'data-[state=checked]:border-primary data-[state=checked]:bg-primary data-[state=checked]:text-primary-foreground data-[state=checked]:shadow-[0_0_0_3px_color-mix(in_srgb,var(--primary)_18%,transparent)]',
      'data-[state=indeterminate]:border-primary data-[state=indeterminate]:bg-primary data-[state=indeterminate]:text-primary-foreground',
      props.class,
    )"
  >
    <CheckboxIndicator class="grid size-full place-content-center text-current">
      <slot>
        <Minus v-if="forwarded.modelValue === 'indeterminate'" class="size-3" :stroke-width="3.5" />
        <Check v-else class="size-3" :stroke-width="3.5" />
      </slot>
    </CheckboxIndicator>
  </CheckboxRoot>
</template>
