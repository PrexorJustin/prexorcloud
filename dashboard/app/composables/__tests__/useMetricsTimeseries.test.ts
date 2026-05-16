import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { useMetricsTimeseries, type TimeseriesResponse } from '../useMetricsTimeseries'

const mockGET = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: vi.fn(), PUT: vi.fn(), PATCH: vi.fn(), DELETE: vi.fn() }),
}))

const sample: TimeseriesResponse = {
  windowMs: 60_000 * 60,
  bucketWidthMs: 60_000,
  buckets: 60,
  startedAtMs: 1_700_000_000_000,
  series: {
    cpu: [10, null, 20, null, 30],
    mem: [1, 2, 3, 4, 5],
  },
}

beforeEach(() => {
  vi.useFakeTimers()
  mockGET.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useMetricsTimeseries', () => {
  it('starts unloaded with empty series', () => {
    mockGET.mockResolvedValue({ data: sample })
    const m = useMetricsTimeseries({ scope: { kind: 'overview' } })
    expect(m.loaded.value).toBe(false)
    expect(m.series).toEqual({})
    expect(m.seriesRaw).toEqual({})
    expect(m.meta.value).toBeNull()
  })

  it('overview scope hits the overview path with default window/buckets', async () => {
    mockGET.mockResolvedValue({ data: sample })
    const m = useMetricsTimeseries({ scope: { kind: 'overview' } })
    await m.refresh()
    expect(mockGET).toHaveBeenCalledWith('/api/v1/overview/timeseries', {
      params: { query: { window: '1h', buckets: 60 } },
    })
    expect(m.loaded.value).toBe(true)
  })

  it('overview scope honours custom window and bucket count', async () => {
    mockGET.mockResolvedValue({ data: sample })
    const m = useMetricsTimeseries({ scope: { kind: 'overview' }, window: '6h', buckets: 30 })
    await m.refresh()
    expect(mockGET).toHaveBeenCalledWith('/api/v1/overview/timeseries', {
      params: { query: { window: '6h', buckets: 30 } },
    })
  })

  it('instance scope encodes the id into path params', async () => {
    mockGET.mockResolvedValue({ data: sample })
    const m = useMetricsTimeseries({ scope: { kind: 'instance', id: 'lobby-1' } })
    await m.refresh()
    expect(mockGET).toHaveBeenCalledWith('/api/v1/services/{id}/metrics/timeseries', {
      params: { path: { id: 'lobby-1' }, query: { window: '1h', buckets: 60 } },
    })
  })

  it('node scope encodes the id into path params', async () => {
    mockGET.mockResolvedValue({ data: sample })
    const m = useMetricsTimeseries({ scope: { kind: 'node', id: 'node-a' } })
    await m.refresh()
    expect(mockGET).toHaveBeenCalledWith('/api/v1/nodes/{id}/metrics/timeseries', {
      params: { path: { id: 'node-a' }, query: { window: '1h', buckets: 60 } },
    })
  })

  it('maps null buckets to 0 in `series` while keeping raw nulls in `seriesRaw`', async () => {
    mockGET.mockResolvedValue({ data: sample })
    const m = useMetricsTimeseries({ scope: { kind: 'overview' } })
    await m.refresh()
    expect(m.series.cpu).toEqual([10, 0, 20, 0, 30])
    expect(m.seriesRaw.cpu).toEqual([10, null, 20, null, 30])
    expect(m.series.mem).toEqual([1, 2, 3, 4, 5])
  })

  it('exposes meta from the latest response', async () => {
    mockGET.mockResolvedValue({ data: sample })
    const m = useMetricsTimeseries({ scope: { kind: 'overview' } })
    await m.refresh()
    expect(m.meta.value).toEqual({
      windowMs: sample.windowMs,
      bucketWidthMs: sample.bucketWidthMs,
      startedAtMs: sample.startedAtMs,
    })
  })

  it('keeps the prior series when the next refresh fails', async () => {
    mockGET.mockResolvedValueOnce({ data: sample })
    const m = useMetricsTimeseries({ scope: { kind: 'overview' } })
    await m.refresh()
    const snapshotCpu = [...m.series.cpu!]

    mockGET.mockRejectedValueOnce(new Error('boom'))
    await m.refresh()
    expect(m.series.cpu).toEqual(snapshotCpu)
    // loaded stays true once it has loaded at least once
    expect(m.loaded.value).toBe(true)
  })

  it('handles a response with no series keys', async () => {
    mockGET.mockResolvedValue({
      data: { ...sample, series: {} },
    })
    const m = useMetricsTimeseries({ scope: { kind: 'overview' } })
    await m.refresh()
    expect(Object.keys(m.series)).toEqual([])
    expect(m.loaded.value).toBe(true)
  })
})
