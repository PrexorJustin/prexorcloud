<script setup lang="ts">
import { Badge } from "@/components/ui/badge"
import { Shield } from "lucide-vue-next"
</script>

<template>
  <Story title="Badge" group="primitives">
    <Variant title="Variants">
      <div class="flex flex-wrap items-center gap-2">
        <Badge>Default</Badge>
        <Badge variant="secondary">Secondary</Badge>
        <Badge variant="outline">Outline</Badge>
        <Badge variant="destructive">Destructive</Badge>
      </div>
    </Variant>

    <Variant title="With leading icon">
      <div class="flex flex-wrap items-center gap-2">
        <Badge variant="outline">
          <Shield class="size-3" /> ADMIN
        </Badge>
        <Badge variant="secondary">v1.21.1</Badge>
      </div>
    </Variant>

    <Variant title="Note">
      <p class="max-w-md text-sm text-muted-foreground">
        For status indicators (Running, Crashed, Draining …) prefer
        <code class="mono">&lt;StatusBadge&gt;</code> — it pairs the dot + label pattern the
        design system requires.
      </p>
    </Variant>
  </Story>
</template>
