import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'

import Login from '../login.vue'

const { routeQuery, login, toastSuccess } = vi.hoisted(() => ({
  routeQuery: { value: {} as Record<string, string> },
  login: vi.fn(),
  toastSuccess: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => ({ query: routeQuery.value }))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: vi.fn() } }))
vi.mock('~/stores/auth', () => ({
  useAuthStore: () => ({ login, token: null, user: null }),
}))

beforeEach(() => {
  login.mockReset().mockResolvedValue(undefined)
  toastSuccess.mockReset()
  routeQuery.value = {}
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

async function submit(
  wrapper: Awaited<ReturnType<typeof mountSuspended>>,
  username: string,
  password: string,
) {
  await wrapper.find('input#username').setValue(username)
  await wrapper.find('input#password').setValue(password)
  await flush()
  await wrapper.find('form').trigger('submit')
  await flush()
}

describe('Login', () => {
  it('renders the title, subtitle and credential fields', async () => {
    const wrapper = await mountSuspended(Login)
    expect(wrapper.text()).toContain('Sign in')
    expect(wrapper.text()).toContain('Welcome back')
    expect(wrapper.find('input#username').exists()).toBe(true)
    expect(wrapper.find('input#password').exists()).toBe(true)
    const forgot = wrapper.findAll('a').find(a => a.text().includes('Forgot your password?'))
    expect(forgot?.attributes('href')).toBe('/auth/forgot-password')
  })

  it('toggles the password field between hidden and visible', async () => {
    const wrapper = await mountSuspended(Login)
    expect(wrapper.find('input#password').attributes('type')).toBe('password')
    await wrapper.find('button[aria-label="Toggle password visibility"]').trigger('click')
    expect(wrapper.find('input#password').attributes('type')).toBe('text')
  })

  it('shows validation errors and skips the login call on an empty submit', async () => {
    const wrapper = await mountSuspended(Login)
    // touch then clear both fields so the required-string rule fires
    await wrapper.find('input#username').setValue('x')
    await wrapper.find('input#password').setValue('x')
    await flush()
    await wrapper.find('input#username').setValue('')
    await wrapper.find('input#password').setValue('')
    await flush()
    await wrapper.find('form').trigger('submit')
    await flush()
    expect(wrapper.text()).toContain('Enter your username')
    expect(wrapper.text()).toContain('Enter your password')
    expect(login).not.toHaveBeenCalled()
  })

  it('calls the auth store login with the entered credentials on a valid submit', async () => {
    const wrapper = await mountSuspended(Login)
    await submit(wrapper, 'alice', 'secret-pw')
    expect(login).toHaveBeenCalledWith({ username: 'alice', password: 'secret-pw' })
  })

  it('shows the signing-in state while the login request is in flight', async () => {
    let resolve!: () => void
    login.mockReturnValue(new Promise<void>((r) => { resolve = r }))
    const wrapper = await mountSuspended(Login)
    await submit(wrapper, 'alice', 'secret-pw')
    expect(wrapper.text()).toContain('Signing in…')
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined()
    resolve()
    await flush()
  })

  it('stays on the form without crashing when login rejects', async () => {
    login.mockRejectedValue(new Error('bad creds'))
    const wrapper = await mountSuspended(Login)
    await submit(wrapper, 'alice', 'wrong-pw')
    expect(login).toHaveBeenCalled()
    expect(wrapper.find('form').exists()).toBe(true)
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })
})
