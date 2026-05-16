<script setup lang="ts">
import DiffViewer from "@/components/ui/diff-viewer/DiffViewer.vue"

const before = `name: lobby
template: lobby@v3
replicas: 4
resources:
  memory: 2048Mi
  cpu: 1000m
maintenance: false`

const after = `name: lobby
template: lobby@v4
replicas: 6
resources:
  memory: 3072Mi
  cpu: 1500m
maintenance: false
priority: 100`
</script>

<template>
  <Story title="Diff viewer" group="primitives">
    <Variant title="Unified + split (toggle in header)">
      <DiffViewer
        :original="before"
        :modified="after"
        original-label="Revision 3"
        modified-label="Revision 4"
      />
    </Variant>
  </Story>
</template>
