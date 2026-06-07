<script setup lang="ts">
import { Box, Layers, Clock } from "lucide-vue-next"
import { Avatar, AvatarFallback } from "~/components/ui/avatar"
import { StatusBadge } from "~/components/ui/status-badge"
import type { StatusDotTone } from "~/components/ui/status-dot"
import { getInitials, timeAgo } from "~/lib/utils"
import type { Player } from "~/stores/players"

const props = defineProps<{
  player: Player
}>()

const emit = defineEmits<{
  select: [player: Player]
}>()

const { t } = useI18n()

function pingTone(ping?: number): StatusDotTone {
  if (ping === undefined) return "muted"
  if (ping < 80) return "success"
  if (ping < 180) return "warning"
  return "destructive"
}

function pingLabel(ping?: number) {
  if (ping === undefined) return "—"
  return `${ping} ms`
}
</script>

<template>
  <div
    class="group relative cursor-pointer select-none rounded-2xl border border-glass-border bg-glass/60 p-5 backdrop-blur-xl transition-all duration-300 hover:border-glass-border-hover hover:bg-glass-hover"
    @click="emit('select', props.player)"
  >
    <div class="mb-4 flex items-start justify-between gap-3">
      <div class="flex min-w-0 items-center gap-3">
        <Avatar class="size-10 shrink-0 bg-primary/15 text-primary">
          <AvatarFallback class="text-sm font-bold">{{ getInitials(props.player.username) }}</AvatarFallback>
        </Avatar>
        <div class="min-w-0">
          <p class="truncate text-sm font-semibold text-foreground">{{ props.player.username }}</p>
          <p class="truncate mono text-[11px] text-muted-foreground">{{ props.player.uuid }}</p>
        </div>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <StatusBadge
          v-if="props.player.edition === 'bedrock'"
          tone="primary"
          :label="t('pages.players.editions.bedrock')"
        />
        <StatusBadge :tone="pingTone(props.player.ping)" :label="pingLabel(props.player.ping)" />
      </div>
    </div>

    <div class="grid grid-cols-2 gap-3 text-sm">
      <div class="flex min-w-0 items-center gap-2">
        <Box class="size-3.5 shrink-0 text-muted-foreground" />
        <NuxtLink
          v-if="props.player.currentInstance"
          :to="`/instances/${props.player.currentInstance}`"
          class="truncate mono text-xs text-primary hover:underline"
          @click.stop
        >
          {{ props.player.currentInstance }}
        </NuxtLink>
        <span v-else class="text-xs text-muted-foreground">—</span>
      </div>
      <div class="flex min-w-0 items-center gap-2">
        <Layers class="size-3.5 shrink-0 text-muted-foreground" />
        <span class="truncate text-xs text-muted-foreground">{{ props.player.group ?? '—' }}</span>
      </div>
    </div>

    <div class="mt-3 flex items-center gap-2 border-t border-glass-border/50 pt-3 text-xs text-muted-foreground">
      <Clock class="size-3 shrink-0" />
      <span class="tabular">{{ props.player.connectedAt ? timeAgo(props.player.connectedAt) : '—' }}</span>
    </div>
  </div>
</template>
