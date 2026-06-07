import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// Mock EventSource
class MockEventSource {
  static instances: MockEventSource[] = []
  url: string
  onmessage: ((event: { data: string; lastEventId: string }) => void) | null = null
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  closed = false

  constructor(url: string) {
    this.url = url
    MockEventSource.instances.push(this)
  }

  close() {
    this.closed = true
  }

  // Test helper: simulate a message
  _emit(data: object, lastEventId = '') {
    this.onmessage?.({ data: JSON.stringify(data), lastEventId })
  }
}

// Mock $fetch for ticket endpoint
const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)
vi.stubGlobal('EventSource', MockEventSource)
vi.stubGlobal('useRuntimeConfig', () => ({
  public: { apiBase: 'http://localhost:8080' },
}))

// We test the singleton state logic directly since useSseEventBus
// relies on window.__sseEventBus
describe('useSseEventBus', () => {
  beforeEach(() => {
    localStorage.clear()
    MockEventSource.instances = []
    mockFetch.mockReset()
    // Reset singleton
    delete (window as any).__sseEventBus
  })

  afterEach(() => {
    delete (window as any).__sseEventBus
  })

  // We need to dynamically import to get fresh singleton state per test
  async function createBus() {
    // Manually re-create the singleton by clearing the cached one
    delete (window as any).__sseEventBus
    const { useSseEventBus } = await import('../useSseEventBus')
    return useSseEventBus()
  }

  function firstEventSource() {
    const es = MockEventSource.instances[0]
    expect(es).toBeDefined()
    if (!es) throw new Error('EventSource was not created')
    return es
  }

  it('registers and invokes event handlers', async () => {
    const bus = await createBus()
    const handler = vi.fn()
    bus.on('INSTANCE_STARTED', handler)

    // Simulate: connect + receive event
    localStorage.setItem('auth_token', 'token')
    mockFetch.mockResolvedValue({ ticket: 'abc' })
    await bus.connect()

    const es = firstEventSource()
    es._emit({ type: 'INSTANCE_STARTED', instanceId: 'lobby-1' })

    expect(handler).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'INSTANCE_STARTED', instanceId: 'lobby-1' }),
    )
  })

  it('supports wildcard handlers', async () => {
    const bus = await createBus()
    const wildcardHandler = vi.fn()
    bus.on('*', wildcardHandler)

    localStorage.setItem('auth_token', 'token')
    mockFetch.mockResolvedValue({ ticket: 'abc' })
    await bus.connect()

    const es = firstEventSource()
    es._emit({ type: 'NODE_JOINED', nodeId: 'node-1' })

    expect(wildcardHandler).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'NODE_JOINED' }),
    )
  })

  it('removes handlers with off()', async () => {
    const bus = await createBus()
    const handler = vi.fn()
    bus.on('TEST_EVENT', handler)
    bus.off('TEST_EVENT', handler)

    localStorage.setItem('auth_token', 'token')
    mockFetch.mockResolvedValue({ ticket: 'abc' })
    await bus.connect()

    firstEventSource()._emit({ type: 'TEST_EVENT' })
    expect(handler).not.toHaveBeenCalled()
  })

  it('supports array of event types in on()', async () => {
    const bus = await createBus()
    const handler = vi.fn()
    bus.on(['EVENT_A', 'EVENT_B'], handler)

    localStorage.setItem('auth_token', 'token')
    mockFetch.mockResolvedValue({ ticket: 'abc' })
    await bus.connect()

    const es = firstEventSource()
    es._emit({ type: 'EVENT_A' })
    es._emit({ type: 'EVENT_B' })
    expect(handler).toHaveBeenCalledTimes(2)
  })

  it('does not connect without auth token', async () => {
    const bus = await createBus()
    await bus.connect()
    expect(MockEventSource.instances).toHaveLength(0)
  })

  it('uses ticket-based auth URL', async () => {
    const bus = await createBus()
    localStorage.setItem('auth_token', 'token')
    mockFetch.mockResolvedValue({ ticket: 'my-ticket' })
    await bus.connect()

    const es = firstEventSource()
    expect(es.url).toContain('ticket=my-ticket')
    expect(es.url).not.toContain('token=')
  })

  it('falls back to token URL when ticket fetch fails', async () => {
    const bus = await createBus()
    localStorage.setItem('auth_token', 'my-jwt')
    mockFetch.mockRejectedValue(new Error('Network error'))
    await bus.connect()

    const es = firstEventSource()
    expect(es.url).toContain('token=my-jwt')
  })

  it('disconnect closes EventSource', async () => {
    const bus = await createBus()
    localStorage.setItem('auth_token', 'token')
    mockFetch.mockResolvedValue({ ticket: 'abc' })
    await bus.connect()

    const es = firstEventSource()
    bus.disconnect()
    expect(es.closed).toBe(true)
    expect(bus.connected.value).toBe(false)
  })

  it('handler errors do not break dispatch', async () => {
    const bus = await createBus()
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const badHandler = vi.fn(() => { throw new Error('handler crash') })
    const goodHandler = vi.fn()

    bus.on('TEST', badHandler)
    bus.on('TEST', goodHandler)

    localStorage.setItem('auth_token', 'token')
    mockFetch.mockResolvedValue({ ticket: 'abc' })
    await bus.connect()

    firstEventSource()._emit({ type: 'TEST' })
    expect(badHandler).toHaveBeenCalled()
    expect(goodHandler).toHaveBeenCalled()
    errorSpy.mockRestore()
  })

  it('persists processed sequence and resumes with lastSequence', async () => {
    const bus = await createBus()
    localStorage.setItem('auth_token', 'token')
    mockFetch.mockResolvedValue({ ticket: 'abc' })
    await bus.connect()

    const es = firstEventSource()
    es._emit({ type: 'TEST', sequence: 42 })
    expect(bus.lastSequence.value).toBe(42)

    bus.disconnect()
    MockEventSource.instances = []
    await bus.connect()

    expect(firstEventSource().url).toContain('lastSequence=42')
  })
})
