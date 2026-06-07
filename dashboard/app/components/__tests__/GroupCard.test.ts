import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import GroupCard from '../groups/GroupCard.vue'
import type { ServerGroup } from '~/types/api'

const { postMock, toastSuccess, toastError } = vi.hoisted(() => ({
  postMock: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))
vi.mock('~/composables/useApiClient', () => ({ useApiClient: () => ({ POST: postMock }) }))
// `navigateTo` is a Nuxt auto-import resolved at compile time — vi.stubGlobal
// can't intercept it, so the card-click → navigate wiring isn't asserted here.
vi.stubGlobal('navigateTo', vi.fn())

function group(overrides: Partial<ServerGroup> = {}): ServerGroup {
  return {
    name: 'lobby', platform: 'paper', platformVersion: '1.21.1', scalingMode: 'DYNAMIC',
    maintenance: false, runningInstances: 2, maxInstances: 8,
    totalPlayers: 30, maxPlayers: 200, memoryMb: 4096,
    ...overrides,
  } as ServerGroup
}

beforeEach(() => {
  postMock.mockReset().mockResolvedValue({})
  toastSuccess.mockReset()
  toastError.mockReset()
})

describe('GroupCard', () => {
  it('renders the group name, platform/version, and metrics', async () => {
    const wrapper = await mountSuspended(GroupCard, { props: { group: group() } })
    expect(wrapper.text()).toContain('lobby')
    expect(wrapper.text()).toContain('paper 1.21.1')
    expect(wrapper.text()).toContain('2/8')      // runningInstances/maxInstances
    expect(wrapper.text()).toContain('30/200')   // totalPlayers/maxPlayers
    expect(wrapper.text()).toContain('4096 MB')
  })

  it('maps the scaling mode to its display label', async () => {
    const wrapper = await mountSuspended(GroupCard, { props: { group: group({ scalingMode: 'DYNAMIC' }) } })
    expect(wrapper.text()).toContain('Dynamic')
  })

  it('shows a Maintenance badge and hides the start button under maintenance', async () => {
    const wrapper = await mountSuspended(GroupCard, { props: { group: group({ maintenance: true }) } })
    expect(wrapper.text()).toContain('Maintenance')
    expect(wrapper.find('button[title="Start instance"]').exists()).toBe(false)
  })

  it('shows the start button when not under maintenance', async () => {
    const wrapper = await mountSuspended(GroupCard, { props: { group: group() } })
    expect(wrapper.find('button[title="Start instance"]').exists()).toBe(true)
  })

  it('start button POSTs to the group start endpoint and toasts success', async () => {
    const wrapper = await mountSuspended(GroupCard, { props: { group: group() } })
    await wrapper.find('button[title="Start instance"]').trigger('click')
    expect(postMock).toHaveBeenCalledWith('/api/v1/groups/{name}/start', {
      params: { path: { name: 'lobby' } },
    })
    expect(toastSuccess).toHaveBeenCalled()
  })

  it('start button toasts an error when the POST rejects', async () => {
    postMock.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountSuspended(GroupCard, { props: { group: group() } })
    await wrapper.find('button[title="Start instance"]').trigger('click')
    await new Promise((r) => setTimeout(r))
    expect(toastError).toHaveBeenCalledWith('Failed to start instance')
  })
})
