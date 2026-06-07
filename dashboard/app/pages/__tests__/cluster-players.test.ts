import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ClusterPlayers from '../cluster/players.vue'
import type { Player, PlayerJourneyEntry } from '~/stores/players'

const { playersState, instancesState, fetchPlayers, fetchInstances, fetchJourney, transfer } = vi.hoisted(() => ({
  playersState: { players: [] as Player[], loading: false },
  instancesState: { instances: [] as Record<string, unknown>[] },
  fetchPlayers: vi.fn(),
  fetchInstances: vi.fn(),
  fetchJourney: vi.fn(),
  transfer: vi.fn(),
}))

vi.mock('~/stores/players', () => ({
  usePlayersStore: () => ({
    get players() { return playersState.players },
    get loading() { return playersState.loading },
    fetchPlayers,
    fetchJourney,
    transfer,
  }),
}))
vi.mock('~/stores/instances', () => ({
  useInstancesStore: () => ({
    get instances() { return instancesState.instances },
    fetchInstances,
  }),
}))

function player(overrides: Partial<Player> = {}): Player {
  return {
    id: 'p1', uuid: 'uuid-1', username: 'Steve',
    currentInstance: 'lobby-1', group: 'lobby', ping: 40,
    connectedAt: new Date().toISOString(),
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
  document.body.innerHTML = ''
  playersState.players = []
  playersState.loading = false
  instancesState.instances = []
  fetchPlayers.mockReset()
  fetchInstances.mockReset()
  fetchJourney.mockReset().mockResolvedValue([])
  transfer.mockReset().mockResolvedValue(undefined)
})

describe('cluster/players page', () => {
  it('renders the header and fetches players + instances on mount', async () => {
    const wrapper = await mountSuspended(ClusterPlayers)
    expect(wrapper.text()).toContain('Players')
    expect(wrapper.text()).toContain('Connected clients across the cluster')
    expect(fetchPlayers).toHaveBeenCalled()
    expect(fetchInstances).toHaveBeenCalled()
  })

  it('shows the empty state when no players are online', async () => {
    const wrapper = await mountSuspended(ClusterPlayers)
    expect(wrapper.text()).toContain('No players online')
  })

  it('renders a row per player and the online/groups summary counts', async () => {
    playersState.players = [
      player({ id: 'p1', username: 'Steve', group: 'lobby', ping: 40 }),
      player({ id: 'p2', username: 'Alex', uuid: 'uuid-2', group: 'survival', ping: 200 }),
    ]
    const wrapper = await mountSuspended(ClusterPlayers)
    expect(wrapper.text()).toContain('Steve')
    expect(wrapper.text()).toContain('Alex')
    // Online count
    expect(wrapper.text()).toContain('2')
  })

  it('filters the table by the search field', async () => {
    playersState.players = [
      player({ id: 'p1', username: 'Steve' }),
      player({ id: 'p2', username: 'Alex', uuid: 'uuid-2' }),
    ]
    const wrapper = await mountSuspended(ClusterPlayers)
    await wrapper.find('input').setValue('Alex')
    await flush()
    expect(wrapper.text()).toContain('Alex')
    expect(wrapper.text()).not.toContain('Steve')
  })

  it('shows the loading skeleton while the store is loading', async () => {
    playersState.loading = true
    const wrapper = await mountSuspended(ClusterPlayers)
    expect(wrapper.find('input').exists()).toBe(true)
    // no player rows / empty state while loading
    expect(wrapper.text()).not.toContain('No players online')
  })

  it('opens the detail sheet and loads the player journey on row click', async () => {
    fetchJourney.mockResolvedValue([
      { ts: new Date().toISOString(), type: 'connected', toInstance: 'lobby-1' },
    ] as PlayerJourneyEntry[])
    playersState.players = [player({ id: 'p1', username: 'Steve' })]
    const wrapper = await mountSuspended(ClusterPlayers)
    const row = wrapper.findAll('div').find(d => d.classes().includes('cursor-pointer'))
    await row!.trigger('click')
    await flush()
    expect(fetchJourney).toHaveBeenCalledWith('p1')
    // DetailSheet (reka-ui Sheet) teleports its content to document.body.
    expect(document.body.textContent).toContain('Transfer')
  })

  it('submits a transfer through the store with the chosen target', async () => {
    playersState.players = [player({ id: 'p1', username: 'Steve', currentInstance: 'lobby-1' })]
    instancesState.instances = [
      { id: 'survival-1', group: 'survival', state: 'RUNNING' },
    ]
    const wrapper = await mountSuspended(ClusterPlayers)
    const row = wrapper.findAll('div').find(d => d.classes().includes('cursor-pointer'))
    await row!.trigger('click')
    await flush()
    // Select is a headless combobox — set the bound model directly, then click
    // the Transfer button (teleported to document.body inside the sheet).
    ;(wrapper.vm as unknown as { transferTarget: string }).transferTarget = 'survival-1'
    await flush()
    const btn = Array.from(document.body.querySelectorAll('button'))
      .find(b => b.textContent?.includes('Transfer'))
    btn!.click()
    await flush()
    expect(transfer).toHaveBeenCalledWith('p1', 'survival-1')
  })
})
