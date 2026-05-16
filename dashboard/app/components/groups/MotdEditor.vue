<script setup lang="ts">
import { Plus, Trash2, Loader2, GripVertical } from "lucide-vue-next"
import { Button } from "~/components/ui/button"
import { toast } from "vue-sonner"
import { renderMiniMessage } from "~/lib/minimessage"
import type { ServerGroup } from "~/types/api"

const props = defineProps<{ group: ServerGroup }>()
const emit = defineEmits<{ saved: [] }>()

type MotdMode = 'STATIC' | 'SEQUENTIAL' | 'RANDOM'

const motds = ref<string[]>(props.group.motds?.length ? [...props.group.motds] : [''])
const motdMode = ref<MotdMode>((props.group.motdMode as MotdMode) ?? 'STATIC')
const motdIntervalSeconds = ref(props.group.motdIntervalSeconds > 0 ? props.group.motdIntervalSeconds : 30)
const saving = ref(false)

const modes: { value: MotdMode; label: string; description: string }[] = [
  { value: 'STATIC', label: 'Static', description: 'Always show the first MOTD' },
  { value: 'SEQUENTIAL', label: 'Sequential', description: 'Rotate on a timer' },
  { value: 'RANDOM', label: 'Random', description: 'Pick one at random each ping' },
]

function addMotd() {
  motds.value.push('')
}

function removeMotd(i: number) {
  motds.value.splice(i, 1)
  if (motds.value.length === 0) motds.value.push('')
}

async function save() {
  saving.value = true
  try {
    // PATCH body is a partial — the SDK type insists on the full GroupDto, but
    // the controller accepts partial updates. Cast to bypass the strict shape.
    const body: Record<string, unknown> = {
      motds: motds.value.filter(m => m.trim()),
      motdMode: motdMode.value,
      motdIntervalSeconds: motdIntervalSeconds.value,
    }
    await useApiClient().PATCH('/api/v1/groups/{name}', {
      params: { path: { name: props.group.name } },
      body: body as never,
    })
    toast.success('MOTD saved')
    emit('saved')
  } catch {
    toast.error('Save failed', { description: "Couldn't save the MOTD. Try again, or check the controller logs." })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-5">
    <!-- Mode selector -->
    <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
      <h3 class="text-base font-semibold text-foreground mb-4">MOTD Mode</h3>
      <div class="flex gap-2">
        <button
          v-for="m in modes"
          :key="m.value"
          class="flex-1 rounded-xl border px-3 py-3 text-left transition-colors"
          :class="motdMode === m.value
            ? 'border-primary bg-primary/10 text-foreground'
            : 'border-glass-border bg-glass/40 text-muted-foreground hover:text-foreground hover:border-muted'"
          @click="motdMode = m.value"
        >
          <div class="text-sm font-medium">{{ m.label }}</div>
          <div class="text-xs mt-0.5 opacity-70">{{ m.description }}</div>
        </button>
      </div>

      <div v-if="motdMode === 'SEQUENTIAL'" class="mt-4 flex items-center gap-3">
        <span class="text-sm text-muted-foreground">Rotate every</span>
        <input
          v-model.number="motdIntervalSeconds"
          type="number"
          min="5"
          class="w-20 rounded-lg border border-glass-border bg-glass/60 px-3 py-1.5 text-sm text-foreground text-center [appearance:textfield] focus:outline-none focus:ring-1 focus:ring-primary"
        >
        <span class="text-sm text-muted-foreground">seconds</span>
      </div>
    </div>

    <!-- MOTD entries -->
    <div class="bg-glass/60 backdrop-blur-xl rounded-2xl border border-glass-border p-6">
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-base font-semibold text-foreground">Messages</h3>
        <Button variant="outline" size="sm" class="h-7 text-xs gap-1" @click="addMotd">
          <Plus class="size-3" /> Add
        </Button>
      </div>

      <div class="flex flex-col gap-3">
        <div v-for="(motd, i) in motds" :key="i" class="flex flex-col gap-1.5">
          <div class="flex items-center gap-2">
            <GripVertical class="size-4 text-muted-foreground/40 shrink-0" />
            <input
              v-model="motds[i]"
              type="text"
              placeholder="<gold>My Server <white>— <gray>A PrexorCloud network"
              class="flex-1 rounded-lg border border-glass-border bg-glass/60 px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground/50 font-mono focus:outline-none focus:ring-1 focus:ring-primary"
            >
            <button
              class="shrink-0 p-1.5 rounded-lg text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
              @click="removeMotd(i)"
            >
              <Trash2 class="size-3.5" />
            </button>
          </div>
          <!-- MiniMessage preview -->
          <div
            v-if="motds[i]?.trim()"
            class="ml-6 px-3 py-1.5 rounded-lg bg-[#1a1a2e] text-sm font-minecraft leading-snug"
            v-html="renderMiniMessage(motds[i] ?? '')"
          />
        </div>
      </div>
    </div>

    <!-- Save -->
    <div class="flex justify-end">
      <Button :disabled="saving" @click="save">
        <Loader2 v-if="saving" class="size-4 mr-2 animate-spin" />
        Save MOTD
      </Button>
    </div>
  </div>
</template>
