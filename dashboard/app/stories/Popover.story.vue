<script setup lang="ts">
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Input } from "@/components/ui/input"
</script>

<template>
  <Story title="Popover" group="primitives">
    <Variant title="Form-style">
      <Popover>
        <PopoverTrigger as-child>
          <Button variant="outline">Edit dimensions</Button>
        </PopoverTrigger>
        <PopoverContent class="w-72">
          <div class="space-y-3">
            <p class="eyebrow">Memory</p>
            <div class="grid grid-cols-2 gap-2 text-sm">
              <div>
                <Label for="pop-min">Min</Label>
                <Input id="pop-min" placeholder="1024" />
              </div>
              <div>
                <Label for="pop-max">Max</Label>
                <Input id="pop-max" placeholder="4096" />
              </div>
            </div>
            <Button size="sm" class="w-full">Save</Button>
          </div>
        </PopoverContent>
      </Popover>
    </Variant>

    <Variant title="Read-only context">
      <Popover>
        <PopoverTrigger as-child>
          <Button variant="ghost" size="sm">Hover me</Button>
        </PopoverTrigger>
        <PopoverContent class="w-64">
          <p class="text-sm">
            Use Popover for transient panels with a clear trigger.
          </p>
          <p class="mt-2 text-xs text-muted-foreground">
            For read-only one-liners, prefer Tooltip.
          </p>
        </PopoverContent>
      </Popover>
    </Variant>
  </Story>
</template>
