<script setup lang="ts">
import { Plus, RefreshCw } from "lucide-vue-next"
import PageHeader from "@/components/PageHeader.vue"
import { Button } from "@/components/ui/button"
</script>

<template>
  <Story title="Page header" group="primitives">
    <Variant title="With description and actions">
      <PageHeader
        title="Instances"
        description="Running Minecraft server processes across the cluster."
      >
        <template #actions>
          <Button variant="outline" size="sm">
            <RefreshCw class="size-4" /> Refresh
          </Button>
          <Button size="sm">
            <Plus class="size-4" /> Start instance
          </Button>
        </template>
      </PageHeader>
    </Variant>
    <Variant title="With eyebrow">
      <PageHeader
        eyebrow="Infrastructure"
        title="Nodes"
        description="Hosts connected to the cluster."
      />
    </Variant>
    <Variant title="Title only">
      <PageHeader title="Audit log" />
    </Variant>
  </Story>
</template>
