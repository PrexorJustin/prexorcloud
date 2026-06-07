<script setup lang="ts">
import { ref } from "vue"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Switch } from "@/components/ui/switch"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"

const username = ref("")
const bio = ref("")
const enabled = ref(true)
const role = ref("VIEWER")
const env = ref("dev")
</script>

<template>
  <Story title="Form controls" group="primitives">
    <Variant title="Input + Label">
      <div class="flex max-w-sm flex-col gap-2">
        <Label for="ex-username">Username</Label>
        <Input id="ex-username" v-model="username" placeholder="alice" />
        <p class="text-xs text-muted-foreground">Used as the sign-in handle. Lowercase only.</p>
      </div>
    </Variant>

    <Variant title="Textarea">
      <div class="flex max-w-md flex-col gap-2">
        <Label for="ex-bio">Maintenance message</Label>
        <Textarea id="ex-bio" v-model="bio" rows="3" placeholder="Cluster will be down for migration from 03:00–04:00 UTC." />
      </div>
    </Variant>

    <Variant title="Switch">
      <div class="flex items-center gap-3">
        <Switch id="ex-enabled" v-model="enabled" />
        <Label for="ex-enabled" class="cursor-pointer">Cluster maintenance enabled</Label>
      </div>
    </Variant>

    <Variant title="Select">
      <div class="flex max-w-xs flex-col gap-2">
        <Label for="ex-role">Role</Label>
        <Select v-model="role">
          <SelectTrigger id="ex-role"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="ADMIN">ADMIN</SelectItem>
            <SelectItem value="OPERATOR">OPERATOR</SelectItem>
            <SelectItem value="DEPLOYER">DEPLOYER</SelectItem>
            <SelectItem value="VIEWER">VIEWER</SelectItem>
          </SelectContent>
        </Select>
      </div>
    </Variant>

    <Variant title="Radio group">
      <RadioGroup v-model="env" class="flex flex-col gap-3">
        <label class="flex items-center gap-2.5 text-sm">
          <RadioGroupItem value="dev" /> Development
        </label>
        <label class="flex items-center gap-2.5 text-sm">
          <RadioGroupItem value="staging" /> Staging
        </label>
        <label class="flex items-center gap-2.5 text-sm">
          <RadioGroupItem value="prod" /> Production
        </label>
      </RadioGroup>
    </Variant>
  </Story>
</template>
