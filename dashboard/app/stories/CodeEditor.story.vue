<script setup lang="ts">
import { ref } from "vue"
import CodeEditor from "@/components/templates/CodeEditor.vue"

const yamlSample = ref(`# server.yml
server:
  motd: "<gold>PrexorCloud <gray>— <white>welcome"
  max-players: 100
  view-distance: 10
  online-mode: true
plugins:
  enabled:
    - viaversion
    - paperweight
`)

const jsonSample = ref(`{
  "name": "lobby",
  "template": "lobby@v3",
  "replicas": 4,
  "resources": { "memory": 2048, "cpu": 1000 }
}`)
</script>

<template>
  <Story title="Code editor (CodeMirror)" group="primitives">
    <Variant title="YAML">
      <div class="h-80 overflow-hidden rounded-xl border border-glass-border bg-background">
        <CodeEditor v-model="yamlSample" language="yaml" />
      </div>
    </Variant>

    <Variant title="JSON, read-only">
      <div class="h-64 overflow-hidden rounded-xl border border-glass-border bg-background">
        <CodeEditor v-model="jsonSample" language="json" :read-only="true" />
      </div>
    </Variant>
  </Story>
</template>
