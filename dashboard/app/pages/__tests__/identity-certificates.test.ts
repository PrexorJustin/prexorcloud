import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import type { RevokedCert } from '~/stores/certificates'
import Certificates from '../identity/certificates.vue'

const { certificatesStore, toastSuccess, toastError } = vi.hoisted(() => ({
  certificatesStore: {
    revoked: [] as RevokedCert[],
    loading: false,
    fetchRevoked: vi.fn(),
    revoke: vi.fn(),
    unrevoke: vi.fn(),
  },
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

mockNuxtImport('useCertificatesStore', () => () => reactive(certificatesStore))
vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))

function cert(overrides: Partial<RevokedCert> = {}): RevokedCert {
  return {
    nodeId: 'node-east-1',
    serial: 'AA:BB:CC',
    revokedAt: new Date().toISOString(),
    reason: 'suspected compromise',
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
  document.body.innerHTML = ''
  certificatesStore.revoked = []
  certificatesStore.loading = false
  certificatesStore.fetchRevoked.mockReset().mockResolvedValue(undefined)
  certificatesStore.revoke.mockReset().mockResolvedValue(undefined)
  certificatesStore.unrevoke.mockReset().mockResolvedValue(undefined)
  toastSuccess.mockReset()
  toastError.mockReset()
})

describe('identity/certificates', () => {
  it('renders the header and warning callout, and fetches the revocation list on mount', async () => {
    const wrapper = await mountSuspended(Certificates)
    expect(wrapper.text()).toContain('Certificates')
    expect(wrapper.text()).toContain('Revoking blocks the daemon')
    expect(certificatesStore.fetchRevoked).toHaveBeenCalledTimes(1)
  })

  it('shows the empty state when no certificates are revoked', async () => {
    const wrapper = await mountSuspended(Certificates)
    expect(wrapper.text()).toContain('No revoked certificates')
  })

  it('renders a row per revoked certificate', async () => {
    certificatesStore.revoked = [cert({ nodeId: 'node-a' }), cert({ nodeId: 'node-b' })]
    const wrapper = await mountSuspended(Certificates)
    expect(wrapper.text()).toContain('node-a')
    expect(wrapper.text()).toContain('node-b')
  })

  it('filters the rows by the search field', async () => {
    certificatesStore.revoked = [cert({ nodeId: 'node-a' }), cert({ nodeId: 'node-b' })]
    const wrapper = await mountSuspended(Certificates)
    await wrapper.find('input').setValue('node-a')
    await flush()
    expect(wrapper.text()).toContain('node-a')
    expect(wrapper.text()).not.toContain('node-b')
  })

  it('revokes a certificate through the revoke dialog', async () => {
    const wrapper = await mountSuspended(Certificates)
    const openBtn = wrapper.findAll('button').find(b => b.text().includes('Revoke certificate'))!
    await openBtn.trigger('click')
    await flush()
    const node = document.body.querySelector('#rc-node') as HTMLInputElement
    node.value = 'node-east-9'
    node.dispatchEvent(new Event('input', { bubbles: true }))
    const reason = document.body.querySelector('#rc-reason') as HTMLTextAreaElement
    reason.value = 'leaked key'
    reason.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    const form = document.body.querySelector('form') as HTMLFormElement
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flush()
    expect(certificatesStore.revoke).toHaveBeenCalledWith('node-east-9', 'leaked key')
  })

  it('keeps the dialog submit disabled until a node id is entered', async () => {
    const wrapper = await mountSuspended(Certificates)
    const openBtn = wrapper.findAll('button').find(b => b.text().includes('Revoke certificate'))!
    await openBtn.trigger('click')
    await flush()
    const submit = document.body.querySelector('button[type="submit"]') as HTMLButtonElement
    expect(submit.disabled).toBe(true)
    const node = document.body.querySelector('#rc-node') as HTMLInputElement
    node.value = 'node-x'
    node.dispatchEvent(new Event('input', { bubbles: true }))
    await flush()
    expect(submit.disabled).toBe(false)
  })

  it('unrevokes a certificate from its row action', async () => {
    certificatesStore.revoked = [cert({ nodeId: 'node-unrevoke' })]
    const wrapper = await mountSuspended(Certificates)
    const unrevokeBtn = wrapper.find('button[title="Unrevoke (allow reconnect)"]')
    await unrevokeBtn.trigger('click')
    await flush()
    expect(certificatesStore.unrevoke).toHaveBeenCalledWith('node-unrevoke')
  })
})
