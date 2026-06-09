<script setup lang="ts">
import { ref } from "vue"
import { Checkbox } from "@/components/ui/checkbox"
import { Label } from "@/components/ui/label"

const a = ref(false)
const b = ref(true)
const c = ref<boolean | "indeterminate">("indeterminate")
const d = ref(false)
</script>

<template>
  <Story title="Checkbox" group="primitives">
    <Variant title="Default states">
      <div class="space-y-3">
        <label class="flex items-center gap-2.5 text-sm">
          <Checkbox v-model="a" />
          Unchecked / hover me
        </label>
        <label class="flex items-center gap-2.5 text-sm">
          <Checkbox v-model="b" />
          Checked
        </label>
        <label class="flex items-center gap-2.5 text-sm">
          <Checkbox v-model="c" />
          Indeterminate
        </label>
        <label class="flex items-center gap-2.5 text-sm opacity-60">
          <Checkbox v-model="d" disabled />
          Disabled
        </label>
      </div>
    </Variant>

    <Variant title="In a list row">
      <div class="overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
        <div class="flex h-10 items-center border-b border-glass-border px-4 eyebrow">
          <div class="w-8 -ml-1"><Checkbox /></div>
          <div class="flex-1">Resource</div>
          <div class="w-24 text-right">Status</div>
        </div>
        <div v-for="(name, i) in ['lobby-01', 'lobby-02', 'survival-01']" :key="name" class="flex h-12 cursor-pointer items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover">
          <div class="w-8 -ml-1" @click.stop><Checkbox :model-value="i === 1" /></div>
          <div class="flex-1 text-sm mono">{{ name }}</div>
          <div class="w-24 text-right text-sm text-muted-foreground">RUNNING</div>
        </div>
      </div>
    </Variant>

    <Variant title="With label primitive">
      <div class="flex items-center gap-2">
        <Checkbox id="terms" />
        <Label for="terms" class="cursor-pointer">Accept terms and conditions</Label>
      </div>
    </Variant>
  </Story>
</template>
