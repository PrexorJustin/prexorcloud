import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ResetPassword from '../auth/reset-password.vue'

const { routeQuery, toastSuccess } = vi.hoisted(() => ({
  routeQuery: { value: {} as Record<string, string> },
  toastSuccess: vi.fn(),
}))

mockNuxtImport('useRuntimeConfig', () => () => ({
  app: { baseURL: '/', buildId: 'test' },
  public: { apiBase: 'http://api.test' },
}))
mockNuxtImport('useRoute', () => () => ({ query: routeQuery.value }))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: vi.fn() } }))

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset().mockResolvedValue({ ok: true })
  vi.stubGlobal('fetch', fetchMock)
  toastSuccess.mockReset()
  routeQuery.value = { token: 'reset-tok' }
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

async function submit(wrapper: Awaited<ReturnType<typeof mountSuspended>>, pwd: string, confirm: string) {
  await wrapper.find('input#newPassword').setValue(pwd)
  await wrapper.find('input#confirmPassword').setValue(confirm)
  await flush()
  await wrapper.find('form').trigger('submit')
  await flush()
}

describe('ResetPassword', () => {
  it('renders the two password fields when a token is present', async () => {
    const wrapper = await mountSuspended(ResetPassword)
    expect(wrapper.text()).toContain('Set a new password')
    expect(wrapper.find('input#newPassword').exists()).toBe(true)
    expect(wrapper.find('input#confirmPassword').exists()).toBe(true)
  })

  it('shows the missing-token state and a request-new-link when no token is in the query', async () => {
    routeQuery.value = {}
    const wrapper = await mountSuspended(ResetPassword)
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).toContain('This reset link is missing the required token')
    const link = wrapper.findAll('a').find(a => a.text().includes('Request a new link'))
    expect(link?.attributes('href')).toBe('/auth/forgot-password')
  })

  it('rejects a password shorter than 8 characters without calling fetch', async () => {
    const wrapper = await mountSuspended(ResetPassword)
    await submit(wrapper, 'short', 'short')
    expect(wrapper.text()).toContain('Use at least 8 characters')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects mismatched passwords without calling fetch', async () => {
    const wrapper = await mountSuspended(ResetPassword)
    await submit(wrapper, 'longenough1', 'different1')
    expect(wrapper.text()).toContain('Passwords do not match')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('POSTs the new password and shows the completed state with a success toast', async () => {
    const wrapper = await mountSuspended(ResetPassword)
    await submit(wrapper, 'longenough1', 'longenough1')
    expect(fetchMock).toHaveBeenCalledWith(
      'http://api.test/api/v1/auth/password-reset/complete',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ token: 'reset-tok', newPassword: 'longenough1' }),
      }),
    )
    expect(wrapper.text()).toContain('Your password has been updated')
    expect(toastSuccess).toHaveBeenCalled()
  })

  it('surfaces the invalid-token message when the controller returns INVALID_TOKEN', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      json: () => Promise.resolve({ error: { code: 'INVALID_TOKEN' } }),
    })
    const wrapper = await mountSuspended(ResetPassword)
    await submit(wrapper, 'longenough1', 'longenough1')
    expect(wrapper.text()).toContain('This reset link is invalid or has expired')
  })

  it('surfaces the server message for a non-coded error response', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      json: () => Promise.resolve({ error: { message: 'Server says no' } }),
    })
    const wrapper = await mountSuspended(ResetPassword)
    await submit(wrapper, 'longenough1', 'longenough1')
    expect(wrapper.text()).toContain('Server says no')
  })

  it('surfaces the network-error message when fetch rejects', async () => {
    fetchMock.mockRejectedValue(new Error('boom'))
    const wrapper = await mountSuspended(ResetPassword)
    await submit(wrapper, 'longenough1', 'longenough1')
    expect(wrapper.text()).toContain("Can't reach the controller")
  })
})
