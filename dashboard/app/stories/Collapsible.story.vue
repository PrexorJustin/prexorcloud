<script setup lang="ts">
import { ref } from "vue"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { Button } from "@/components/ui/button"
import { ChevronDown } from "lucide-vue-next"

const open = ref(true)
</script>

<template>
  <Story title="Collapsible" group="primitives">
    <Variant title="Default">
      <Collapsible v-model:open="open" class="w-80 space-y-2 rounded-xl border border-glass-border bg-glass/40 p-3">
        <CollapsibleTrigger as-child>
          <Button variant="ghost" size="sm" class="w-full justify-between">
            Advanced settings
            <ChevronDown class="size-4 transition-transform" :class="open ? 'rotate-180' : ''" />
          </Button>
        </CollapsibleTrigger>
        <CollapsibleContent class="space-y-2 pt-2 text-sm text-muted-foreground">
          <p>Burst ceiling, predictive scaling, anti-affinity rules.</p>
          <p>Hidden by default to keep the form short.</p>
        </CollapsibleContent>
      </Collapsible>
    </Variant>
  </Story>
</template>
