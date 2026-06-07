import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { useNotificationsStore } from '../notifications'

const busOn = vi.fn()
const busOff = vi.fn()
const busConnect = vi.fn()
const busDisconnect = vi.fn()

vi.mock('~/composables/useSseEventBus', () => ({
  useSseEventBus: () => ({
    on: busOn,
    off: busOff,
    connect: busConnect,
    disconnect: busDisconnect,
    connected: { value: false },
  }),
}))

const STORAGE_KEY = 'prexor:notifications'

// Capture the handleEvent callback the store registers on the SSE bus so
// individual event types can be driven without exporting the function.
function captureHandler(): (event: unknown) => void {
  const store = useNotificationsStore()
  store.connectSse()
  const lastCall = busOn.mock.calls.at(-1)
  if (!lastCall) throw new Error('connectSse did not register an SSE handler')
  return lastCall[1] as (event: unknown) => void
}

describe('useNotificationsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    busOn.mockReset(); busOff.mockReset(); busConnect.mockReset(); busDisconnect.mockReset()
  })

  it('starts empty with zero unread', () => {
    const store = useNotificationsStore()
    expect(store.items).toEqual([])
    expect(store.unreadCount).toBe(0)
  })

  it('rehydrates persisted notifications from localStorage', () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([
      { id: 'n1', tone: 'warning', title: 'Old crash', createdAt: '2026-05-13T00:00:00Z', read: false },
    ]))
    const store = useNotificationsStore()
    expect(store.items).toHaveLength(1)
    expect(store.unreadCount).toBe(1)
  })

  it('ignores a malformed persisted payload', () => {
    localStorage.setItem(STORAGE_KEY, '{not json')
    const store = useNotificationsStore()
    expect(store.items).toEqual([])
  })

  it('add() prepends and persists with read=false', () => {
    const store = useNotificationsStore()
    store.add({ tone: 'primary', title: 'first' })
    store.add({ tone: 'success', title: 'second' })
    expect(store.items.map(n => n.title)).toEqual(['second', 'first'])
    expect(store.unreadCount).toBe(2)

    const persisted = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]')
    expect(persisted).toHaveLength(2)
    expect(persisted[0].read).toBe(false)
  })

  it('add() caps the buffer at 100 entries', () => {
    const store = useNotificationsStore()
    for (let i = 0; i < 105; i++) store.add({ tone: 'muted', title: `t${i}` })
    expect(store.items).toHaveLength(100)
    // Newest first → the last add() is at index 0.
    expect(store.items[0]?.title).toBe('t104')
  })

  it('markRead flips a single entry and persists', () => {
    const store = useNotificationsStore()
    store.add({ tone: 'primary', title: 'one' })
    const id = store.items[0]!.id
    store.markRead(id)
    expect(store.items[0]?.read).toBe(true)
    expect(store.unreadCount).toBe(0)
  })

  it('markAllRead flips every entry', () => {
    const store = useNotificationsStore()
    store.add({ tone: 'primary', title: 'a' })
    store.add({ tone: 'primary', title: 'b' })
    store.markAllRead()
    expect(store.unreadCount).toBe(0)
    expect(store.items.every(n => n.read)).toBe(true)
  })

  it('remove drops a single entry; clear empties the list', () => {
    const store = useNotificationsStore()
    store.add({ tone: 'primary', title: 'a' })
    store.add({ tone: 'primary', title: 'b' })
    const id = store.items[0]!.id
    store.remove(id)
    expect(store.items).toHaveLength(1)
    store.clear()
    expect(store.items).toEqual([])
  })

  it('connectSse registers exactly once', () => {
    const store = useNotificationsStore()
    store.connectSse()
    store.connectSse()
    expect(busOn).toHaveBeenCalledTimes(1)
    expect(busConnect).toHaveBeenCalledTimes(1)
  })

  it('translates INSTANCE_CRASHED into a destructive notification', () => {
    const handle = captureHandler()
    const store = useNotificationsStore()
    handle({ type: 'INSTANCE_CRASHED', instanceId: 'srv-1', classification: 'OOM' })
    expect(store.items).toHaveLength(1)
    expect(store.items[0]).toMatchObject({
      tone: 'destructive',
      title: 'srv-1 crashed',
      description: 'Classification: OOM',
      route: '/instances/srv-1',
    })
  })

  it('translates DEPLOYMENT_COMPLETED + ROLLED_BACK with different tones', () => {
    const handle = captureHandler()
    const store = useNotificationsStore()
    handle({ type: 'DEPLOYMENT_COMPLETED', groupName: 'lobby' })
    handle({ type: 'DEPLOYMENT_ROLLED_BACK', groupName: 'lobby', revision: 4 })
    // Newest first
    expect(store.items[0]?.tone).toBe('warning')
    expect(store.items[1]?.tone).toBe('success')
  })

  it('translates NODE_CONNECTED / NODE_DISCONNECTED', () => {
    const handle = captureHandler()
    const store = useNotificationsStore()
    handle({ type: 'NODE_CONNECTED',    nodeId: 'lon-1' })
    handle({ type: 'NODE_DISCONNECTED', nodeId: 'lon-1' })
    expect(store.items[0]?.tone).toBe('warning')
    expect(store.items[1]?.tone).toBe('success')
  })

  it('translates GROUP_CRASH_LOOP as destructive', () => {
    const handle = captureHandler()
    const store = useNotificationsStore()
    handle({ type: 'GROUP_CRASH_LOOP', group: 'lobby', crashCount: 3, windowStart: Date.now() })
    expect(store.items[0]).toMatchObject({ tone: 'destructive', route: '/groups/lobby' })
  })

  it('routes MAINTENANCE_UPDATED tone by globalEnabled', () => {
    const handle = captureHandler()
    const store = useNotificationsStore()
    handle({ type: 'MAINTENANCE_UPDATED', globalEnabled: true,  message: 'on'  })
    handle({ type: 'MAINTENANCE_UPDATED', globalEnabled: false, message: 'off' })
    expect(store.items[0]?.tone).toBe('primary')
    expect(store.items[1]?.tone).toBe('warning')
  })

  it('disconnectSse() detaches the handler', () => {
    const store = useNotificationsStore()
    store.connectSse()
    store.disconnectSse()
    expect(busOff).toHaveBeenCalledTimes(1)
    // Subsequent reconnects re-register cleanly.
    store.connectSse()
    expect(busOn).toHaveBeenCalledTimes(2)
  })
})
