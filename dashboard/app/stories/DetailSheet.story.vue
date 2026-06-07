<script setup lang="ts">
import { ref } from "vue"
import { RefreshCw, Square } from "lucide-vue-next"
import { DetailSheet } from "@/components/ui/detail-sheet"
import { StatusBadge } from "@/components/ui/status-badge"
import { Button } from "@/components/ui/button"

const open1 = ref(true)
const open2 = ref(true)
const open3 = ref(true)
</script>

<template>
  <Story title="Detail sheet" group="primitives">
    <Variant title="With actions + full-page link">
      <Button @click="open1 = true">Open</Button>
      <DetailSheet
        v-model:open="open1"
        title="lobby-01"
        eyebrow="Instance"
        full-page-path="/instances/lobby-01"
      >
        <template #status>
          <StatusBadge state="RUNNING" pulse />
        </template>
        <template #actions>
          <Button variant="outline" size="sm"><RefreshCw class="size-3.5" /></Button>
          <Button variant="outline" size="sm" class="border-warning/50 text-warning hover:bg-warning/10">
            <Square class="mr-1.5 size-3.5" /> Stop
          </Button>
        </template>

        <div class="space-y-4 text-sm">
          <div class="flex justify-between">
            <span class="text-muted-foreground">Group</span>
            <span class="mono">lobby</span>
          </div>
          <div class="flex justify-between">
            <span class="text-muted-foreground">Node</span>
            <span class="mono">node-1</span>
          </div>
          <div class="flex justify-between">
            <span class="text-muted-foreground">Players</span>
            <span class="tabular">47</span>
          </div>
          <div class="flex justify-between">
            <span class="text-muted-foreground">Uptime</span>
            <span class="tabular">7h 12m</span>
          </div>
        </div>
      </DetailSheet>
    </Variant>

    <Variant title="No status, no full-page link">
      <Button @click="open2 = true">Open</Button>
      <DetailSheet
        v-model:open="open2"
        title="Cluster diagnostics"
        eyebrow="System"
      >
        <p class="text-sm text-muted-foreground">
          A drawer without a status badge or full-page link — used for
          ephemeral panels that don't have a deep route.
        </p>
      </DetailSheet>
    </Variant>

    <Variant title="Large size for editor-style content">
      <Button @click="open3 = true">Open</Button>
      <DetailSheet
        v-model:open="open3"
        title="server.properties"
        eyebrow="Template file"
        size="lg"
      >
        <pre class="mono text-xs leading-relaxed">motd=A PrexorCloud server
gamemode=survival
difficulty=normal
max-players=100</pre>
      </DetailSheet>
    </Variant>
  </Story>
</template>
