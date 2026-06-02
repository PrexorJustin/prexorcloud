<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { ArrowUpRight, X } from "lucide-vue-next"
import { Sheet, SheetContent } from "~/components/ui/sheet"
import { Eyebrow } from "~/components/ui/eyebrow"
import { cn } from "@/lib/utils"

/**
 * Right-rail detail panel — the canonical "row click → drawer" surface for
 * lists across the dashboard. Wraps reka-ui's Sheet/SheetContent with the
 * dashboard's design-system shell (glass, eyebrow + title, action row,
 * "Open full page" footer link).
 *
 * Adoption pattern:
 *   <DetailSheet
 *     :open="!!selected"
 *     :title="selected?.id"
 *     eyebrow="Instance"
 *     :full-page-path="selected ? `/instances/${selected.id}` : undefined"
 *     @update:open="(o) => { if (!o) selected = null }"
 *   >
 *     <template #status><StatusBadge :state="selected.state" /></template>
 *     <template #actions><Button>Stop</Button></template>
 *     ...content...
 *   </DetailSheet>
 */
const props = withDefaults(defineProps<{
  open: boolean
  title?: string
  eyebrow?: string
  fullPagePath?: string
  /** Override default 480px width. Use 'lg' for editor-ish sheets. */
  size?: "default" | "lg"
  class?: HTMLAttributes["class"]
}>(), {
  size: "default",
})

const emit = defineEmits<{
  "update:open": [value: boolean]
}>()

function close() { emit("update:open", false) }

const { t } = useI18n()

const widthClass = {
  default: "sm:max-w-[480px]",
  lg:      "sm:max-w-[640px]",
}
</script>

<template>
  <Sheet :open="props.open" @update:open="emit('update:open', $event)">
    <SheetContent
      side="right"
      :class="cn(
        'flex w-full flex-col gap-0 border-l border-glass-border bg-popover/95 p-0 backdrop-blur-xl shadow-lg',
        widthClass[props.size],
        props.class,
      )"
    >
      <header class="flex items-start gap-3 border-b border-glass-border px-5 py-4">
        <div class="min-w-0 flex-1">
          <Eyebrow v-if="props.eyebrow || $slots.eyebrow" class="mb-1.5">
            <slot name="eyebrow">{{ props.eyebrow }}</slot>
          </Eyebrow>
          <div class="flex items-center gap-2.5">
            <h2 v-if="props.title || $slots.title" class="truncate text-lg font-semibold tracking-tight mono">
              <slot name="title">{{ props.title }}</slot>
            </h2>
            <slot name="status" />
          </div>
        </div>
        <div v-if="$slots.actions" class="flex shrink-0 items-center gap-1.5">
          <slot name="actions" />
        </div>
        <button
          type="button"
          :aria-label="t('components.detailSheet.close')"
          class="-mr-1.5 -mt-0.5 inline-flex size-8 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-glass-hover hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
          @click="close"
        >
          <X class="size-4" />
        </button>
      </header>

      <div class="min-h-0 flex-1 overflow-y-auto styled-scrollbar px-5 py-5">
        <slot />
      </div>

      <footer
        v-if="props.fullPagePath || $slots.footer"
        class="flex shrink-0 items-center gap-3 border-t border-glass-border bg-glass/30 px-5 py-3"
      >
        <slot name="footer">
          <NuxtLink
            v-if="props.fullPagePath"
            :to="props.fullPagePath"
            class="inline-flex items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
            @click="close"
          >
            Open full page
            <ArrowUpRight class="size-3.5" />
          </NuxtLink>
        </slot>
      </footer>
    </SheetContent>
  </Sheet>
</template>
