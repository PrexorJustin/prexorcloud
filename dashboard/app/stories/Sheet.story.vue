<script setup lang="ts">
import { ref } from "vue"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { Button } from "@/components/ui/button"

const open1 = ref(false)
const open2 = ref(false)
</script>

<template>
  <Story title="Sheet (raw)" group="primitives">
    <Variant title="Right side (default)">
      <Button @click="open1 = true">Open right</Button>
      <Sheet v-model:open="open1">
        <SheetContent>
          <SheetHeader>
            <SheetTitle>Filters</SheetTitle>
            <SheetDescription>Narrow what's shown in the table.</SheetDescription>
          </SheetHeader>
          <div class="px-6 pb-6 text-sm text-muted-foreground">
            For row-detail panels prefer <code class="mono">&lt;DetailSheet&gt;</code> — it
            adds the eyebrow, status slot, action row, and "Open full page" footer that
            list-detail flows expect.
          </div>
          <SheetFooter>
            <Button @click="open1 = false">Apply</Button>
          </SheetFooter>
        </SheetContent>
      </Sheet>
    </Variant>

    <Variant title="Bottom (mobile-style)">
      <Button @click="open2 = true">Open bottom</Button>
      <Sheet v-model:open="open2">
        <SheetContent side="bottom">
          <SheetHeader>
            <SheetTitle>Quick actions</SheetTitle>
          </SheetHeader>
          <div class="grid grid-cols-2 gap-3 p-6">
            <Button variant="outline">Stop</Button>
            <Button variant="outline">Restart</Button>
            <Button variant="outline">Drain</Button>
            <Button variant="outline">Cordon</Button>
          </div>
        </SheetContent>
      </Sheet>
    </Variant>
  </Story>
</template>
