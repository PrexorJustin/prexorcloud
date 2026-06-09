<script setup lang="ts">
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { StatusBadge } from "@/components/ui/status-badge"
</script>

<template>
  <Story title="Card" group="primitives">
    <Variant title="Default — title + description + content + footer">
      <Card class="max-w-md">
        <CardHeader>
          <CardTitle>lobby</CardTitle>
          <CardDescription>Paper 1.21.1 · 4 / 6 instances</CardDescription>
        </CardHeader>
        <CardContent class="space-y-2 text-sm">
          <div class="flex justify-between">
            <span class="text-muted-foreground">Players</span>
            <span class="tabular">87 / 200</span>
          </div>
          <div class="flex justify-between">
            <span class="text-muted-foreground">Routing</span>
            <span>LEAST_LOADED</span>
          </div>
        </CardContent>
        <CardFooter class="flex gap-2">
          <Button size="sm" class="flex-1">Deploy</Button>
          <Button size="sm" variant="outline" class="flex-1">Restart</Button>
        </CardFooter>
      </Card>
    </Variant>

    <Variant title="With status pill">
      <Card class="max-w-md">
        <CardHeader>
          <div class="flex items-start justify-between">
            <div>
              <CardTitle>node-2</CardTitle>
              <CardDescription>10.0.1.22:8443</CardDescription>
            </div>
            <StatusBadge state="DRAINING" />
          </div>
        </CardHeader>
        <CardContent class="text-sm text-muted-foreground">
          <p>Migrating 4 instances. ETA ~2 minutes.</p>
        </CardContent>
      </Card>
    </Variant>
  </Story>
</template>
