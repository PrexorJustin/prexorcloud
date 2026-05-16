<script setup lang="ts">
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  PageHeader,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  computed,
  onMounted,
  ref,
  useApiClient,
  useRoute,
} from '@prexorcloud/module-sdk'

interface PlayerStat {
  playerId: string
  playerName: string
  totalMs: number
  sessionCount: number
  firstSeen: string | null
  lastSeen: string | null
}

interface SessionRecord {
  playerId: string
  playerName: string
  sessionId: string
  group: string
  instanceId: string
  joinAt: string
  quitAt: string | null
  durationMs: number
}

interface JourneyEntry {
  playerUuid: string
  playerName: string
  eventType: string
  fromInstanceId: string
  toInstanceId: string
  group: string
  timestamp: string
}

interface Detail {
  stat: PlayerStat | null
  recentSessions: SessionRecord[]
  recentJourney: JourneyEntry[]
}

const route = useRoute()
const api = useApiClient() as unknown as {
  GET: (path: string) => Promise<{ data?: unknown; error?: unknown }>
}

const detail = ref<Detail | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const uuid = computed(() => String(route.params.uuid ?? ''))

function formatMs(ms: number): string {
  if (ms < 60_000) return `${(ms / 1000).toFixed(0)} s`
  if (ms < 3_600_000) return `${(ms / 60_000).toFixed(1)} m`
  return `${(ms / 3_600_000).toFixed(2)} h`
}

async function load() {
  if (!uuid.value) return
  loading.value = true
  error.value = null
  try {
    const { data, error: apiError } = await api.GET(`/api/v1/modules/stats-aggregator/players/${uuid.value}`)
    if (apiError) throw new Error(String(apiError))
    detail.value = (data as Detail | undefined) ?? null
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="space-y-4 p-6">
    <PageHeader
      :title="detail?.stat?.playerName || uuid"
      :description="`Stats for player ${uuid}`"
    />

    <div v-if="error" class="text-sm text-destructive">{{ error }}</div>
    <div v-else-if="loading" class="text-sm text-muted-foreground">Loading…</div>

    <Card v-if="detail?.stat">
      <CardHeader>
        <CardTitle>Summary</CardTitle>
      </CardHeader>
      <CardContent class="grid grid-cols-2 gap-4 text-sm md:grid-cols-4">
        <div>
          <div class="text-muted-foreground">Total playtime</div>
          <div class="font-mono">{{ formatMs(detail.stat.totalMs) }}</div>
        </div>
        <div>
          <div class="text-muted-foreground">Sessions</div>
          <div class="font-mono">{{ detail.stat.sessionCount }}</div>
        </div>
        <div>
          <div class="text-muted-foreground">First seen</div>
          <div class="font-mono">{{ detail.stat.firstSeen ?? '—' }}</div>
        </div>
        <div>
          <div class="text-muted-foreground">Last seen</div>
          <div class="font-mono">{{ detail.stat.lastSeen ?? '—' }}</div>
        </div>
      </CardContent>
    </Card>

    <Card v-if="detail">
      <CardHeader>
        <CardTitle>Recent sessions</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Joined</TableHead>
              <TableHead>Group</TableHead>
              <TableHead>Instance</TableHead>
              <TableHead class="text-right">Duration</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="s in detail.recentSessions" :key="s.sessionId">
              <TableCell class="font-mono">{{ s.joinAt }}</TableCell>
              <TableCell>{{ s.group || '—' }}</TableCell>
              <TableCell class="font-mono">{{ s.instanceId || '—' }}</TableCell>
              <TableCell class="text-right font-mono">
                {{ s.quitAt ? formatMs(s.durationMs) : 'open' }}
              </TableCell>
            </TableRow>
            <TableRow v-if="!detail.recentSessions.length">
              <TableCell colspan="4" class="text-center text-muted-foreground">
                No sessions yet.
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </CardContent>
    </Card>

    <Card v-if="detail">
      <CardHeader>
        <CardTitle>Journey timeline</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>When</TableHead>
              <TableHead>Event</TableHead>
              <TableHead>Group</TableHead>
              <TableHead>From</TableHead>
              <TableHead>To</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="(j, idx) in detail.recentJourney" :key="`${j.timestamp}-${idx}`">
              <TableCell class="font-mono">{{ j.timestamp }}</TableCell>
              <TableCell>{{ j.eventType }}</TableCell>
              <TableCell>{{ j.group || '—' }}</TableCell>
              <TableCell class="font-mono">{{ j.fromInstanceId || '—' }}</TableCell>
              <TableCell class="font-mono">{{ j.toInstanceId || '—' }}</TableCell>
            </TableRow>
            <TableRow v-if="!detail.recentJourney.length">
              <TableCell colspan="5" class="text-center text-muted-foreground">
                No journey events recorded.
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  </div>
</template>
