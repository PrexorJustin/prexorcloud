<script setup lang="ts">
import { Box, Users, Clock, Layers, Square, Trash2, Loader2 } from "lucide-vue-next"
import type { ServerInstance } from "~/types/api"
import { StatusBadge } from "~/components/ui/status-badge"
import { formatUptime } from "~/lib/utils"
import { INSTANCE_STATE_CONFIG } from "~/lib/constants"
import { toast } from "vue-sonner"

const props = defineProps<{
  instance: ServerInstance
}>()

const store = useInstancesStore()
const { t } = useI18n()
const acting = ref(false)

const state = computed(() => INSTANCE_STATE_CONFIG[props.instance.state] ?? { label: props.instance.state, color: "text-muted-foreground", dot: "bg-muted-foreground" })

const canStop = computed(() => props.instance.state === "RUNNING" || props.instance.state === "STARTING")
const canDelete = computed(() => props.instance.state === "STOPPED" || props.instance.state === "CRASHED" || props.instance.state === "SCHEDULED")

async function stopInstance() {
  acting.value = true
  try {
    await store.stopInstance(props.instance.id)
  } catch {
    toast.error(t("toast.instances.stopFailed"), {
      description: t("toast.instances.stopFailedDesc", { id: props.instance.id }),
    })
  } finally {
    acting.value = false
  }
}

async function deleteInstance() {
  acting.value = true
  try {
    await store.deleteInstance(props.instance.id)
  } catch {
    toast.error(t("toast.instances.deleteFailed"), {
      description: t("toast.instances.deleteFailedDesc", { id: props.instance.id }),
    })
  } finally {
    acting.value = false
  }
}
</script>

<template>
  <div
    class="relative bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border hover:bg-glass-hover hover:border-glass-border-hover p-5 cursor-pointer transition-all duration-300 group select-none"
    @click="navigateTo(`/instances/${instance.id}`)"
  >
    <div
class="absolute inset-0 bg-linear-to-br from-transparent to-transparent opacity-30 rounded-2xl overflow-hidden"
      :class="instance.state === 'RUNNING' ? 'from-success/10' : instance.state === 'CRASHED' ? 'from-destructive/10' : 'from-muted/10'"
    />

    <div class="relative z-10">
      <div class="flex items-start justify-between mb-4">
        <div class="flex items-center gap-3">
          <div class="relative">
            <div class="size-10 rounded-xl bg-glass flex items-center justify-center">
              <Box class="size-5 text-muted-foreground" />
            </div>
            <div :class="['absolute -bottom-0.5 -right-0.5 size-3 rounded-full border-2 border-background', state.dot]" />
          </div>
          <div>
            <p class="font-semibold text-foreground">{{ instance.id }}</p>
            <p class="text-xs text-muted-foreground">{{ instance.node }}</p>
          </div>
        </div>
        <div class="flex items-center gap-1.5">
          <!-- Stop button (running/starting) -->
          <button
            v-if="canStop"
            :class="['size-7 rounded-lg flex items-center justify-center transition-all', acting ? 'text-warning' : 'text-muted-foreground/0 group-hover:text-muted-foreground hover:text-warning! hover:bg-warning/10']"
            title="Stop instance"
            :disabled="acting"
            @click.stop="stopInstance"
          >
            <Loader2 v-if="acting" class="size-3.5 animate-spin" />
            <Square v-else class="size-3.5" />
          </button>
          <!-- Delete button (stopped/crashed/scheduled) -->
          <button
            v-if="canDelete"
            :class="['size-7 rounded-lg flex items-center justify-center transition-all', acting ? 'text-destructive' : 'text-muted-foreground/0 group-hover:text-muted-foreground hover:text-destructive! hover:bg-destructive/10']"
            title="Delete instance"
            :disabled="acting"
            @click.stop="deleteInstance"
          >
            <Loader2 v-if="acting" class="size-3.5 animate-spin" />
            <Trash2 v-else class="size-3.5" />
          </button>
          <StatusBadge :state="instance.state" />
        </div>
      </div>

      <div class="grid grid-cols-3 gap-3 mt-3">
        <div class="flex items-center gap-2">
          <Layers class="size-3.5 text-muted-foreground" />
          <span class="text-sm text-foreground truncate">{{ instance.group }}</span>
        </div>
        <div class="flex items-center gap-2">
          <Users class="size-3.5 text-muted-foreground" />
          <span class="text-sm text-foreground tabular-nums">{{ instance.playerCount }}</span>
        </div>
        <div class="flex items-center gap-2">
          <Clock class="size-3.5 text-muted-foreground" />
          <span class="text-sm text-foreground tabular-nums">{{ formatUptime(instance.uptimeMs) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
