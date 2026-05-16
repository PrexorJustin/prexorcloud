<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { ref } from "vue"
import { Check, Copy } from "lucide-vue-next"
import { cn } from "@/lib/utils"

const props = withDefaults(defineProps<{
  copy?: string
  title?: string
  trafficLights?: boolean
  class?: HTMLAttributes["class"]
}>(), {
  trafficLights: true,
})

const copied = ref(false)

async function onCopy() {
  if (!props.copy) return
  await navigator.clipboard.writeText(props.copy)
  copied.value = true
  setTimeout(() => { copied.value = false }, 1200)
}
</script>

<template>
  <div :class="cn('overflow-hidden rounded-xl border border-glass-border bg-slate-950/60 backdrop-blur-sm', props.class)">
    <div class="flex items-center gap-2 border-b border-glass-border px-3 py-2">
      <div v-if="props.trafficLights" class="flex items-center gap-1.5">
        <span class="size-2.5 rounded-full opacity-60" aria-hidden="true" style="background: var(--red-9)" />
        <span class="size-2.5 rounded-full opacity-60" aria-hidden="true" style="background: var(--amber-9)" />
        <span class="size-2.5 rounded-full opacity-60" aria-hidden="true" style="background: var(--green-9)" />
      </div>
      <span v-if="props.title" class="text-xs text-muted-foreground mono">{{ props.title }}</span>
      <button
        v-if="props.copy"
        type="button"
        class="ml-auto inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-glass-hover hover:text-foreground"
        :aria-label="copied ? 'Copied' : 'Copy to clipboard'"
        @click="onCopy"
      >
        <Check v-if="copied" class="size-3.5 text-success" />
        <Copy v-else class="size-3.5" />
        {{ copied ? "Copied" : "Copy" }}
      </button>
    </div>
    <pre class="mono overflow-x-auto px-4 py-3 text-sm leading-relaxed text-slate-100"><slot /></pre>
  </div>
</template>
