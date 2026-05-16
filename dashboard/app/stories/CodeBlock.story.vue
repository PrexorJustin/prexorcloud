<script setup lang="ts">
import { CodeBlock } from "@/components/ui/code-block"

const yaml = `name: lobby
template: lobby@v3
replicas: 4
resources:
  memory: 2048Mi
  cpu: 1000m`
</script>

<template>
  <Story title="Code block" group="primitives">
    <Variant title="With line numbers">
      <CodeBlock :code="yaml" language="yaml" />
    </Variant>
    <Variant title="No line numbers">
      <CodeBlock :code="yaml" language="yaml" :show-line-numbers="false" />
    </Variant>
  </Story>
</template>
