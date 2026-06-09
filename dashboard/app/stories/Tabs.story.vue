<script setup lang="ts">
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Activity, Box, FileCode } from "lucide-vue-next"
</script>

<template>
  <Story title="Tabs" group="primitives">
    <Variant title="Default — text only">
      <Tabs default-value="overview" class="w-full max-w-xl">
        <TabsList>
          <TabsTrigger value="overview">Overview</TabsTrigger>
          <TabsTrigger value="metrics">Metrics</TabsTrigger>
          <TabsTrigger value="composition">Composition</TabsTrigger>
        </TabsList>
        <TabsContent value="overview" class="mt-4 text-sm">Instance summary, resource links, action buttons.</TabsContent>
        <TabsContent value="metrics" class="mt-4 text-sm">Live TPS / heap / mspt charts.</TabsContent>
        <TabsContent value="composition" class="mt-4 text-sm">Resolved module + extension plan.</TabsContent>
      </Tabs>
    </Variant>

    <Variant title="With icons">
      <Tabs default-value="overview" class="w-full max-w-xl">
        <TabsList>
          <TabsTrigger value="overview" class="gap-1.5"><Activity class="size-3.5" /> Overview</TabsTrigger>
          <TabsTrigger value="instances" class="gap-1.5"><Box class="size-3.5" /> Instances</TabsTrigger>
          <TabsTrigger value="files" class="gap-1.5"><FileCode class="size-3.5" /> Files</TabsTrigger>
        </TabsList>
        <TabsContent value="overview" class="mt-4 text-sm">Section content</TabsContent>
        <TabsContent value="instances" class="mt-4 text-sm">Section content</TabsContent>
        <TabsContent value="files" class="mt-4 text-sm">Section content</TabsContent>
      </Tabs>
    </Variant>
  </Story>
</template>
