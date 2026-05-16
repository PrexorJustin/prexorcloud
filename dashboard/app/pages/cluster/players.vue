<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { UsersRound, ArrowRightLeft } from "lucide-vue-next"
import { VList } from "virtua/vue"
import { useVirtualList } from "@vueuse/core"
import { Avatar, AvatarFallback } from "~/components/ui/avatar"
import { DetailSheet } from "~/components/ui/detail-sheet"
import { Button } from "~/components/ui/button"
import { Label } from "~/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "~/components/ui/select"
import { StatusBadge } from "~/components/ui/status-badge"
import type { StatusDotTone } from "~/components/ui/status-dot"
import { Eyebrow } from "~/components/ui/eyebrow"
import { getInitials, timeAgo } from "~/lib/utils"
import type { Player, PlayerJourneyEntry } from "~/stores/players"

const store = usePlayersStore()
const instances = useInstancesStore()

onMounted(() => {
  store.fetchPlayers()
  instances.fetchInstances()
})

const { search, viewMode, filteredItems: filteredPlayers } = useFilteredList(
  () => store.players,
  {
    searchFields: p => [p.username, p.uuid, p.currentInstance ?? "", p.group ?? ""],
    defaultView: "table",
  },
)

// Virtualized grid: chunk filtered players into rows of 3 cards.
const CARD_HEIGHT = 168
const CARD_GAP = 16
const gridRows = computed(() => {
  const rows: Player[][] = []
  for (let i = 0; i < filteredPlayers.value.length; i += 3) {
    rows.push(filteredPlayers.value.slice(i, i + 3))
  }
  return rows
})
const { list: virtualGridRows, containerProps: gridContainerProps, wrapperProps: gridWrapperProps } = useVirtualList(
  gridRows,
  { itemHeight: CARD_HEIGHT + CARD_GAP, overscan: 5 },
)

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

// Detail sheet — journey + transfer
const sheetPlayer = ref<Player | null>(null)
const sheetOpen = computed({
  get: () => sheetPlayer.value !== null,
  set: (v) => { if (!v) sheetPlayer.value = null },
})
const journey = ref<PlayerJourneyEntry[]>([])
const journeyLoading = ref(false)

watch(sheetPlayer, async (p) => {
  if (!p) { journey.value = []; return }
  journeyLoading.value = true
  try {
    journey.value = await store.fetchJourney(p.id)
  } finally {
    journeyLoading.value = false
  }
})

const transferTarget = ref("")
const transferring = ref(false)

const transferOptions = computed(() =>
  instances.instances
    .filter(i => i.state === "RUNNING" && i.id !== sheetPlayer.value?.currentInstance)
    .map(i => ({ id: i.id, label: `${i.id} · ${i.group}` })),
)

async function submitTransfer() {
  if (!sheetPlayer.value || !transferTarget.value) return
  transferring.value = true
  try {
    await store.transfer(sheetPlayer.value.id, transferTarget.value)
    transferTarget.value = ""
    sheetPlayer.value = null
  } finally { transferring.value = false }
}

function journeyTone(t: PlayerJourneyEntry["type"]): StatusDotTone {
  if (t === "connected") return "success"
  if (t === "disconnected") return "muted"
  return "primary"
}
</script>

