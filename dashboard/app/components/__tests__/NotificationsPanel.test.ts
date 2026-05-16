import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import NotificationsPanel from '../layout/NotificationsPanel.vue'
import type { Notification } from '~/stores/notifications'

const { store, markRead, markAllRead, clear } = vi.hoisted(() => ({
  store: { items: [] as Notification[], unreadCount: 0 },
  markRead: vi.fn(),
  markAllRead: vi.fn(),
  clear: vi.fn(),
}))

vi.mock('~/stores/notifications', () => ({
  useNotificationsStore: () => reactive(Object.assign(store, { markRead, markAllRead, clear })),
}))

function note(overrides: Partial<Notification> = {}): Notification {
  return {
    id: 'n1', tone: 'primary', title: 'Node connected',
    createdAt: new Date().toISOString(), read: false,
    ...overrides,
  }
}

// Reka UI Popover teleports content to <body>.
function bodyText() {
  return document.body.textContent ?? ''
}
function findBodyButton(text: string) {
  return Array.from(document.body.querySelectorAll('button')).find(
    b => b.textContent?.includes(text),
  )
}

// Popover content teleports to <body> and is not torn down between mounts, so
// each test tracks its wrapper and unmounts it afterwards to avoid stale DOM.
let active: { unmount: () => void } | null = null

async function mount() {
  const wrapper = await mountSuspended(NotificationsPanel)
  active = wrapper
  return wrapper
}

afterEach(() => {
  active?.unmount()
  active = null
})

beforeEach(() => {
  store.items = []
  store.unreadCount = 0
  markRead.mockReset()
  markAllRead.mockReset()
  clear.mockReset()
})

describe('NotificationsPanel', () => {
  it('hides the unread badge when there are no unread items', async () => {
    const wrapper = await mount()
    expect(wrapper.find('[aria-label="Unread count"]').exists()).toBe(false)
  })

  it('shows the unread count on the badge', async () => {
    store.unreadCount = 3
    const wrapper = await mount()
    expect(wrapper.find('[aria-label="Unread count"]').text()).toBe('3')
  })

  it('caps the unread badge at 99+', async () => {
    store.unreadCount = 150
    const wrapper = await mount()
    expect(wrapper.find('[aria-label="Unread count"]').text()).toBe('99+')
  })

  it('shows the empty state when the inbox is empty', async () => {
    const wrapper = await mount()
    await wrapper.find('button[aria-label="Notifications"]').trigger('click')
    expect(bodyText()).toContain('No notifications.')
  })

  it('renders notification title and description when items exist', async () => {
    store.items = [note({ title: 'lobby-1 crashed', description: 'Classification: OOM' })]
    const wrapper = await mount()
    await wrapper.find('button[aria-label="Notifications"]').trigger('click')
    expect(bodyText()).toContain('lobby-1 crashed')
    expect(bodyText()).toContain('Classification: OOM')
  })

  it('"Mark all read" delegates to the store', async () => {
    store.items = [note()]
    store.unreadCount = 1
    const wrapper = await mount()
    await wrapper.find('button[aria-label="Notifications"]').trigger('click')
    findBodyButton('Mark all read')!.click()
    expect(markAllRead).toHaveBeenCalled()
  })

  it('"Clear" delegates to the store', async () => {
    store.items = [note()]
    const wrapper = await mount()
    await wrapper.find('button[aria-label="Notifications"]').trigger('click')
    findBodyButton('Clear')!.click()
    expect(clear).toHaveBeenCalled()
  })

  // `navigateTo` is a Nuxt auto-import resolved at compile time — vi.stubGlobal
  // can't intercept it, so only the markRead side-effect is asserted here.
  it('clicking an item marks it read', async () => {
    store.items = [note({ id: 'x9', title: 'lobby-1 crashed', route: '/instances/lobby-1' })]
    const wrapper = await mount()
    await wrapper.find('button[aria-label="Notifications"]').trigger('click')
    const item = Array.from(document.body.querySelectorAll('button')).find(
      b => b.textContent?.includes('lobby-1 crashed'),
    )
    item!.click()
    expect(markRead).toHaveBeenCalledWith('x9')
  })
})
