<script setup lang="ts">
import { StatusBadge } from "@/components/ui/status-badge"
</script>

<template>
  <Story title="Status badge" group="primitives">
    <Variant title="Instance states">
      <div class="flex flex-wrap gap-2">
        <StatusBadge state="RUNNING" pulse />
        <StatusBadge state="STARTING" />
        <StatusBadge state="SCHEDULED" />
        <StatusBadge state="PENDING" />
        <StatusBadge state="STOPPING" />
        <StatusBadge state="STOPPED" />
        <StatusBadge state="CRASHED" />
      </div>
    </Variant>
    <Variant title="Node states">
      <div class="flex flex-wrap gap-2">
        <StatusBadge state="ONLINE" pulse />
        <StatusBadge state="DRAINING" />
        <StatusBadge state="CORDONED" />
        <StatusBadge state="UNREACHABLE" />
        <StatusBadge state="OFFLINE" />
      </div>
    </Variant>
    <Variant title="Custom">
      <div class="flex gap-2">
        <StatusBadge tone="primary" label="Live" pulse />
        <StatusBadge tone="muted" label="Snapshot" />
      </div>
    </Variant>
  </Story>
</template>
