import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import RolesIndex from '../roles/index.vue'
import type { RoleDefinition } from '~/stores/roles'

const { store } = vi.hoisted(() => ({
  store: {
    roles: [] as RoleDefinition[],
    loading: false,
    fetchRoles: vi.fn(() => Promise.resolve()),
    createRole: vi.fn(() => Promise.resolve()),
    updateRole: vi.fn(() => Promise.resolve()),
    deleteRole: vi.fn(() => Promise.resolve()),
  },
}))

mockNuxtImport('useRolesStore', () => () => reactive(store))

function role(over: Partial<RoleDefinition> = {}): RoleDefinition {
  return { name: 'ADMIN', permissions: ['users.view'], builtIn: true, userCount: 3, ...over }
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  store.roles = []
  store.loading = false
  store.fetchRoles.mockReset().mockResolvedValue(undefined)
  store.createRole.mockReset().mockResolvedValue(undefined)
  store.updateRole.mockReset().mockResolvedValue(undefined)
  store.deleteRole.mockReset().mockResolvedValue(undefined)
})

// DetailSheet / Dialog content teleports to <body> and is not torn down
// between mounts; clear it so each test only sees its own teleported nodes.
afterEach(() => {
  document.body.innerHTML = ''
})

describe('RolesIndex', () => {
  it('renders the header and fetches roles on mount', async () => {
    const wrapper = await mountSuspended(RolesIndex)
    await flush()
    expect(wrapper.text()).toContain('Roles')
    expect(store.fetchRoles).toHaveBeenCalled()
  })

  it('shows the empty state when no roles are defined', async () => {
    const wrapper = await mountSuspended(RolesIndex)
    await flush()
    expect(wrapper.text()).toContain('No roles defined')
  })

  it('renders a row per role with type and counts', async () => {
    store.roles = [
      role({ name: 'ADMIN', builtIn: true }),
      role({ name: 'CUSTOM_OPS', builtIn: false, permissions: ['nodes.view', 'nodes.edit'], userCount: 1 }),
    ]
    const wrapper = await mountSuspended(RolesIndex)
    await flush()
    expect(wrapper.text()).toContain('ADMIN')
    expect(wrapper.text()).toContain('CUSTOM_OPS')
    expect(wrapper.text()).toContain('Built-in')
    expect(wrapper.text()).toContain('Custom')
  })

  it('opening a built-in role shows the read-only notice', async () => {
    store.roles = [role({ name: 'ADMIN', builtIn: true })]
    const wrapper = await mountSuspended(RolesIndex)
    await flush()
    const row = wrapper.findAll('div').find(d => d.text().includes('ADMIN') && d.classes().includes('cursor-pointer'))!
    await row.trigger('click')
    await flush()
    expect(document.body.textContent).toContain("Built-in roles can't be edited or deleted")
  })

  it('editing a custom role and saving calls updateRole with the new permissions', async () => {
    store.roles = [role({ name: 'CUSTOM_OPS', builtIn: false, permissions: [] })]
    const wrapper = await mountSuspended(RolesIndex)
    await flush()
    const row = wrapper.findAll('div').find(d => d.text().includes('CUSTOM_OPS') && d.classes().includes('cursor-pointer'))!
    await row.trigger('click')
    await flush()
    // Toggle the first permission checkbox in the teleported sheet
    const checkbox = document.body.querySelector('[role="checkbox"]') as HTMLElement
    checkbox.click()
    await flush()
    const saveBtn = Array.from(document.body.querySelectorAll('button'))
      .find(b => b.textContent?.trim() === 'Save') as HTMLElement
    saveBtn.click()
    await flush()
    expect(store.updateRole).toHaveBeenCalledWith('CUSTOM_OPS', expect.objectContaining({ permissions: expect.any(Array) }))
  })

  it('create dialog rejects an invalid name and submits a valid one', async () => {
    const wrapper = await mountSuspended(RolesIndex)
    await flush()
    const createBtn = wrapper.findAll('button').find(b => b.text().includes('Create role'))!
    await createBtn.trigger('click')
    await flush()
    const nameInput = document.body.querySelector('#cr-name') as HTMLInputElement
    nameInput.value = 'invalid-name'
    nameInput.dispatchEvent(new Event('input'))
    await flush()
    // Submit button is disabled for invalid names; submitting the form is a no-op
    const form = document.body.querySelector('form') as HTMLFormElement
    form.dispatchEvent(new Event('submit'))
    await flush()
    expect(store.createRole).not.toHaveBeenCalled()

    nameInput.value = 'GOOD_ROLE'
    nameInput.dispatchEvent(new Event('input'))
    await flush()
    form.dispatchEvent(new Event('submit'))
    await flush()
    expect(store.createRole).toHaveBeenCalledWith(expect.objectContaining({ name: 'GOOD_ROLE' }))
  })

  it('filters the role list by search term', async () => {
    store.roles = [
      role({ name: 'ADMIN', builtIn: true }),
      role({ name: 'VIEWER', builtIn: false }),
    ]
    const wrapper = await mountSuspended(RolesIndex)
    await flush()
    const searchInput = wrapper.find('input')
    await searchInput.setValue('VIEWER')
    await flush()
    expect(wrapper.text()).toContain('VIEWER')
    expect(wrapper.text()).not.toContain('ADMIN')
  })

  it('shows the loading skeleton while the store is loading', async () => {
    store.loading = true
    const wrapper = await mountSuspended(RolesIndex)
    await flush()
    expect(wrapper.text()).not.toContain('No roles defined')
  })
})
