import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import type { CloudEvent } from '~/types/events'

import { useInstancesStore } from '../instances'

const mockGET = vi.fn()
const handlers = new Map<string, (event: CloudEvent) => void>()
const mockOn = vi.fn((types: string | string[], handler: (event: CloudEvent) => void) => {
  for (const type of Array.isArray(types) ? types : [types]) handlers.set(type, handler)
})
const mockOff = vi.fn()

vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: vi.fn(), DELETE: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
}))

vi.mock('~/composables/useSseEventBus', () => ({
  useSseEventBus: () => ({
    on: mockOn,
    off: mockOff,
    connect: vi.fn(),
    disconnect: vi.fn(),
    connected: { value: false },
  }),
}))

function emit(event: CloudEvent) {
  handlers.get(event.type)?.(event)
}

describe('useInstancesStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    handlers.clear()
    mockGET.mockReset()
    mockOn.mockClear()
    mockOff.mockClear()
  })

  it('applies targeted SSE deltas for state, metrics, and player counts', async () => {
    mockGET.mockResolvedValueOnce({
      data: {
        data: [
          { id: 'lobby-1', group: 'lobby', state: 'RUNNING', playerCount: 4 },
        ],
      },
    })

    const store = useInstancesStore()
    await store.fetchInstances()
    store.connectSse()

    emit({
      type: 'INSTANCE_STATE_CHANGED',
      timestamp: '2026-04-22T00:00:00Z',
      instanceId: 'lobby-1',
      group: 'lobby',
      nodeId: 'node-1',
      oldState: 'RUNNING',
      newState: 'DRAINING',
    })
    emit({
      type: 'INSTANCE_METRICS',
      timestamp: '2026-04-22T00:00:01Z',
      instanceId: 'lobby-1',
      group: 'lobby',
      tps1m: 20,
      tps5m: 20,
      tps15m: 20,
      msptAvg: 5,
      heapUsedMb: 256,
      heapMaxMb: 1024,
      gcCollections: 3,
      gcTimeMs: 17,
      threadCount: 32,
      playerCount: 8,
      maxPlayers: 100,
      worldCount: 1,
      totalEntities: 10,
      totalChunks: 20,
      worlds: [{ name: 'world', environment: 'NORMAL', entityCount: 10, chunkCount: 20, playerCount: 8 }],
      serverVersion: 'Paper 1.21.4',
      pluginCount: 5,
    })
    emit({
      type: 'PLAYER_CONNECTED',
      timestamp: '2026-04-22T00:00:02Z',
      uuid: '00000000-0000-0000-0000-000000000001',
      name: 'Steve',
      instanceId: 'lobby-1',
      group: 'lobby',
    })
    emit({
      type: 'PLAYER_DISCONNECTED',
      timestamp: '2026-04-22T00:00:03Z',
      uuid: '00000000-0000-0000-0000-000000000001',
      name: 'Steve',
      instanceId: 'lobby-1',
      group: 'lobby',
    })

    expect(store.instances[0]!.state).toBe('DRAINING')
    expect(store.instances[0]!.playerCount).toBe(8)
    expect(mockGET).toHaveBeenCalledTimes(1)
    expect(store.lastInstanceMetrics?.instanceId).toBe('lobby-1')
    expect(store.lastInstanceMetrics?.serverVersion).toBe('Paper 1.21.4')
    expect(store.lastInstanceMetrics?.worlds.length).toBe(1)
  })
})
