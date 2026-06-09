<script setup lang="ts">
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { AlertTriangle, Info, CheckCircle2 } from "lucide-vue-next"
</script>

<template>
  <Story title="Alert (legacy)" group="primitives">
    <Variant title="Variants">
      <div class="flex w-full max-w-xl flex-col gap-3">
        <Alert>
          <Info class="size-4" />
          <AlertTitle>Heads up</AlertTitle>
          <AlertDescription>Default informational alert. Borderless body text.</AlertDescription>
        </Alert>
        <Alert variant="success">
          <CheckCircle2 class="size-4" />
          <AlertTitle>Cluster healthy</AlertTitle>
          <AlertDescription>All 4 nodes are online and reporting.</AlertDescription>
        </Alert>
        <Alert variant="warning">
          <AlertTriangle class="size-4" />
          <AlertTitle>Node draining</AlertTitle>
          <AlertDescription>node-2 is draining — 4 instances will move to other nodes.</AlertDescription>
        </Alert>
        <Alert variant="destructive">
          <AlertTriangle class="size-4" />
          <AlertTitle>Can't reach node-2</AlertTitle>
          <AlertDescription>The controller hasn't received a heartbeat in 90s.</AlertDescription>
        </Alert>
      </div>
    </Variant>

    <Variant title="Note">
      <p class="max-w-md text-sm text-muted-foreground">
        For new code, prefer <code class="mono">&lt;Callout&gt;</code> — it adds the
        <code class="mono">next:</code> slot the design system requires for
        error variants ("name the fix"), and uses the kit's icon set.
      </p>
    </Variant>
  </Story>
</template>
