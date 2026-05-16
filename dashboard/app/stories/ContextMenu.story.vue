<script setup lang="ts">
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from "@/components/ui/context-menu"
import { Copy, RefreshCw, Trash2 } from "lucide-vue-next"
</script>

<template>
  <Story title="Context menu" group="primitives">
    <Variant title="Right-click on the box">
      <ContextMenu>
        <ContextMenuTrigger>
          <div class="grid h-32 w-80 cursor-default place-content-center rounded-2xl border border-dashed border-glass-border bg-glass/40 text-sm text-muted-foreground select-none">
            Right-click anywhere
          </div>
        </ContextMenuTrigger>
        <ContextMenuContent>
          <ContextMenuItem><Copy class="mr-2 size-4" /> Copy ID</ContextMenuItem>
          <ContextMenuItem><RefreshCw class="mr-2 size-4" /> Refresh</ContextMenuItem>
          <ContextMenuSeparator />
          <ContextMenuItem class="text-destructive focus:text-destructive">
            <Trash2 class="mr-2 size-4" /> Delete
          </ContextMenuItem>
        </ContextMenuContent>
      </ContextMenu>
    </Variant>
  </Story>
</template>
