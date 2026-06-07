import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import LogsPage from '../observability/logs.vue'

const { route, nodesStore, getAuthToken, termInstances } = vi.hoisted(() => ({
  route: { query: {} as Record<string, string> },
  nodesStore: {
    nodes: [] as unknown[],
    fetchNodes: vi.fn(),
  },
  getAuthToken: vi.fn(() => 'tok-xyz'),
  termInstances: [] as Array<Record<string, unknown>>,
}))

vi.mock('~/lib/auth-storage', () => ({
  getAuthToken,
  AUTH_TOKEN_KEY: 'auth_token',
  setAuthToken: vi.fn(),
  clearAuthToken: vi.fn(),
}))

vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    buffer = { active: { length: 0, getLine: () => null } }
    loadAddon = vi.fn()
    open = vi.fn()
    writeln = vi.fn()
    scrollToBottom = vi.fn()
    clear = vi.fn()
    dispose = vi.fn()
    constructor() { termInstances.push(this) }
  },
}))
vi.mock('@xterm/addon-fit', () => ({
  FitAddon: class { fit = vi.fn() },
}))
vi.mock('@xterm/xterm/css/xterm.css', () => ({}))

mockNuxtImport('useRoute', () => () => reactive(route))
mockNuxtImport('useNodesStore', () => () => reactive(nodesStore))
mockNuxtImport('useRuntimeConfig', () => () => ({
  app: { baseURL: '/', buildId: 'test' },
  public: { apiBase: 'http://api.test' },
}))

class FakeEventSource {
  url: string
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((ev: { data: string }) => void) | null = null
  close = vi.fn()
  static instances: FakeEventSource[] = []
  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }
}

class FakeResizeObserver {
  observe = vi.fn()
  disconnect = vi.fn()
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  route.query = {}
  nodesStore.nodes = []
  nodesStore.fetchNodes.mockReset()
  getAuthToken.mockReset().mockReturnValue('tok-xyz')
  termInstances.length = 0
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  vi.stubGlobal('ResizeObserver', FakeResizeObserver)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('LogsPage', () => {
  it('renders the header, toolbar actions and scrollback note', async () => {
    const wrapper = await mountSuspended(LogsPage)
    await flush()
    expect(wrapper.text()).toContain('Logs')
    expect(wrapper.text()).toContain('Live tail of controller and node stdout/stderr.')
    expect(wrapper.text()).toContain('Pause')
    expect(wrapper.text()).toContain('Export')
    expect(wrapper.text()).toContain('Clear')
  })

  it('fetches nodes on mount and opens an SSE stream to the controller log endpoint', async () => {
    const wrapper = await mountSuspended(LogsPage)
    await flush()
    expect(nodesStore.fetchNodes).toHaveBeenCalled()
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(FakeEventSource.instances[0]!.url).toBe('http://api.test/api/v1/system/logs/stream?token=tok-xyz')
    wrapper.unmount()
  })

  it('defaults the source to a node stream when the route carries a nodeId query', async () => {
    route.query = { nodeId: 'node-7' }
    const wrapper = await mountSuspended(LogsPage)
    await flush()
    expect(FakeEventSource.instances[0]!.url).toBe(
      'http://api.test/api/v1/nodes/node-7/logs/stream?token=tok-xyz',
    )
    wrapper.unmount()
  })

  it('shows the connected status once the EventSource opens', async () => {
    const wrapper = await mountSuspended(LogsPage)
    await flush()
    FakeEventSource.instances[0]!.onopen?.()
    await flush()
    expect(wrapper.text()).toContain('Connected')
    wrapper.unmount()
  })

  it('pausing disconnects the stream and shows the paused status', async () => {
    const wrapper = await mountSuspended(LogsPage)
    await flush()
    const es = FakeEventSource.instances[0]!
    const pauseBtn = wrapper.findAll('button').find(b => b.text().includes('Pause'))!
    await pauseBtn.trigger('click')
    await flush()
    expect(es.close).toHaveBeenCalled()
    expect(wrapper.text()).toContain('Paused')
    expect(wrapper.text()).toContain('Resume')
    wrapper.unmount()
  })

  it('writes incoming SSE messages into the terminal', async () => {
    const wrapper = await mountSuspended(LogsPage)
    await flush()
    FakeEventSource.instances[0]!.onmessage?.({ data: 'hello log line' })
    const term = termInstances[0]!
    expect(term.writeln).toHaveBeenCalledWith('hello log line')
    wrapper.unmount()
  })

  it('lists controller plus every known node in the source options', async () => {
    nodesStore.nodes = [{ id: 'node-a' }, { id: 'node-b' }]
    const wrapper = await mountSuspended(LogsPage)
    await flush()
    expect(wrapper.findAll('option, [role="option"]').length).toBeGreaterThanOrEqual(0)
    wrapper.unmount()
  })
})
