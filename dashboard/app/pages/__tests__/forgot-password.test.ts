import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ForgotPassword from '../auth/forgot-password.vue'

mockNuxtImport('useRuntimeConfig', () => () => ({
  app: { baseURL: '/', buildId: 'test' },
  public: { apiBase: 'http://api.test' },
}))

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset().mockResolvedValue({ ok: true })
  vi.stubGlobal('fetch', fetchMock)
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

async function setEmail(wrapper: Awaited<ReturnType<typeof mountSuspended>>, value: string) {
  await wrapper.find('input#email').setValue(value)
  await flush()
}

describe('ForgotPassword', () => {
  it('renders the title, subtitle and the email field', async () => {
    const wrapper = await mountSuspended(ForgotPassword)
    expect(wrapper.text()).toContain('Reset your password')
    expect(wrapper.text()).toContain("We'll email you a link")
    expect(wrapper.find('input#email').exists()).toBe(true)
    const back = wrapper.findAll('a').find(a => a.text().includes('Back to sign in'))
    expect(back?.attributes('href')).toBe('/login')
  })

  it('shows a validation error and skips the request on an invalid email', async () => {
    const wrapper = await mountSuspended(ForgotPassword)
    await setEmail(wrapper, 'not-an-email')
    await wrapper.find('form').trigger('submit')
    await flush()
    expect(wrapper.text()).toContain('Enter a valid email address')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('POSTs the reset request and swaps to the sent message on a valid submit', async () => {
    const wrapper = await mountSuspended(ForgotPassword)
    await setEmail(wrapper, 'user@example.com')
    await wrapper.find('form').trigger('submit')
    await flush()
    expect(fetchMock).toHaveBeenCalledWith(
      'http://api.test/api/v1/auth/password-reset/request',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ email: 'user@example.com' }),
      }),
    )
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).toContain('a password-reset link has been sent')
  })

  it('shows the sending state while the request is in flight', async () => {
    let resolve!: () => void
    fetchMock.mockReturnValue(new Promise<{ ok: boolean }>((r) => {
      resolve = () => r({ ok: true })
    }))
    const wrapper = await mountSuspended(ForgotPassword)
    await setEmail(wrapper, 'user@example.com')
    await wrapper.find('form').trigger('submit')
    await flush()
    expect(wrapper.text()).toContain('Sending…')
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined()
    resolve()
    await flush()
  })
})
