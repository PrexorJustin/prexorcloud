<script setup lang="ts">
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import { Button } from "@/components/ui/button"
import { Info, Trash2 } from "lucide-vue-next"
</script>

<template>
  <Story title="Tooltip" group="primitives">
    <Variant title="Icon button (no visible label)">
      <TooltipProvider>
        <Tooltip>
          <TooltipTrigger as-child>
            <Button variant="ghost" size="icon" aria-label="Info"><Info class="size-4" /></Button>
          </TooltipTrigger>
          <TooltipContent>One-line context for the icon button.</TooltipContent>
        </Tooltip>
      </TooltipProvider>
    </Variant>

    <Variant title="Destructive action">
      <TooltipProvider>
        <Tooltip>
          <TooltipTrigger as-child>
            <Button variant="ghost" size="icon" class="text-destructive" aria-label="Delete"><Trash2 class="size-4" /></Button>
          </TooltipTrigger>
          <TooltipContent>Delete (Esc to cancel)</TooltipContent>
        </Tooltip>
      </TooltipProvider>
    </Variant>
  </Story>
</template>
