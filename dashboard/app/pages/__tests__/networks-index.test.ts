import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import NetworksIndex from '../networks/index.vue'

const { networksStore, groupsStore, can } = vi.hoisted(() => ({
  networksStore: {
    networks: [] as unknown[],
    loading: false,
    fetchNetworks: vi.fn(),
    deleteNetwork: vi.fn(),
  },
  groupsStore: {
    groups: [] as unknown[],
    fetchGroups: vi.fn(),
  },
  can: vi.fn(() => true),
}))

mockNuxtImport('useNetworksStore', () => () => reactive(networksStore))
mockNuxtImport('useGroupsStore', () => () => reactive(groupsStore))
mockNuxtImport('useCan', () => () => ({ can, canAny: vi.fn(() => true) }))

function network(overrides: Record<string, unknown> = {}) {
  return {
    name: 'main-net',
    description: 'Primary network',
    lobbyGroup: 'lobby',
    fallbackGroups: ['survival'],
    memberGroups: ['lobby', 'survival'],
    proxyGroups: ['proxy'],
    ...overrides,
  }
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  networksStore.networks = []
  networksStore.loading = false
  networksStore.fetchNetworks.mockReset()
  networksStore.deleteNetwork.mockReset().mockResolvedValue(undefined)
  groupsStore.groups = []
  groupsStore.fetchGroups.mockReset()
  can.mockReset().mockReturnValue(true)
})

describe('NetworksIndex', () => {
  it('renders the page header and fetches networks + groups on mount', async () => {
    const wrapper = await mountSuspended(NetworksIndex)
    await flush()
    expect(wrapper.text()).toContain('Networks')
    expect(wrapper.text()).toContain('Velocity proxies fronting one or more groups')
    expect(networksStore.fetchNetworks).toHaveBeenCalled()
    expect(groupsStore.fetchGroups).toHaveBeenCalled()
  })

  it('shows the empty state when no networks are configured', async () => {
    const wrapper = await mountSuspended(NetworksIndex)
    await flush()
    expect(wrapper.text()).toContain('No networks configured')
  })

  it('renders a card per network with its lobby and fallback chain', async () => {
    networksStore.networks = [network()]
    const wrapper = await mountSuspended(NetworksIndex)
    await flush()
    expect(wrapper.text()).toContain('main-net')
    expect(wrapper.text()).toContain('Primary network')
    expect(wrapper.text()).toContain('lobby')
    expect(wrapper.text()).toContain('survival')
  })

  it('filters the rendered networks by the search box', async () => {
    networksStore.networks = [network({ name: 'alpha' }), network({ name: 'beta', description: '' })]
    const wrapper = await mountSuspended(NetworksIndex)
    await flush()
    expect(wrapper.text()).toContain('alpha')
    expect(wrapper.text()).toContain('beta')
    const searchInput = wrapper.find('input[type="search"], input')
    await searchInput.setValue('alpha')
    await flush()
    expect(wrapper.text()).toContain('alpha')
    expect(wrapper.text()).not.toContain('beta')
  })

  it('clicking delete on a network opens the confirm dialog and calls deleteNetwork', async () => {
    networksStore.networks = [network({ name: 'doomed' })]
    const wrapper = await mountSuspended(NetworksIndex)
    await flush()
    const delBtn = wrapper.find('button[title="Delete network"]')
    await delBtn.trigger('click')
    await flush()
    // ConfirmDialog content teleports to document.body
    const confirmBtn = Array.from(document.body.querySelectorAll('button'))
      .find(b => b.textContent?.trim() === 'Delete network') as HTMLButtonElement
    expect(confirmBtn).toBeTruthy()
    confirmBtn.click()
    await flush()
    expect(networksStore.deleteNetwork).toHaveBeenCalledWith('doomed')
    wrapper.unmount()
    document.body.innerHTML = ''
  })

  it('hides edit and delete controls when the user lacks permissions', async () => {
    can.mockReturnValue(false)
    networksStore.networks = [network()]
    const wrapper = await mountSuspended(NetworksIndex)
    await flush()
    expect(wrapper.find('button[title="Edit network"]').exists()).toBe(false)
    expect(wrapper.find('button[title="Delete network"]').exists()).toBe(false)
  })
})
