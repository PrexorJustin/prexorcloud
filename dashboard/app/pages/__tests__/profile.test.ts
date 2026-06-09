import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import Profile from '../profile.vue'

const { authStore, changePassword, toastSuccess, toastError } = vi.hoisted(() => ({
  authStore: {
    user: null as Record<string, unknown> | null,
  },
  changePassword: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

mockNuxtImport('useAuthStore', () => () => Object.assign(reactive(authStore), {
  changePassword,
  logout: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))

const fetchMock = vi.fn()
const apiPut = vi.fn()
const apiDelete = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ PUT: apiPut, DELETE: apiDelete }),
}))

function user(overrides: Record<string, unknown> = {}) {
  return {
    username: 'admin',
    role: 'OWNER',
    minecraftUuid: null,
    minecraftName: null,
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
  authStore.user = user()
  changePassword.mockReset().mockResolvedValue(undefined)
  toastSuccess.mockReset()
  toastError.mockReset()
  fetchMock.mockReset().mockResolvedValue({ id: 'abc', name: 'Notch' })
  apiPut.mockReset().mockResolvedValue({})
  apiDelete.mockReset().mockResolvedValue({})
  vi.stubGlobal('$fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('profile', () => {
  it('renders the account info with username and role', async () => {
    const wrapper = await mountSuspended(Profile)
    expect(wrapper.text()).toContain('Profile')
    expect(wrapper.text()).toContain('admin')
    expect(wrapper.text()).toContain('OWNER')
  })

  it('shows the unlinked Minecraft state with a username input', async () => {
    const wrapper = await mountSuspended(Profile)
    expect(wrapper.text()).toContain('Minecraft Account')
    expect(wrapper.find('input#mc-username').exists()).toBe(true)
  })

  it('shows the linked Minecraft state when the user has a linked account', async () => {
    authStore.user = user({ minecraftUuid: '1234-5678', minecraftName: 'Notch' })
    const wrapper = await mountSuspended(Profile)
    expect(wrapper.text()).toContain('Notch')
    expect(wrapper.text()).toContain('1234-5678')
    expect(wrapper.find('input#mc-username').exists()).toBe(false)
  })

  it('keeps the Change Password button disabled until the form is valid', async () => {
    const wrapper = await mountSuspended(Profile)
    const btn = wrapper.findAll('button').find(b => b.text().includes('Change Password'))!
    expect(btn.attributes('disabled')).toBeDefined()

    await wrapper.find('input#current-password').setValue('oldpass')
    await wrapper.find('input#new-password').setValue('newlongpass')
    await wrapper.find('input#confirm-password').setValue('newlongpass')
    await flush()
    expect(btn.attributes('disabled')).toBeUndefined()
  })

  it('shows a mismatch hint and skips the request when passwords differ', async () => {
    const wrapper = await mountSuspended(Profile)
    await wrapper.find('input#current-password').setValue('oldpass')
    await wrapper.find('input#new-password').setValue('newlongpass')
    await wrapper.find('input#confirm-password').setValue('different')
    await flush()
    expect(wrapper.text()).toContain('Passwords do not match')
    expect(changePassword).not.toHaveBeenCalled()
  })

  it('calls changePassword and clears the fields on a valid submit', async () => {
    const wrapper = await mountSuspended(Profile)
    await wrapper.find('input#current-password').setValue('oldpass')
    await wrapper.find('input#new-password').setValue('newlongpass')
    await wrapper.find('input#confirm-password').setValue('newlongpass')
    await flush()
    const btn = wrapper.findAll('button').find(b => b.text().includes('Change Password'))!
    await btn.trigger('click')
    await flush()
    expect(changePassword).toHaveBeenCalledWith('oldpass', 'newlongpass')
    expect((wrapper.find('input#current-password').element as HTMLInputElement).value).toBe('')
  })

  it('toasts an error when changePassword rejects', async () => {
    changePassword.mockRejectedValue(new Error('bad'))
    const wrapper = await mountSuspended(Profile)
    await wrapper.find('input#current-password').setValue('oldpass')
    await wrapper.find('input#new-password').setValue('newlongpass')
    await wrapper.find('input#confirm-password').setValue('newlongpass')
    await flush()
    const btn = wrapper.findAll('button').find(b => b.text().includes('Change Password'))!
    await btn.trigger('click')
    await flush()
    expect(toastError).toHaveBeenCalled()
  })

  it('links a Minecraft account via the Mojang lookup and the API client', async () => {
    const wrapper = await mountSuspended(Profile)
    await wrapper.find('input#mc-username').setValue('Notch')
    await flush()
    const linkBtn = wrapper.findAll('button').find(b => b.text().includes('Link Account'))!
    await linkBtn.trigger('click')
    await flush()
    expect(fetchMock).toHaveBeenCalled()
    expect(apiPut).toHaveBeenCalledWith('/api/v1/users/{username}/minecraft', expect.objectContaining({
      body: expect.objectContaining({ name: 'Notch' }),
    }))
    expect(toastSuccess).toHaveBeenCalled()
  })
})
