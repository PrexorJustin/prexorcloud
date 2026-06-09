<script setup lang="ts">
import { Box, Plus } from "lucide-vue-next"
import EmptyState from "@/components/EmptyState.vue"
import { Button } from "@/components/ui/button"
</script>

<template>
  <Story title="Empty state" group="primitives">
    <Variant title="Default with CTA">
      <EmptyState
        :icon="Box"
        title="No instances yet"
        description="Start an instance from a group to see it listed here."
      >
        <Button size="sm">
          <Plus class="size-4" /> Start instance
        </Button>
      </EmptyState>
    </Variant>
    <Variant title="Description only">
      <EmptyState
        :icon="Box"
        title="No matches"
        description="Try clearing the filter or searching by another term."
      />
    </Variant>
  </Story>
</template>
