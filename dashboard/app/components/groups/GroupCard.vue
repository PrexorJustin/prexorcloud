<script setup lang="ts">
import {Layers, Users, Box, Activity, Play, Loader2} from "lucide-vue-next"
import type {ServerGroup} from "~/types/api"
import {Badge} from "~/components/ui/badge"
import {SCALING_MODE_CONFIG} from "~/lib/constants"
import {toast} from "vue-sonner"

const props = defineProps<{
  group: ServerGroup
}>()

const scaling = computed(() => SCALING_MODE_CONFIG[props.group.scalingMode] ?? {
  label: props.group.scalingMode,
  color: "text-muted-foreground"
})

const starting = ref(false)

async function startInstance() {
  starting.value = true
  try {
    await useApiClient().POST('/api/v1/groups/{name}/start', { params: { path: { name: props.group.name } } })
    toast.success("Instance scheduled", {description: `Starting an instance for "${props.group.name}"`})
  } catch {
    toast.error("Failed to start instance")
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <div
      class="relative bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border hover:bg-glass-hover hover:border-glass-border-hover p-5 cursor-pointer transition-all duration-300 group select-none"
      @click="navigateTo(`/groups/${group.name}`)"
  >
    <div
class="absolute inset-0 bg-linear-to-br from-transparent to-transparent opacity-30 rounded-2xl overflow-hidden"
         :class="group.maintenance ? 'from-warning/10' : 'from-primary/5'"
    />

    <div class="relative z-10">
      <div class="flex items-start justify-between mb-4">
        <div class="flex items-center gap-3">
          <div class="size-10 rounded-xl bg-glass flex items-center justify-center">
            <Layers class="size-5 text-muted-foreground"/>
          </div>
          <div>
            <p class="font-semibold text-foreground">{{ group.name }}</p>
            <p class="text-xs text-muted-foreground">{{ group.platform }} {{ group.platformVersion }}</p>
          </div>
        </div>
        <div class="flex items-center gap-1.5">
          <button
              v-if="!group.maintenance"
              :class="['size-7 rounded-lg flex items-center justify-center transition-all', starting ? 'text-primary' : 'text-muted-foreground/0 group-hover:text-muted-foreground hover:text-primary! hover:bg-primary/10']"
              title="Start instance"
              :disabled="starting"
              @click.stop="startInstance"
          >
            <Loader2 v-if="starting" class="size-3.5 animate-spin"/>
            <Play v-else class="size-3.5"/>
          </button>
          <Badge v-if="group.maintenance" variant="outline" class="text-xs text-warning">Maintenance</Badge>
          <Badge variant="outline" :class="['text-xs', scaling.color]">{{ scaling.label }}</Badge>
        </div>
      </div>

      <div class="grid grid-cols-3 gap-3 mt-3">
        <div class="flex items-center gap-2">
          <Box class="size-3.5 text-muted-foreground"/>
          <span class="text-sm text-foreground tabular-nums">{{ group.runningInstances }}/{{
              group.maxInstances
            }}</span>
        </div>
        <div class="flex items-center gap-2">
          <Users class="size-3.5 text-muted-foreground"/>
          <span class="text-sm text-foreground tabular-nums">{{ group.totalPlayers }}/{{ group.maxPlayers }}</span>
        </div>
        <div class="flex items-center gap-2">
          <Activity class="size-3.5 text-muted-foreground"/>
          <span class="text-sm text-foreground tabular-nums">{{ group.memoryMb }} MB</span>
        </div>
      </div>
    </div>
  </div>
</template>
