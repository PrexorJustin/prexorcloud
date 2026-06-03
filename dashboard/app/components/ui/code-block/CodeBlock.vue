<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { computed, ref } from "vue"
import { Check, Copy } from "lucide-vue-next"
import { cn } from "@/lib/utils"

const props = withDefaults(defineProps<{
  code: string
  language?: string
  showLineNumbers?: boolean
  class?: HTMLAttributes["class"]
}>(), {
  showLineNumbers: true,
})

const lines = computed(() => props.code.split("\n"))
const copied = ref(false)

async function onCopy() {
  await navigator.clipboard.writeText(props.code)
  copied.value = true
  setTimeout(() => { copied.value = false }, 1200)
}
const { t } = useI18n()
</script>

<template>
  <div :class="cn('overflow-hidden rounded-xl border border-glass-border bg-glass/60 backdrop-blur-sm', props.class)">
    <div class="flex items-center justify-between border-b border-glass-border px-3 py-2">
      <span v-if="props.language" class="eyebrow text-[10px]">{{ props.language }}</span>
      <span v-else />
      <button
        type="button"
        class="inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs text-muted-foreground hover:bg-glass-hover hover:text-foreground"
        ::aria-label="copied ? t('components.copyBtn.copied') : t('components.copyBtn.copy')"
        @click="onCopy"
      >
        <Check v-if="copied" class="size-3.5 text-success" />
        <Copy v-else class="size-3.5" />
        {{ copied ? "Copied" : "Copy" }}
      </button>
    </div>
    <div class="overflow-x-auto">
      <table class="mono w-full border-collapse text-sm leading-relaxed">
        <tbody>
          <tr v-for="(line, i) in lines" :key="i" class="align-top">
            <td
              v-if="props.showLineNumbers"
              class="select-none border-r border-glass-border px-3 py-0.5 text-right text-muted-foreground/60 tabular"
            >{{ i + 1 }}</td>
            <td class="whitespace-pre px-3 py-0.5">{{ line }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
