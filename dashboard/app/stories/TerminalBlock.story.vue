<script setup lang="ts">
import { TerminalBlock } from "@/components/ui/terminal-block"

const sample = `▶ APPLY  groups/lobby.yaml
  ✓ resolved template lobby@v3
  ✓ scheduled 2 instances on node-1
  ✓ scheduled 2 instances on node-3
  applied in 1.2s`
</script>

<template>
  <Story title="Terminal block" group="primitives">
    <Variant title="Default">
      <TerminalBlock title="prexorctl" :copy="sample">{{ sample }}</TerminalBlock>
    </Variant>
    <Variant title="No traffic lights">
      <TerminalBlock :traffic-lights="false">$ prexorctl node ls</TerminalBlock>
    </Variant>
  </Story>
</template>
