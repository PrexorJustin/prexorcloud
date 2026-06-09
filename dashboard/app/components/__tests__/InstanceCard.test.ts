import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import InstanceCard from '../instances/InstanceCard.vue'
import type { ServerInstance } from '~/types/api'

const { stopInstance, deleteInstance, toastError } = vi.hoisted(() => ({
  stopInstance: vi.fn(),
  deleteInstance: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { error: toastError, success: vi.fn() } }))
vi.mock('~/stores/instances', () => ({
  useInstancesStore: () => ({ stopInstance, deleteInstance }),
}))
// `navigateTo` is a Nuxt auto-import resolved at compile time — vi.stubGlobal
// can't intercept it, so the card-click → navigate wiring isn't asserted here.
vi.stubGlobal('navigateTo', vi.fn())

function instance(overrides: Partial<ServerInstance> = {}): ServerInstance {
  return {
    id: 'lobby-1', group: 'lobby', node: 'node-a', state: 'RUNNING',
    port: 25565, playerCount: 17, uptimeMs: 3_600_000,
    startedAt: '2026-05-14T00:00:00Z', deploymentRevision: 1,
    ...overrides,
  }
}

beforeEach(() => {
  stopInstance.mockReset().mockResolvedValue(undefined)
  deleteInstance.mockReset().mockResolvedValue(undefined)
  toastError.mockReset()
})

describe('InstanceCard', () => {
  it('renders the instance id, node, group and player count', async () => {
    const wrapper = await mountSuspended(InstanceCard, { props: { instance: instance() } })
    expect(wrapper.text()).toContain('lobby-1')
    expect(wrapper.text()).toContain('node-a')
    expect(wrapper.text()).toContain('lobby')
    expect(wrapper.text()).toContain('17')
  })

  it('shows the stop button (not delete) for a RUNNING instance', async () => {
    const wrapper = await mountSuspended(InstanceCard, { props: { instance: instance({ state: 'RUNNING' }) } })
    expect(wrapper.find('button[title="Stop instance"]').exists()).toBe(true)
    expect(wrapper.find('button[title="Delete instance"]').exists()).toBe(false)
  })

  it('shows the delete button (not stop) for a STOPPED instance', async () => {
    const wrapper = await mountSuspended(InstanceCard, { props: { instance: instance({ state: 'STOPPED' }) } })
    expect(wrapper.find('button[title="Delete instance"]').exists()).toBe(true)
    expect(wrapper.find('button[title="Stop instance"]').exists()).toBe(false)
  })

  it('shows neither action button for a STOPPING instance', async () => {
    const wrapper = await mountSuspended(InstanceCard, { props: { instance: instance({ state: 'STOPPING' }) } })
    expect(wrapper.find('button[title="Stop instance"]').exists()).toBe(false)
    expect(wrapper.find('button[title="Delete instance"]').exists()).toBe(false)
  })

  it('stop button delegates to the store', async () => {
    const wrapper = await mountSuspended(InstanceCard, { props: { instance: instance({ state: 'RUNNING' }) } })
    await wrapper.find('button[title="Stop instance"]').trigger('click')
    expect(stopInstance).toHaveBeenCalledWith('lobby-1')
  })

  it('delete button delegates to the store', async () => {
    const wrapper = await mountSuspended(InstanceCard, { props: { instance: instance({ state: 'CRASHED' }) } })
    await wrapper.find('button[title="Delete instance"]').trigger('click')
    expect(deleteInstance).toHaveBeenCalledWith('lobby-1')
  })

  it('toasts an error when the store stop call rejects', async () => {
    stopInstance.mockRejectedValueOnce(new Error('nope'))
    const wrapper = await mountSuspended(InstanceCard, { props: { instance: instance({ state: 'RUNNING' }) } })
    await wrapper.find('button[title="Stop instance"]').trigger('click')
    await new Promise((r) => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Stop instance failed', expect.any(Object))
  })
})
