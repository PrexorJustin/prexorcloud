<script setup lang="ts">
import { Skeleton } from "@/components/ui/skeleton"
import LoadingSkeleton from "@/components/LoadingSkeleton.vue"
</script>

<template>
  <Story title="Skeleton" group="primitives">
    <Variant title="Raw Skeleton primitive">
      <div class="flex items-center gap-3">
        <Skeleton class="size-12 rounded-xl" />
        <div class="flex flex-1 flex-col gap-2">
          <Skeleton class="h-4 w-1/3" />
          <Skeleton class="h-3 w-1/2" />
        </div>
      </div>
    </Variant>

    <Variant title="LoadingSkeleton — grid mode (cards)">
      <LoadingSkeleton mode="grid" :count="3" />
    </Variant>

    <Variant title="LoadingSkeleton — table mode">
      <LoadingSkeleton mode="table" :count="5" />
    </Variant>
  </Story>
</template>
