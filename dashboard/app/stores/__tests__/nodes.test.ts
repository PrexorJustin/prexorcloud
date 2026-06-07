import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import type { CloudEvent } from '~/types/events'

import { toast } from 'vue-sonner'
import { useNodesStore } from '../nodes'

const mockGET = vi.fn()
const handlers = new Map<string, (event: CloudEvent) => void>()
const mockOn = vi.fn((types: string | string[], handler: (event: CloudEvent) => void) => {
  for (const type of Array.isArray(types) ? types : [types]) handlers.set(type, handler)
})

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
    off: vi.fn(),
    connect: vi.fn(),
    disconnect: vi.fn(),
    connected: { value: false },
  }),
}))

function emit(event: CloudEvent) {
  handlers.get(event.type)?.(event)
}

describe('useNodesStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    mockGET.mockReset()
    mockOn.mockClear()
    handlers.clear()
    vi.mocked(toast.error).mockReset()
  })

  it('starts with empty nodes and not loading', () => {
    const store = useNodesStore()
    expect(store.nodes).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchNodes populates nodes on success', async () => {
    const mockNodes = [
      { id: 'node-1', type: 'CONNECTED', status: 'READY', cpuUsage: 0.5 },
      { id: 'node-2', type: 'DISCONNECTED', status: 'OFFLINE' },
    ]
    mockGET.mockResolvedValueOnce({ data: { data: mockNodes } })

    const store = useNodesStore()
    await store.fetchNodes()

    expect(store.nodes).toEqual(mockNodes)
    expect(store.loading).toBe(false)
  })

  it('fetchNodes shows error toast on failure', async () => {
    mockGET.mockRejectedValueOnce({ statusCode: 500 })

    const store = useNodesStore()
    await store.fetchNodes()

    expect(store.nodes).toEqual([])
    expect(toast.error).toHaveBeenCalledWith('Failed to load nodes')
  })

  it('sets loading=true during fetch', async () => {
    let resolvePromise: (v: any) => void
    mockGET.mockImplementation(() => new Promise(r => { resolvePromise = r }))

    const store = useNodesStore()
    const promise = store.fetchNodes()
    expect(store.loading).toBe(true)

    resolvePromise!({ data: { data: [] } })
    await promise
    expect(store.loading).toBe(false)
  })

  it('tracks stale set and flips node status across HEARTBEAT_STALE / HEARTBEAT_RESUMED', async () => {
    mockGET.mockResolvedValueOnce({
      data: {
        data: [
          {
            id: 'node-1',
            type: 'CONNECTED',
            status: 'ONLINE',
            cpuUsage: 0.1,
            usedMemoryMb: 100,
            totalMemoryMb: 1024,
            lastHeartbeat: '2026-05-11T00:00:00Z',
          },
        ],
      },
    })

    const store = useNodesStore()
    await store.fetchNodes()
    store.connectSse()

    emit({
      type: 'NODE_HEARTBEAT_STALE',
      timestamp: '2026-05-11T00:00:30Z',
      nodeId: 'node-1',
      missedPongs: 3,
      lastHeartbeatAt: '2026-05-11T00:00:00Z',
    })
    expect(store.staleNodeIds.has('node-1')).toBe(true)
    expect((store.nodes[0] as any).status).toBe('UNREACHABLE')

    emit({
      type: 'NODE_HEARTBEAT_RESUMED',
      timestamp: '2026-05-11T00:01:00Z',
      nodeId: 'node-1',
      lastHeartbeatAt: '2026-05-11T00:01:00Z',
    })
    expect(store.staleNodeIds.has('node-1')).toBe(false)
    expect((store.nodes[0] as any).status).toBe('ONLINE')
    expect((store.nodes[0] as any).lastHeartbeat).toBe('2026-05-11T00:01:00Z')
  })

  it('NODE_STATUS event updates lastHeartbeat when carried', async () => {
    mockGET.mockResolvedValueOnce({
      data: {
        data: [
          {
            id: 'node-1',
            type: 'CONNECTED',
            status: 'ONLINE',
            cpuUsage: 0.1,
            usedMemoryMb: 100,
            totalMemoryMb: 1024,
            lastHeartbeat: '2026-05-11T00:00:00Z',
          },
        ],
      },
    })

    const store = useNodesStore()
    await store.fetchNodes()
    store.connectSse()

    emit({
      type: 'NODE_STATUS',
      timestamp: '2026-05-11T00:00:15Z',
      nodeId: 'node-1',
      cpuUsage: 0.5,
      usedMemoryMb: 256,
      totalMemoryMb: 1024,
      lastHeartbeatAt: '2026-05-11T00:00:15Z',
    })
    expect((store.nodes[0] as any).cpuUsage).toBe(0.5)
    expect((store.nodes[0] as any).lastHeartbeat).toBe('2026-05-11T00:00:15Z')
  })
})
