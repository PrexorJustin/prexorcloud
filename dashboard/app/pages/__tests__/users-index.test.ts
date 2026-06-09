import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'

import UsersIndex from '../users/index.vue'

const {
  usersState, rolesState,
  fetchUsers, fetchRoles, createUser, updateUser, deleteUser,
  toastSuccess,
} = vi.hoisted(() => ({
  usersState: { users: [] as unknown[], loading: false },
  rolesState: { roles: [] as { name: string }[] },
  fetchUsers: vi.fn(),
  fetchRoles: vi.fn(),
  createUser: vi.fn(),
  updateUser: vi.fn(),
  deleteUser: vi.fn(),
  toastSuccess: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: vi.fn() } }))
vi.mock('~/stores/users', () => ({
  useUsersStore: () => ({
    get users() { return usersState.users },
    get loading() { return usersState.loading },
    fetchUsers, createUser, updateUser, deleteUser,
  }),
}))
vi.mock('~/stores/roles', () => ({
  useRolesStore: () => ({
    get roles() { return rolesState.roles },
    fetchRoles,
  }),
}))

const sampleUser = {
  username: 'alice', email: 'alice@example.com', role: 'ADMIN',
  permissions: ['users.read'], createdAt: '2026-01-01T00:00:00Z', lastLogin: null,
}

beforeEach(() => {
  usersState.users = []
  usersState.loading = false
  rolesState.roles = [{ name: 'ADMIN' }, { name: 'VIEWER' }]
  fetchUsers.mockReset().mockResolvedValue(undefined)
  fetchRoles.mockReset().mockResolvedValue(undefined)
  createUser.mockReset().mockResolvedValue(undefined)
  updateUser.mockReset().mockResolvedValue(undefined)
  deleteUser.mockReset().mockResolvedValue(undefined)
  toastSuccess.mockReset()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

describe('UsersIndex', () => {
  it('renders the page header and loads users plus roles on mount', async () => {
    const wrapper = await mountSuspended(UsersIndex)
    expect(wrapper.text()).toContain('Users')
    expect(wrapper.text()).toContain('People with dashboard access')
    expect(fetchUsers).toHaveBeenCalled()
    expect(fetchRoles).toHaveBeenCalled()
  })

  it('shows the empty state with a create CTA when there are no users', async () => {
    const wrapper = await mountSuspended(UsersIndex)
    expect(wrapper.text()).toContain('No users yet')
    expect(wrapper.text()).toContain('Create the first user to grant dashboard access')
  })

  it('renders a row per user once loaded', async () => {
    usersState.users = [sampleUser]
    const wrapper = await mountSuspended(UsersIndex)
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('alice@example.com')
    expect(wrapper.text()).toContain('ADMIN')
    expect(wrapper.text()).toContain('Never')
  })

  it('filters the table by the search box', async () => {
    usersState.users = [sampleUser, { username: 'bob', email: null, role: 'VIEWER' }]
    const wrapper = await mountSuspended(UsersIndex)
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('bob')
    await wrapper.find('input').setValue('alice')
    await flush()
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).not.toContain('bob')
  })

  it('shows the no-matches empty state for a search that hits nothing', async () => {
    usersState.users = [sampleUser]
    const wrapper = await mountSuspended(UsersIndex)
    await wrapper.find('input').setValue('zzz-nobody')
    await flush()
    expect(wrapper.text()).toContain('No matches')
  })

  it('opens the create dialog and submits a new user through the store', async () => {
    const wrapper = await mountSuspended(UsersIndex)
    const createBtn = wrapper.findAll('button').find(b => b.text().includes('Create user'))
    await createBtn!.trigger('click')
    await flush()
    const usernameInput = document.body.querySelector('#cu-username') as HTMLInputElement
    const passwordInput = document.body.querySelector('#cu-password') as HTMLInputElement
    expect(usernameInput).toBeTruthy()
    usernameInput.value = 'carol'
    usernameInput.dispatchEvent(new Event('input'))
    passwordInput.value = 'longenoughpw'
    passwordInput.dispatchEvent(new Event('input'))
    await flush()
    const form = document.body.querySelector('form') as HTMLFormElement
    form.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }))
    await flush()
    expect(createUser).toHaveBeenCalledWith(
      expect.objectContaining({ username: 'carol', password: 'longenoughpw', role: 'VIEWER' }),
    )
    document.body.innerHTML = ''
  })
})
