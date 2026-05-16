import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { CloudEvent } from '~/types/events'

const STORAGE_KEY = 'prexor:notifications'
const MAX_NOTIFICATIONS = 100

export type NotificationTone = 'destructive' | 'warning' | 'success' | 'primary' | 'muted'

export interface Notification {
  id: string
  tone: NotificationTone
  title: string
  description?: string
  route?: string
  createdAt: string
  read: boolean
}

function uid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

function safeRead(): Notification[] {
  if (typeof localStorage === 'undefined') return []
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as Notification[]
    return Array.isArray(parsed) ? parsed : []
  } catch { return [] }
}

function safeWrite(items: Notification[]) {
  if (typeof localStorage === 'undefined') return
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(items)) }
  catch { /* quota / private mode */ }
}

/**
 * Persistent notification inbox. Subscribes to the SSE bus and translates
 * cluster events into human-readable notifications. Bell icon in the header
 * renders the unread count + popover list.
 */
export const useNotificationsStore = defineStore('notifications', () => {
  const items = ref<Notification[]>(import.meta.client ? safeRead() : [])

  const unreadCount = computed(() => items.value.filter(n => !n.read).length)

  function persist() {
    safeWrite(items.value)
  }

  function add(n: Omit<Notification, 'id' | 'createdAt' | 'read'>) {
    const next: Notification = {
      ...n,
      id: uid(),
      createdAt: new Date().toISOString(),
      read: false,
    }
    items.value = [next, ...items.value].slice(0, MAX_NOTIFICATIONS)
    persist()
  }

  function markRead(id: string) {
    const item = items.value.find(n => n.id === id)
    if (!item || item.read) return
    item.read = true
    persist()
  }

  function markAllRead() {
    items.value.forEach(n => { n.read = true })
    persist()
  }

  function remove(id: string) {
    items.value = items.value.filter(n => n.id !== id)
    persist()
  }

  function clear() {
    items.value = []
    persist()
  }

  function handleEvent(event: CloudEvent) {
    try {
      switch (event.type) {
        case 'INSTANCE_CRASHED': {
          add({
            tone: 'destructive',
            title: `${event.instanceId} crashed`,
            description: event.classification ? `Classification: ${event.classification}` : undefined,
            route: `/instances/${encodeURIComponent(event.instanceId)}`,
          })
          break
        }
        case 'DEPLOYMENT_COMPLETED': {
          add({
            tone: 'success',
            title: `Deployment completed for ${event.groupName}`,
            route: `/groups/${encodeURIComponent(event.groupName)}`,
          })
          break
        }
        case 'DEPLOYMENT_ROLLED_BACK': {
          add({
            tone: 'warning',
            title: `Deployment rolled back for ${event.groupName}`,
            description: `Revision ${event.revision} reverted.`,
            route: `/groups/${encodeURIComponent(event.groupName)}`,
          })
          break
        }
        case 'NODE_CONNECTED': {
          add({
            tone: 'success',
            title: `${event.nodeId} connected`,
            route: `/nodes/${encodeURIComponent(event.nodeId)}`,
          })
          break
        }
        case 'NODE_DISCONNECTED': {
          add({
            tone: 'warning',
            title: `${event.nodeId} disconnected`,
            description: 'The daemon stopped reporting heartbeats.',
            route: `/nodes/${encodeURIComponent(event.nodeId)}`,
          })
          break
        }
        case 'GROUP_CRASH_LOOP': {
          add({
            tone: 'destructive',
            title: `${event.group} is crash-looping`,
            description: `${event.crashCount} crashes since ${new Date(event.windowStart).toLocaleTimeString()}.`,
            route: `/groups/${encodeURIComponent(event.group)}`,
          })
          break
        }
        case 'MAINTENANCE_UPDATED': {
          const enabled = event.globalEnabled
          add({
            tone: enabled ? 'warning' : 'primary',
            title: enabled ? 'Cluster maintenance enabled' : 'Cluster maintenance disabled',
            description: event.message || undefined,
            route: '/operations/maintenance',
          })
          break
        }
      }
    } catch {
      // swallow — never let an event payload break the bus
    }
  }

  let connected = false
  function connectSse() {
    if (connected) return
    connected = true
    const bus = useSseEventBus()
    bus.on([
      'INSTANCE_CRASHED',
      'DEPLOYMENT_COMPLETED', 'DEPLOYMENT_ROLLED_BACK',
      'NODE_CONNECTED', 'NODE_DISCONNECTED',
      'GROUP_CRASH_LOOP',
      'MAINTENANCE_UPDATED',
    ], handleEvent)
    bus.connect()
  }

  function disconnectSse() {
    if (!connected) return
    connected = false
    const bus = useSseEventBus()
    bus.off([
      'INSTANCE_CRASHED',
      'DEPLOYMENT_COMPLETED', 'DEPLOYMENT_ROLLED_BACK',
      'NODE_CONNECTED', 'NODE_DISCONNECTED',
      'GROUP_CRASH_LOOP',
      'MAINTENANCE_UPDATED',
    ], handleEvent)
  }

  return {
    items, unreadCount,
    add, markRead, markAllRead, remove, clear,
    connectSse, disconnectSse,
  }
})
