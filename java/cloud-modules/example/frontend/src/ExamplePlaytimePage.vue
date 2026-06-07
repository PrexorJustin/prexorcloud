<!--
  Dashboard page for the example module.

  Two halves of the module-sdk surface every page needs:

    1. HTTP. `useApiClient()` returns the dashboard's openapi-fetch client —
       baseUrl + auth middleware baked in. Dashboard routes are statically
       typed from the core OpenAPI spec; module-owned routes are NOT, so we
       reach them by casting the path argument. This is the honest shape of
       a "call one of MY module's endpoints" request against an untyped
       path. We hit `/api/v1/modules/example-playtime/top`, which will be
       mounted once the platform-route API is wired.

    2. Realtime updates. `useModuleEvents([...], handler)` hooks the shared
       SSE bus and fans out events by type. We subscribe to
       `PLAYTIME:TOP_UPDATED`, which the backend bridge can publish after a
       rebuild of the top-N table.

  The page is intentionally minimal: one Card, one Table, one refresh button.
  The richer shadcn-vue primitives are all available from the same import.
-->
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
  onMounted,
  ref,
  useApiClient,
  useModuleEvents,
} from '@prexorcloud/module-sdk'

interface TopEntry {
  playerId: string
  playerName: string
  totalMs: number
}

interface TopResponse {
  entries: TopEntry[]
  generatedAt: string
}

const api = useApiClient()

const entries = ref<TopEntry[]>([])
const loading = ref(false)
const lastUpdated = ref<string | null>(null)
const error = ref<string | null>(null)

async function refresh() {
  loading.value = true
  error.value = null
  try {
    // Module routes live outside the core OpenAPI spec; cast the client
    // to escape path-level typing while keeping auth + baseUrl middleware.
    const { data, error: apiError } = await (api as unknown as {
      GET: (path: string) => Promise<{ data?: TopResponse; error?: unknown }>
    }).GET('/api/v1/modules/example-playtime/top')
    if (apiError) throw new Error(String(apiError))
    entries.value = data?.entries ?? []
    lastUpdated.value = data?.generatedAt ?? new Date().toISOString()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function formatHours(ms: number): string {
  return `${(ms / 3_600_000).toFixed(1)} h`
}

// Realtime refresh. Re-pull the top table on every
// PLAYTIME:TOP_UPDATED event rather than trusting the payload, so the
// displayed order matches whatever rebuildTotals() just committed.
useModuleEvents(['PLAYTIME:TOP_UPDATED'], () => {
  refresh()
})

onMounted(refresh)
</script>

<template>
  <div class="space-y-4 p-6">
    <PageHeader title="Playtime" description="Top players by total session time across the cluster.">
      <template #actions>
        <Button :disabled="loading" @click="refresh">
          {{ loading ? 'Refreshing…' : 'Refresh' }}
        </Button>
      </template>
    </PageHeader>

    <Card>
      <CardHeader>
        <CardTitle>Leaderboard</CardTitle>
      </CardHeader>
      <CardContent>
        <div v-if="error" class="text-sm text-destructive">{{ error }}</div>
        <Table v-else>
          <TableHeader>
            <TableRow>
              <TableHead class="w-12">#</TableHead>
              <TableHead>Player</TableHead>
              <TableHead class="text-right">Total</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="(entry, idx) in entries" :key="entry.playerId">
              <TableCell class="text-muted-foreground">{{ idx + 1 }}</TableCell>
              <TableCell>{{ entry.playerName }}</TableCell>
              <TableCell class="text-right font-mono">{{ formatHours(entry.totalMs) }}</TableCell>
            </TableRow>
            <TableRow v-if="!entries.length">
              <TableCell colspan="3" class="text-center text-muted-foreground">
                No sessions recorded yet.
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
        <p v-if="lastUpdated" class="mt-4 text-xs text-muted-foreground">
          Last updated {{ new Date(lastUpdated).toLocaleTimeString() }}
        </p>
      </CardContent>
    </Card>
  </div>
</template>
