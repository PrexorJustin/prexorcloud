import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import NodeCachePanel from '../nodes/NodeCachePanel.vue'
import type { NodeCacheStatus } from '~/types/api'

const { postMock, toastSuccess } = vi.hoisted(() => ({
  postMock: vi.fn(),
  toastSuccess: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: vi.fn() } }))
vi.mock('~/composables/useApiClient', () => ({ useApiClient: () => ({ POST: postMock }) }))

function cache(overrides: Partial<NodeCacheStatus> = {}): NodeCacheStatus {
  return {
    totalSizeBytes: 2 * 1048576,
    templates: [{ name: 'survival', sizeBytes: 1024, lastUsed: '2026-05-14T00:00:00Z' }],
    jars: [{ platform: 'paper', version: '1.21.1', jarFile: 'paper.jar', sizeBytes: 2048, cachedAt: '2026-05-14T00:00:00Z' }],
    bootstraps: [{ configFormat: 'yaml', version: '1.21.1', hasCds: true, sizeBytes: 512 }],
    ...overrides,
  } as NodeCacheStatus
}

function props(overrides: Record<string, unknown> = {}) {
  return { nodeId: 'node-a', cache: cache(), loading: false, ...overrides }
}

beforeEach(() => {
  postMock.mockReset().mockResolvedValue({})
  toastSuccess.mockReset()
})

describe('NodeCachePanel', () => {
  it('shows skeleton cards while loading', async () => {
    const wrapper = await mountSuspended(NodeCachePanel, { props: props({ loading: true, cache: null }) })
    expect(wrapper.findAll('.animate-pulse')).toHaveLength(3)
  })

  it('renders the summary card counts and total size', async () => {
    const wrapper = await mountSuspended(NodeCachePanel, { props: props() })
    expect(wrapper.text()).toContain('2.0 MB total')
    expect(wrapper.text()).toContain('Templates')
    expect(wrapper.text()).toContain('JARs')
    expect(wrapper.text()).toContain('Bootstraps')
  })

  it('renders the templates, jars and bootstraps tables when populated', async () => {
    const wrapper = await mountSuspended(NodeCachePanel, { props: props() })
    expect(wrapper.text()).toContain('survival')
    expect(wrapper.text()).toContain('paper.jar')
    expect(wrapper.text()).toContain('yaml')
  })

  it('shows the empty state when nothing is cached', async () => {
    const wrapper = await mountSuspended(NodeCachePanel, {
      props: props({ cache: cache({ templates: [], jars: [], bootstraps: [] }) }),
    })
    expect(wrapper.text()).toContain('No cached data')
  })

  it('Refresh POSTs the refresh endpoint, toasts and emits refresh', async () => {
    const wrapper = await mountSuspended(NodeCachePanel, { props: props() })
    await wrapper.findAll('button').find(b => b.text().includes('Refresh'))!.trigger('click')
    await new Promise(r => setTimeout(r))
    expect(postMock).toHaveBeenCalledWith('/api/v1/nodes/{id}/cache/refresh', {
      params: { path: { id: 'node-a' } },
    })
    expect(toastSuccess).toHaveBeenCalledWith('Cache status refresh requested')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('Pre-warm POSTs the warm endpoint and toasts', async () => {
    const wrapper = await mountSuspended(NodeCachePanel, { props: props() })
    await wrapper.findAll('button').find(b => b.text().includes('Pre-warm'))!.trigger('click')
    await new Promise(r => setTimeout(r))
    expect(postMock).toHaveBeenCalledWith('/api/v1/nodes/{id}/cache/warm', {
      params: { path: { id: 'node-a' } },
    })
    expect(toastSuccess).toHaveBeenCalledWith('Cache pre-warm started')
  })
})
