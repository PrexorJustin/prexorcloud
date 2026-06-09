import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { nextTick } from 'vue'

import { useResourceSearchIndex } from '../useResourceSearchIndex'
import { useInstancesStore } from '~/stores/instances'
import { useNodesStore } from '~/stores/nodes'
import { useGroupsStore } from '~/stores/groups'
import { useTemplatesStore } from '~/stores/templates'

function seed() {
  const inst = useInstancesStore()
  const nodes = useNodesStore()
  const groups = useGroupsStore()
  const tpl = useTemplatesStore()
  // @ts-expect-error — seed Pinia state directly
  inst.instances = [
    { id: 'lobby-1', group: 'lobby', node: 'node-a', state: 'RUNNING' },
    { id: 'survival-1', group: 'survival', node: 'node-b', state: 'STARTING' },
    { id: 'arena-1', group: 'arena', node: 'node-c', state: 'CRASHED' },
  ]
  // @ts-expect-error
  nodes.nodes = [
    { id: 'node-a', status: 'ONLINE' },
    { id: 'node-b', status: 'DRAINING' },
    { id: 'node-c', status: 'UNREACHABLE' },
  ]
  // @ts-expect-error
  groups.groups = [
    { name: 'lobby', platform: 'paper', runningInstances: 2, maintenance: false },
    { name: 'survival', platform: 'paper', runningInstances: 1, maintenance: true },
  ]
  // @ts-expect-error
  tpl.templates = [
    { name: 'paper-vanilla', platform: 'paper' },
    { name: 'velocity-default', platform: 'velocity' },
  ]
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('useResourceSearchIndex', () => {
  it('normalises instances/nodes/groups/templates into a flat hit list', () => {
    seed()
    const { items } = useResourceSearchIndex()
    expect(items.value).toHaveLength(3 + 3 + 2 + 2)
    expect(items.value.find(h => h.id === 'lobby-1')!.kind).toBe('instance')
    expect(items.value.find(h => h.id === 'node-a')!.kind).toBe('node')
    expect(items.value.find(h => h.id === 'lobby')!.kind).toBe('group')
    expect(items.value.find(h => h.id === 'paper-vanilla')!.kind).toBe('template')
  })

  it('maps instance states to status tones', () => {
    seed()
    const { items } = useResourceSearchIndex()
    const lobby = items.value.find(h => h.id === 'lobby-1')!
    const survival = items.value.find(h => h.id === 'survival-1')!
    const arena = items.value.find(h => h.id === 'arena-1')!
    expect(lobby.statusTone).toBe('success')
    expect(survival.statusTone).toBe('primary')
    expect(arena.statusTone).toBe('destructive')
  })

  it('maps node statuses to tones', () => {
    seed()
    const { items } = useResourceSearchIndex()
    expect(items.value.find(h => h.id === 'node-a')!.statusTone).toBe('success')
    expect(items.value.find(h => h.id === 'node-b')!.statusTone).toBe('warning')
    expect(items.value.find(h => h.id === 'node-c')!.statusTone).toBe('destructive')
  })

  it('group maintenance flag maps to a warning tone', () => {
    seed()
    const { items } = useResourceSearchIndex()
    expect(items.value.find(h => h.id === 'lobby')!.statusTone).toBe('primary')
    expect(items.value.find(h => h.id === 'survival')!.statusTone).toBe('warning')
  })

  it('builds routes per kind', () => {
    seed()
    const { items } = useResourceSearchIndex()
    expect(items.value.find(h => h.id === 'lobby-1')!.route).toBe('/instances/lobby-1')
    expect(items.value.find(h => h.id === 'node-a')!.route).toBe('/nodes/node-a')
    expect(items.value.find(h => h.id === 'lobby')!.route).toBe('/groups/lobby')
    expect(items.value.find(h => h.id === 'paper-vanilla')!.route).toBe('/templates/paper-vanilla')
  })

  it('search returns [] for an empty / whitespace query', () => {
    seed()
    const { search } = useResourceSearchIndex()
    expect(search('')).toEqual([])
    expect(search('   ')).toEqual([])
  })

  it('search finds a hit fuzzily by label', () => {
    seed()
    const { search } = useResourceSearchIndex()
    const hits = search('lobby')
    expect(hits.length).toBeGreaterThan(0)
    expect(hits.some(h => h.id === 'lobby-1' || h.id === 'lobby')).toBe(true)
  })

  it('search honours the limit option', () => {
    seed()
    const { search } = useResourceSearchIndex()
    const hits = search('e', 2) // broad query so the limit actually bites
    expect(hits.length).toBeLessThanOrEqual(2)
  })

  it('refreshes the Fuse index when a store changes', async () => {
    seed()
    const { search } = useResourceSearchIndex()
    expect(search('newentry')).toEqual([])

    const tpl = useTemplatesStore()
    // @ts-expect-error
    tpl.templates = [...tpl.templates, { name: 'newentry-template', platform: 'paper' }]

    await nextTick()
    const hits = search('newentry')
    expect(hits.some(h => h.id === 'newentry-template')).toBe(true)
  })
})
