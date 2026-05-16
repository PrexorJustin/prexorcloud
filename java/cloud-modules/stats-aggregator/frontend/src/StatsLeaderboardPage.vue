<script setup lang="ts">
import {
  Button,
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
} from '@prexorcloud/module-sdk'

interface PlayerStat {
  playerId: string
  playerName: string
  totalMs: number
  sessionCount: number
  firstSeen: string | null
  lastSeen: string | null
}

interface GroupStat {
  group: string
  totalMs: number
  sessionCount: number
  uniquePlayers: number
  updatedAt: string | null
}

interface TopPlayersResponse { count: number; players: PlayerStat[] }
interface TopGroupsResponse { count: number; groups: GroupStat[] }

const api = useApiClient() as unknown as {
  GET: (path: string) => Promise<{ data?: unknown; error?: unknown }>
  POST: (path: string) => Promise<{ data?: unknown; error?: unknown }>
}

const players = ref<PlayerStat[]>([])
const groups = ref<GroupStat[]>([])
const loading = ref(false)
const rebuilding = ref(false)
const error = ref<string | null>(null)

const totalPlaytimeMs = computed(() => players.value.reduce((acc, p) => acc + p.totalMs, 0))

function formatHours(ms: number): string {
  return `${(ms / 3_600_000).toFixed(1)} h`
}

function shortId(id: string): string {
  return id.slice(0, 8)
}

async function refresh() {
  loading.value = true
  error.value = null
  try {
    const [p, g] = await Promise.all([
      api.GET('/api/v1/modules/stats-aggregator/players/top'),
      api.GET('/api/v1/modules/stats-aggregator/groups/top'),
    ])
    if (p.error) throw new Error(String(p.error))
    if (g.error) throw new Error(String(g.error))
    players.value = (p.data as TopPlayersResponse | undefined)?.players ?? []
    groups.value = (g.data as TopGroupsResponse | undefined)?.groups ?? []
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function rebuild() {
  rebuilding.value = true
  try {
    await api.POST('/api/v1/modules/stats-aggregator/aggregates/rebuild')
    await refresh()
  } finally {
    rebuilding.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <div class="space-y-4 p-6">
    <PageHeader title="Stats" description="Playtime, sessions, and group leaderboards aggregated from the Player Journey Bus.">
      <template #actions>
        <Button :disabled="rebuilding" variant="outline" @click="rebuild">
          {{ rebuilding ? 'Rebuilding…' : 'Rebuild aggregates' }}
        </Button>
        <Button :disabled="loading" @click="refresh">
          {{ loading ? 'Refreshing…' : 'Refresh' }}
        </Button>
      </template>
    </PageHeader>

    <div v-if="error" class="text-sm text-destructive">{{ error }}</div>

    <Card>
      <CardHeader>
        <CardTitle>Top players</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead class="w-12">#</TableHead>
              <TableHead>Player</TableHead>
              <TableHead class="text-right">Sessions</TableHead>
              <TableHead class="text-right">Total</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="(p, idx) in players" :key="p.playerId">
              <TableCell class="text-muted-foreground">{{ idx + 1 }}</TableCell>
              <TableCell>
                <a :href="`/modules/stats-aggregator/players/${p.playerId}`" class="hover:underline">
                  {{ p.playerName || shortId(p.playerId) }}
                </a>
              </TableCell>
              <TableCell class="text-right font-mono">{{ p.sessionCount }}</TableCell>
              <TableCell class="text-right font-mono">{{ formatHours(p.totalMs) }}</TableCell>
            </TableRow>
            <TableRow v-if="!players.length">
              <TableCell colspan="4" class="text-center text-muted-foreground">
                No sessions recorded yet.
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
        <p class="mt-4 text-xs text-muted-foreground">
          Total tracked playtime: {{ formatHours(totalPlaytimeMs) }}
        </p>
      </CardContent>
    </Card>

    <Card>
      <CardHeader>
        <CardTitle>Top groups</CardTitle>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead class="w-12">#</TableHead>
              <TableHead>Group</TableHead>
              <TableHead class="text-right">Players</TableHead>
              <TableHead class="text-right">Sessions</TableHead>
              <TableHead class="text-right">Total</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="(g, idx) in groups" :key="g.group">
              <TableCell class="text-muted-foreground">{{ idx + 1 }}</TableCell>
              <TableCell>{{ g.group }}</TableCell>
              <TableCell class="text-right font-mono">{{ g.uniquePlayers }}</TableCell>
              <TableCell class="text-right font-mono">{{ g.sessionCount }}</TableCell>
              <TableCell class="text-right font-mono">{{ formatHours(g.totalMs) }}</TableCell>
            </TableRow>
            <TableRow v-if="!groups.length">
              <TableCell colspan="5" class="text-center text-muted-foreground">
                No group activity yet.
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  </div>
</template>
