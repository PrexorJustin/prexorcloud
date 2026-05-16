<script setup lang="ts">
import { computed } from "vue"
import { Keyboard } from "lucide-vue-next"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "~/components/ui/dialog"
import { KbdHint } from "~/components/ui/kbd"
import { Eyebrow } from "~/components/ui/eyebrow"
import { shortcuts, type Shortcut } from "~/lib/shortcuts"

const props = defineProps<{ open: boolean }>()
defineEmits<{ "update:open": [value: boolean] }>()

const visible = computed(() => shortcuts.filter(s => !s.hidden))
const sections = computed(() => {
  const grouped: Record<string, Shortcut[]> = {}
  for (const s of visible.value) {
    grouped[s.section] ??= []
    grouped[s.section]!.push(s)
  }
  return grouped
})

function chordTokens(keys: string): string[] {
  return keys.split(" ")
}
</script>

<template>
  <Dialog :open="props.open" @update:open="$emit('update:open', $event)">
    <DialogContent class="max-h-[80vh] overflow-y-auto bg-popover backdrop-blur-xl border-glass-border rounded-2xl sm:max-w-lg [&>button:last-child]:hidden">
      <DialogHeader>
        <div class="flex items-center gap-3">
          <div class="flex size-10 shrink-0 items-center justify-center rounded-xl bg-primary/10 border border-primary/20">
            <Keyboard class="size-5 text-primary" />
          </div>
          <div>
            <DialogTitle>Keyboard shortcuts</DialogTitle>
            <p class="mt-0.5 text-sm text-muted-foreground">Press a chord to jump anywhere in the dashboard.</p>
          </div>
        </div>
      </DialogHeader>

      <div class="space-y-5">
        <section v-for="(items, section) in sections" :key="section" class="space-y-2">
          <Eyebrow>{{ section }}</Eyebrow>
          <div class="space-y-1">
            <div
              v-for="s in items"
              :key="s.keys"
              class="flex items-center justify-between gap-3 rounded-md px-2 py-1.5 hover:bg-glass-hover"
            >
              <span class="text-sm text-foreground">{{ s.label }}</span>
              <span class="flex items-center gap-1">
                <KbdHint v-for="(token, i) in chordTokens(s.keys)" :key="i">{{ token }}</KbdHint>
              </span>
            </div>
          </div>
        </section>
      </div>
    </DialogContent>
  </Dialog>
</template>
