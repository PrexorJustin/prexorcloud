<script setup lang="ts">
import { Callout, CalloutTitle, CalloutBody } from "@/components/ui/callout"
</script>

<template>
  <Story title="Callout" group="primitives">
    <Variant title="Info">
      <Callout variant="info">
        <CalloutTitle>Drift detected</CalloutTitle>
        <CalloutBody>The lobby template on disk diverges from the recorded version hash by 3 files.</CalloutBody>
      </Callout>
    </Variant>
    <Variant title="OK">
      <Callout variant="ok">
        <CalloutTitle>Cluster healthy</CalloutTitle>
        <CalloutBody>All 4 nodes are online and reporting.</CalloutBody>
      </Callout>
    </Variant>
    <Variant title="Warning">
      <Callout variant="warning">
        <CalloutTitle>Node draining</CalloutTitle>
        <CalloutBody>node-2 is draining — 4 instances will move to other nodes.</CalloutBody>
      </Callout>
    </Variant>
    <Variant title="Error with named fix">
      <Callout variant="error">
        <CalloutTitle>Can't reach node-2 on :8080</CalloutTitle>
        <CalloutBody>The controller hasn't received a heartbeat in 90s.</CalloutBody>
        <template #next>
          Check the firewall or run <code class="mono">prexorctl node ping node-2</code>.
        </template>
      </Callout>
    </Variant>
  </Story>
</template>
