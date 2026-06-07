import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import type { ServerInstance } from '~/types/api'
import InstanceDetail from '../instances/[id].vue'

const {
  routeParams,
  getMock, fetchInstances, stopInstance, forceStopInstance, deleteInstance,
  connectSse, disconnectSse, storeState,
  toastError,
} = vi.hoisted(() => ({
  routeParams: { value: { id: 'lobby-1' } as Record<string, string> },
  getMock: vi.fn(),
  fetchInstances: vi.fn(),
  stopInstance: vi.fn(),
  forceStopInstance: vi.fn(),
  deleteInstance: vi.fn(),
  connectSse: vi.fn(),
  disconnectSse: vi.fn(),
  storeState: {
    instances: [] as ServerInstance[],
    lastInstanceMetrics: null as unknown,
  },
  toastError: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => ({ params: routeParams.value }))

vi.mock('~/composables/useApiClient', () => ({ useApiClient: () => ({ GET: getMock }) }))
vi.mock('~/composables/useMetricsTimeseries', () => ({
  useMetricsTimeseries: () => ({ series: {}, seriesRaw: {}, meta: { value: null }, loaded: { value: false }, refresh: vi.fn() }),
}))
vi.mock('~/stores/instances', () => ({
  useInstancesStore: () => ({
    get instances() { return storeState.instances },
    get lastInstanceMetrics() { return storeState.lastInstanceMetrics },
    fetchInstances,
    stopInstance,
    forceStopInstance,
    deleteInstance,
    connectSse,
    disconnectSse,
  }),
}))
vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))

function instance(overrides: Partial<ServerInstance> = {}): ServerInstance {
  return {
    id: 'lobby-1', group: 'lobby', node: 'node-a', state: 'RUNNING',
    port: 25565, playerCount: 17, uptimeMs: 3_600_000,
    startedAt: '2026-05-14T00:00:00Z', deploymentRevision: 3,
    ...overrides,
  } as ServerInstance
}

// Resolve every API GET by URL — instance, metrics, proxy-metrics, composition.
function wireGet(opts: { instance?: ServerInstance | null, fail?: boolean } = {}) {
  getMock.mockImplementation((path: string) => {
    if (path === '/api/v1/services/{id}') {
      if (opts.fail) return Promise.reject(new Error('not found'))
      return Promise.resolve({ data: opts.instance ?? instance() })
    }
    if (path.includes('/composition')) return Promise.resolve({ data: null })
    // metrics + proxy-metrics + timeseries
    return Promise.resolve({ data: null })
  })
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

const mountOpts = { global: { stubs: { InstancesInstanceConsole: true } } }

beforeEach(() => {
  routeParams.value = { id: 'lobby-1' }
  getMock.mockReset()
  fetchInstances.mockReset().mockResolvedValue(undefined)
  stopInstance.mockReset().mockResolvedValue(undefined)
  forceStopInstance.mockReset().mockResolvedValue(undefined)
  deleteInstance.mockReset().mockResolvedValue(undefined)
  connectSse.mockReset()
  disconnectSse.mockReset()
  storeState.instances = []
  storeState.lastInstanceMetrics = null
  toastError.mockReset()
  wireGet()
})

describe('instances/[id]', () => {
  it('renders the instance id in the header', async () => {
    const wrapper = await mountSuspended(InstanceDetail, mountOpts)
    await flush()
    expect(wrapper.text()).toContain('lobby-1')
    expect(wrapper.text()).toContain('Instance')
  })

  it('loads the instance on mount and renders its info card', async () => {
    const wrapper = await mountSuspended(InstanceDetail, mountOpts)
    await flush()
    expect(getMock).toHaveBeenCalledWith('/api/v1/services/{id}', { params: { path: { id: 'lobby-1' } } })
    expect(fetchInstances).toHaveBeenCalledTimes(1)
    expect(connectSse).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('node-a')
    expect(wrapper.text()).toContain('25565')
    expect(wrapper.text()).toContain('rev 3')
  })

  it('shows the no-metrics empty state for a game server without metrics', async () => {
    const wrapper = await mountSuspended(InstanceDetail, mountOpts)
    await flush()
    expect(wrapper.text()).toContain('No metrics available')
  })

  it('shows the Stop button for a RUNNING instance and delegates to the store', async () => {
    const wrapper = await mountSuspended(InstanceDetail, mountOpts)
    await flush()
    const stopBtn = wrapper.findAll('button').find(b => b.text().includes('Stop'))
    expect(stopBtn).toBeTruthy()
    await stopBtn!.trigger('click')
    await flush()
    expect(stopInstance).toHaveBeenCalledWith('lobby-1')
  })

  it('shows the Delete button for a STOPPED instance and deletes via the store', async () => {
    wireGet({ instance: instance({ state: 'STOPPED' }) })
    const wrapper = await mountSuspended(InstanceDetail, mountOpts)
    await flush()
    const deleteBtn = wrapper.findAll('button').find(b => b.text().includes('Delete'))
    expect(deleteBtn).toBeTruthy()
    await deleteBtn!.trigger('click')
    await flush()
    // ConfirmDialog confirm — find the confirm action in the dialog.
    const confirmBtn = [...document.querySelectorAll('button')].find(b => b.textContent?.trim() === 'Delete')
    expect(confirmBtn).toBeTruthy()
    confirmBtn!.click()
    await flush()
    expect(deleteInstance).toHaveBeenCalledWith('lobby-1')
  })

  it('toasts an error when the instance cannot be loaded', async () => {
    wireGet({ fail: true })
    await mountSuspended(InstanceDetail, mountOpts)
    await flush()
    expect(toastError).toHaveBeenCalledWith("Can't load instance", expect.any(Object))
  })

  it('toasts an error when the store stop call rejects', async () => {
    stopInstance.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountSuspended(InstanceDetail, mountOpts)
    await flush()
    const stopBtn = wrapper.findAll('button').find(b => b.text().includes('Stop'))!
    await stopBtn.trigger('click')
    await flush()
    expect(toastError).toHaveBeenCalledWith('Stop failed', expect.any(Object))
  })

  it('disconnects SSE on unmount', async () => {
    const wrapper = await mountSuspended(InstanceDetail, mountOpts)
    await flush()
    wrapper.unmount()
    expect(disconnectSse).toHaveBeenCalledTimes(1)
  })
})
