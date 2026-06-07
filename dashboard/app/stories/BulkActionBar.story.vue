<script setup lang="ts">
import { ref } from "vue"
import { Square, Trash2, Zap } from "lucide-vue-next"
import { BulkActionBar } from "@/components/ui/bulk-action-bar"
import { Button } from "@/components/ui/button"

const count = ref(3)
</script>

<template>
  <Story title="Bulk action bar" group="primitives">
    <Variant title="Live (use the slider)">
      <div class="flex flex-col gap-3">
        <div class="flex items-center gap-3">
          <label class="text-sm text-muted-foreground">Selection count</label>
          <input v-model.number="count" type="range" min="0" max="50" class="flex-1" >
          <span class="w-8 text-right tabular text-sm">{{ count }}</span>
        </div>
        <p class="text-xs text-muted-foreground">
          The bar slides up from the bottom of the viewport when count &gt; 0. Resize this story panel to see it dock at the bottom.
        </p>
      </div>

      <BulkActionBar :count="count" singular="instance" plural="instances" @clear="count = 0">
        <Button variant="outline" size="sm" class="border-warning/50 text-warning hover:bg-warning/10">
          <Square class="mr-1.5 size-3.5" /> Stop
        </Button>
        <Button variant="outline" size="sm" class="border-destructive/50 text-destructive hover:bg-destructive/10">
          <Zap class="mr-1.5 size-3.5" /> Force-stop
        </Button>
        <Button variant="outline" size="sm" class="border-destructive/50 text-destructive hover:bg-destructive/10">
          <Trash2 class="mr-1.5 size-3.5" /> Delete
        </Button>
      </BulkActionBar>
    </Variant>
  </Story>
</template>
