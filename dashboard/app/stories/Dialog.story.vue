<script setup lang="ts">
import { ref } from "vue"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"

const open1 = ref(false)
const open2 = ref(false)
const open3 = ref(false)
</script>

<template>
  <Story title="Dialog" group="primitives">
    <Variant title="Default — title + description + actions">
      <Button @click="open1 = true">Open</Button>
      <Dialog v-model:open="open1">
        <DialogContent class="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Restart group?</DialogTitle>
            <DialogDescription>Rolling restart cycles every instance with no downtime.</DialogDescription>
          </DialogHeader>
          <DialogFooter class="flex-row! gap-2">
            <Button variant="outline" @click="open1 = false">Cancel</Button>
            <Button @click="open1 = false">Restart</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Variant>

    <Variant title="With form body">
      <Button @click="open2 = true">Open</Button>
      <Dialog v-model:open="open2">
        <DialogContent class="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Create user</DialogTitle>
          </DialogHeader>
          <form class="flex flex-col gap-4" @submit.prevent="open2 = false">
            <div class="flex flex-col gap-1.5">
              <Label for="ex-username">Username</Label>
              <Input id="ex-username" placeholder="alice" />
            </div>
            <div class="flex flex-col gap-1.5">
              <Label for="ex-email">Email</Label>
              <Input id="ex-email" type="email" placeholder="alice@example.com" />
            </div>
            <DialogFooter class="flex-row! gap-2 pt-2">
              <Button type="button" variant="outline" @click="open2 = false">Cancel</Button>
              <Button type="submit">Create</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </Variant>

    <Variant title="Wide (sm:max-w-2xl) for editor-style content">
      <Button @click="open3 = true">Open</Button>
      <Dialog v-model:open="open3">
        <DialogContent class="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>Composition plan</DialogTitle>
            <DialogDescription>Resolved templates and extensions for this instance.</DialogDescription>
          </DialogHeader>
          <pre class="mono max-h-64 overflow-auto rounded-md border border-glass-border bg-glass/50 p-3 text-xs">templates:
  - base
  - base-paper
  - lobby
extensions:
  - paper-1.21.1: viaversion@5.0.0</pre>
          <DialogFooter>
            <Button @click="open3 = false">Done</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Variant>
  </Story>
</template>
