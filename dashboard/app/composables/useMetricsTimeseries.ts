import { onMounted, onUnmounted, reactive, ref } from 'vue'

export type TimeseriesWindow = '15m' | '1h' | '6h' | '24h'

export type TimeseriesResponse = {
  windowMs: number
  bucketWidthMs: number
  buckets: number
  startedAtMs: number
  series: Record<string, Array<number | null>>
}

type Scope =
  | { kind: 'overview' }
  | { kind: 'instance', id: string }
  | { kind: 'node', id: string }

export interface UseMetricsTimeseriesOptions {
  scope: Scope
  window?: TimeseriesWindow
  buckets?: number
  refreshMs?: number
}

/**
 * Polls a `/timeseries` endpoint and exposes the latest snapshot. Empty
 * buckets returned by the server (null) are mapped to 0 to keep the sparkline
 * line continuous; charts that care about gaps can opt out by using
 * `seriesRaw`.
 */
export function useMetricsTimeseries(opts: UseMetricsTimeseriesOptions) {
  const win: TimeseriesWindow = opts.window ?? '1h'
  const buckets = opts.buckets ?? 60
  const refreshMs = opts.refreshMs ?? 30_000

  const series = reactive<Record<string, number[]>>({})
  const seriesRaw = reactive<Record<string, Array<number | null>>>({})
  const loaded = ref(false)
  const meta = ref<{ windowMs: number, bucketWidthMs: number, startedAtMs: number } | null>(null)

  let timer: ReturnType<typeof setInterval> | null = null

  async function fetchOnce(): Promise<void> {
    const client = useApiClient()
    try {
      let body: TimeseriesResponse
      if (opts.scope.kind === 'overview') {
        const { data } = await client.GET('/api/v1/overview/timeseries', { params: { query: { window: win, buckets } } })
        body = data as TimeseriesResponse
      } else if (opts.scope.kind === 'instance') {
        const { data } = await client.GET('/api/v1/services/{id}/metrics/timeseries', {
          params: { path: { id: opts.scope.id }, query: { window: win, buckets } },
        })
        body = data as TimeseriesResponse
      } else {
        const { data } = await client.GET('/api/v1/nodes/{id}/metrics/timeseries', {
          params: { path: { id: opts.scope.id }, query: { window: win, buckets } },
        })
        body = data as TimeseriesResponse
      }
      meta.value = { windowMs: body.windowMs, bucketWidthMs: body.bucketWidthMs, startedAtMs: body.startedAtMs }
      for (const k of Object.keys(body.series)) {
        const raw = body.series[k] ?? []
        seriesRaw[k] = raw
        series[k] = raw.map(v => (v == null ? 0 : v))
      }
      loaded.value = true
    } catch {
      // leave previous series in place
    }
  }

  onMounted(() => {
    fetchOnce()
    timer = setInterval(fetchOnce, refreshMs)
  })

  onUnmounted(() => {
    if (timer) clearInterval(timer)
    timer = null
  })

  return { series, seriesRaw, meta, loaded, refresh: fetchOnce }
}
