import { defineStore } from 'pinia'
import type { CloudEvent } from '~/types/events'
import { toast } from 'vue-sonner'
import { t } from '~/lib/translate'

const HISTORY_LEN = 60
const MIN_TICK_MS = 5000

export const useOverviewStore = defineStore('overview', () => {
  const stats = ref<Awaited<ReturnType<typeof fetchRaw>> | null>(null)
  const loading = ref(false)

  // Recent-history ring buffers. Pre-seeded from
  // /api/v1/overview/timeseries on first load (server-side ring,
  // SAMPLE_INTERVAL=15s, 1h window → 60 buckets matching HISTORY_LEN), then
  // appended client-side as SSE events drive fetchOverview() ticks. The server
  // doesn't yet track `groupCount` as a series, so it remains client-side.
  const history = reactive({
    nodeCount: [] as number[],
    playerCount: [] as number[],
    instanceCount: [] as number[],
    groupCount: [] as number[],
  })
  let lastTick = 0
  let historySeeded = false

  function pushHistory(s: NonNullable<typeof stats.value>) {
    const now = Date.now()
    if (now - lastTick < MIN_TICK_MS) return
    lastTick = now
    push(history.nodeCount,     s.nodeCount     ?? 0)
    push(history.playerCount,   s.playerCount   ?? 0)
    push(history.instanceCount, s.instanceCount ?? 0)
    push(history.groupCount,    s.groupCount    ?? 0)
  }

  function push(arr: number[], v: number) {
    arr.push(v)
    if (arr.length > HISTORY_LEN) arr.shift()
  }

  async function fetchRaw() {
    const { data } = await useApiClient().GET('/api/v1/overview')
    return data!
  }

  async function seedHistory() {
    if (historySeeded) return
    historySeeded = true
    try {
      const { data } = await useApiClient().GET('/api/v1/overview/timeseries', {
        params: { query: { window: '1h', buckets: HISTORY_LEN } },
      })
      const series = (data as { series?: Record<string, Array<number | null>> } | null)?.series
      if (!series) return
      const apply = (key: keyof typeof history, src: Array<number | null> | undefined) => {
        if (!src) return
        history[key].splice(0, history[key].length, ...src.map(v => v == null ? 0 : v))
      }
      apply('playerCount', series.players)
      apply('instanceCount', series.instances)
      apply('nodeCount', series.onlineNodes)
    } catch {
      historySeeded = false
    }
  }

  async function fetchOverview() {
    loading.value = true
    try {
      const next = await fetchRaw()
      stats.value = next
      if (!historySeeded) await seedHistory()
      pushHistory(next)
    }
    catch { toast.error(t("store.overview.loadFailed")) }
    finally {
      loading.value = false
    }
  }

  let sseConnected = false

  function handleEvent(_data: CloudEvent) {
    fetchOverview()
  }

  function connectSse() {
    if (sseConnected) return
    sseConnected = true
    const bus = useSseEventBus()
    bus.on([
      'NODE_CONNECTED', 'NODE_DISCONNECTED',
      'INSTANCE_STATE_CHANGED', 'INSTANCE_STARTED', 'INSTANCE_STOPPED',
      'PLAYER_CONNECTED', 'PLAYER_DISCONNECTED',
      'GROUP_CREATED', 'GROUP_DELETED',
    ], handleEvent)
    bus.connect()
  }

  function disconnectSse() {
    if (!sseConnected) return
    sseConnected = false
    const bus = useSseEventBus()
    bus.off([
      'NODE_CONNECTED', 'NODE_DISCONNECTED',
      'INSTANCE_STATE_CHANGED', 'INSTANCE_STARTED', 'INSTANCE_STOPPED',
      'PLAYER_CONNECTED', 'PLAYER_DISCONNECTED',
      'GROUP_CREATED', 'GROUP_DELETED',
    ], handleEvent)
  }

  return { stats, loading, history, fetchOverview, connectSse, disconnectSse }
})
