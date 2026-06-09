<script setup lang="ts">
import { Toaster, toast } from "vue-sonner"
import { Button } from "@/components/ui/button"

function fireSuccess() {
  toast.success("Instance scheduled", { description: "Starting on node-1." })
}
function fireError() {
  toast.error("Stop failed", { description: "Couldn't stop lobby-04. Try force-stop, or check the controller logs." })
}
function fireWarning() {
  toast.warning("Drain in progress", { description: "node-2 still has 3 instances to migrate." })
}
function fireInfo() {
  toast("Maintenance window starts in 15 minutes")
}
</script>

<template>
  <Story title="Toast (sonner)" group="primitives">
    <Variant title="All tones">
      <div class="flex flex-wrap gap-3">
        <Button @click="fireSuccess">Success</Button>
        <Button variant="outline" class="border-warning/50 text-warning hover:bg-warning/10" @click="fireWarning">Warning</Button>
        <Button variant="outline" class="border-destructive/50 text-destructive hover:bg-destructive/10" @click="fireError">Error</Button>
        <Button variant="outline" @click="fireInfo">Info</Button>
      </div>
      <Toaster position="bottom-right" />
    </Variant>

    <Variant title="Voice rule reminder">
      <p class="max-w-md text-sm text-muted-foreground">
        Per the design system: error toasts should <strong>name a fix</strong>.
        Don't write <code class="mono">"Stop failed"</code> alone — pair it with a
        description that tells the operator what to try next.
      </p>
    </Variant>
  </Story>
</template>