<template>
  <div
    class="flex min-h-[420px] flex-col gap-5 overflow-hidden"
    style="height: calc(100svh - 6.5rem)"
  >
    <PageHeader title="Players" description="Connected clients across the cluster." />

    <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
      <div class="rounded-xl border border-glass-border bg-glass/50 p-4">
        <p class="eyebrow mb-2">Online</p>
        <p class="text-2xl font-semibold tabular">{{ store.total || store.players.length }}</p>
      </div>
      <div class="rounded-xl border border-glass-border bg-glass/50 p-4">
        <p class="eyebrow mb-2">Avg ping</p>
        <p class="text-2xl font-semibold tabular">
          {{ store.players.length === 0
              ? '—'
              : Math.round(store.players.reduce((s, p) => s + (p.ping ?? 0), 0) / store.players.length) + ' ms' }}
        </p>
      </div>
      <div class="rounded-xl border border-glass-border bg-glass/50 p-4">
        <p class="eyebrow mb-2">Groups in use</p>
        <p class="text-2xl font-semibold tabular">{{ new Set(store.players.map(p => p.group).filter(Boolean)).size }}</p>
      </div>
    </div>

    <FilterToolbar
      v-model:search="search"
      :filters="[]"
      :active-filters="new Set(['ALL'])"
      search-placeholder="Search by username, UUID, instance, or group…"
      :view-mode="viewMode"
      @update:view-mode="viewMode = $event"
    />

    <LoadingSkeleton v-if="store.loading" mode="table" :count="6" />

    <EmptyState
      v-else-if="filteredPlayers.length === 0"
      :icon="UsersRound"
      :title="search ? 'No matches' : 'No players online'"
      :description="search ? 'Try clearing the filter or searching by another term.' : 'New connections will appear here as players join.'"
    />

    <template v-else-if="viewMode === 'grid'">
      <div
        v-if="filteredPlayers.length <= 30"
        class="grid min-h-0 flex-1 grid-cols-1 gap-4 overflow-auto styled-scrollbar pr-1 md:grid-cols-2 lg:grid-cols-3"
      >
        <PlayersPlayerCard
          v-for="p in filteredPlayers"
          :key="p.id"
          :player="p"
          @select="sheetPlayer = $event"
        />
      </div>
      <div v-else v-bind="gridContainerProps" class="min-h-0 flex-1 overflow-auto styled-scrollbar pr-1">
        <div v-bind="gridWrapperProps">
          <div
            v-for="{ data: row, index } in virtualGridRows"
            :key="index"
            class="grid grid-cols-3 gap-4"
            :style="{ height: `${CARD_HEIGHT}px`, marginBottom: `${CARD_GAP}px` }"
          >
            <PlayersPlayerCard
              v-for="p in row"
              :key="p.id"
              :player="p"
              @select="sheetPlayer = $event"
            />
          </div>
        </div>
      </div>
      <div
        v-if="store.truncated"
        class="shrink-0 rounded-xl border border-warning/30 bg-warning/5 px-4 py-2 text-xs text-warning"
      >
        Showing {{ store.players.length.toLocaleString() }} of {{ store.total.toLocaleString() }} online — list capped. Refine with search to find a specific player.
      </div>
    </template>

    <div v-else class="flex min-h-0 flex-1 flex-col overflow-hidden rounded-2xl border border-glass-border bg-glass/60 backdrop-blur-xl">
      <div class="flex h-10 shrink-0 items-center border-b border-glass-border px-4 eyebrow">
        <div class="w-64 shrink-0">Player</div>
        <div class="flex-1">UUID</div>
        <div class="w-44 shrink-0">Instance</div>
        <div class="w-32 shrink-0">Ping</div>
        <div class="w-32 shrink-0 text-right">Connected</div>
      </div>
      <VList
        v-slot="{ item: p }"
        :data="filteredPlayers"
        :item-size="56"
        :overscan="8"
        class="styled-scrollbar min-h-0 flex-1"
      >
        <div
          class="flex h-14 cursor-pointer select-none items-center border-b border-glass-border/50 px-4 transition-colors last:border-0 hover:bg-glass-hover"
          @click="sheetPlayer = p"
        >
          <div class="flex w-64 shrink-0 items-center gap-3">
            <Avatar class="size-8 bg-primary/15 text-primary">
              <AvatarFallback class="text-xs font-bold">{{ getInitials(p.username) }}</AvatarFallback>
            </Avatar>
            <span class="truncate text-sm font-medium">{{ p.username }}</span>
          </div>
          <div class="flex-1 truncate mono text-xs text-muted-foreground">{{ p.uuid }}</div>
          <NuxtLink :to="`/instances/${p.currentInstance}`" class="w-44 shrink-0 truncate text-sm text-primary mono hover:underline" @click.stop>
            {{ p.currentInstance ?? '—' }}
          </NuxtLink>
          <div class="w-32 shrink-0">
            <StatusBadge :tone="pingTone(p.ping)" :label="pingLabel(p.ping)" />
          </div>
          <div class="w-32 shrink-0 text-right text-sm text-muted-foreground tabular">
            {{ p.connectedAt ? timeAgo(p.connectedAt) : '—' }}
          </div>
        </div>
      </VList>
      <div
        v-if="store.truncated"
        class="shrink-0 border-t border-glass-border bg-warning/5 px-4 py-2 text-xs text-warning"
      >
        Showing {{ store.players.length.toLocaleString() }} of {{ store.total.toLocaleString() }} online — list capped. Refine with search to find a specific player.
      </div>
    </div>

    <DetailSheet
      :open="sheetOpen"
      :title="sheetPlayer?.username"
      eyebrow="Player"
      size="lg"
      @update:open="sheetOpen = $event"
    >
      <template v-if="sheetPlayer" #status>
        <StatusBadge :tone="pingTone(sheetPlayer.ping)" :label="pingLabel(sheetPlayer.ping)" />
      </template>

      <div v-if="sheetPlayer" class="space-y-5">
        <section class="space-y-2">
          <Eyebrow>Identity</Eyebrow>
          <div class="space-y-2 rounded-xl border border-glass-border bg-glass/40 p-3">
            <div class="flex items-center justify-between text-sm">
              <span class="text-muted-foreground">UUID</span>
              <span class="mono text-xs">{{ sheetPlayer.uuid }}</span>
            </div>
            <div class="flex items-center justify-between text-sm">
              <span class="text-muted-foreground">Current instance</span>
              <NuxtLink :to="`/instances/${sheetPlayer.currentInstance}`" class="mono text-primary hover:underline">{{ sheetPlayer.currentInstance ?? '—' }}</NuxtLink>
            </div>
            <div class="flex items-center justify-between text-sm">
              <span class="text-muted-foreground">Group</span>
              <span class="mono">{{ sheetPlayer.group ?? '—' }}</span>
            </div>
            <div class="flex items-center justify-between text-sm">
              <span class="text-muted-foreground">Connected</span>
              <span class="tabular">{{ sheetPlayer.connectedAt ? timeAgo(sheetPlayer.connectedAt) : '—' }}</span>
            </div>
          </div>
        </section>

        <section class="space-y-2">
          <Eyebrow>Transfer</Eyebrow>
          <div class="flex items-end gap-2">
            <div class="flex-1 space-y-1.5">
              <Label for="player-transfer">Target instance</Label>
              <Select v-model="transferTarget">
                <SelectTrigger id="player-transfer">
                  <SelectValue placeholder="Pick a running instance…" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem v-for="o in transferOptions" :key="o.id" :value="o.id">{{ o.label }}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <Button :disabled="!transferTarget || transferring" @click="submitTransfer">
              <ArrowRightLeft class="mr-1.5 size-3.5" />
              {{ transferring ? 'Transferring…' : 'Transfer' }}
            </Button>
          </div>
          <p class="text-xs text-muted-foreground">Only RUNNING instances are listed. The player keeps their session.</p>
        </section>

        <section class="space-y-2">
          <Eyebrow>Journey</Eyebrow>
          <p v-if="journeyLoading" class="text-sm text-muted-foreground">Loading…</p>
          <div v-else-if="journey.length === 0" class="rounded-xl border border-dashed border-glass-border bg-glass/30 px-4 py-6 text-center text-sm text-muted-foreground">
            No movement history.
          </div>
          <ol v-else class="space-y-2.5 border-l border-glass-border pl-5">
            <li v-for="(j, i) in journey" :key="i" class="relative">
              <span class="absolute -left-[27px] top-1.5">
                <span class="block size-2 rounded-full" :class="`bg-${journeyTone(j.type) === 'success' ? 'success' : journeyTone(j.type) === 'primary' ? 'primary' : 'muted-foreground'}`" />
              </span>
              <div class="flex items-center justify-between text-sm">
                <span class="font-medium">
                  <template v-if="j.type === 'connected'">Joined <span class="mono text-primary">{{ j.toInstance }}</span></template>
                  <template v-else-if="j.type === 'transferred'">Moved <span class="mono">{{ j.fromInstance }}</span> → <span class="mono text-primary">{{ j.toInstance }}</span></template>
                  <template v-else>Disconnected from <span class="mono">{{ j.fromInstance }}</span></template>
                </span>
                <span class="tabular text-xs text-muted-foreground">{{ timeAgo(j.ts) }}</span>
              </div>
            </li>
          </ol>
        </section>
      </div>
    </DetailSheet>
  </div>
</template>
