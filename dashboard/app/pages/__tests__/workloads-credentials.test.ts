import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import WorkloadCredentials from '../workloads/credentials.vue'
import type { WorkloadCredential } from '~/stores/workloadCredentials'

const { credState, fetchCredentials, revokeCredential, revokeAllForInstance, toastSuccess } = vi.hoisted(() => ({
  credState: { credentials: [] as WorkloadCredential[], loading: false },
  fetchCredentials: vi.fn(),
  revokeCredential: vi.fn(),
  revokeAllForInstance: vi.fn(),
  toastSuccess: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: vi.fn(), info: vi.fn() } }))
vi.mock('~/stores/workloadCredentials', () => ({
  useWorkloadCredentialsStore: () => ({
    get credentials() { return credState.credentials },
    get loading() { return credState.loading },
    fetchCredentials,
    revokeCredential,
    revokeAllForInstance,
  }),
}))

function cred(overrides: Partial<WorkloadCredential> = {}): WorkloadCredential {
  return {
    tokenId: 'tok-1', instanceId: 'lobby-1', group: 'lobby', node: 'node-a',
    issuedAt: new Date().toISOString(), expiresAt: null,
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
  credState.credentials = []
  credState.loading = false
  fetchCredentials.mockReset()
  revokeCredential.mockReset().mockResolvedValue(undefined)
  revokeAllForInstance.mockReset().mockResolvedValue(undefined)
  toastSuccess.mockReset()
})

describe('workloads/credentials page', () => {
  it('renders the header and fetches credentials on mount', async () => {
    const wrapper = await mountSuspended(WorkloadCredentials)
    expect(wrapper.text()).toContain('Workload credentials')
    expect(fetchCredentials).toHaveBeenCalled()
  })

  it('shows the empty state when no credentials are issued', async () => {
    const wrapper = await mountSuspended(WorkloadCredentials)
    expect(wrapper.text()).toContain('No credentials issued')
  })

  it('renders a row per credential', async () => {
    credState.credentials = [
      cred({ tokenId: 'tok-1', instanceId: 'lobby-1' }),
      cred({ tokenId: 'tok-2', instanceId: 'survival-1' }),
    ]
    const wrapper = await mountSuspended(WorkloadCredentials)
    expect(wrapper.text()).toContain('tok-1')
    expect(wrapper.text()).toContain('tok-2')
    expect(wrapper.text()).toContain('survival-1')
  })

  it('filters the table by the search field', async () => {
    credState.credentials = [
      cred({ tokenId: 'tok-1', instanceId: 'lobby-1' }),
      cred({ tokenId: 'tok-2', instanceId: 'survival-1' }),
    ]
    const wrapper = await mountSuspended(WorkloadCredentials)
    await wrapper.find('input').setValue('survival')
    await flush()
    expect(wrapper.text()).toContain('survival-1')
    expect(wrapper.text()).not.toContain('lobby-1')
  })

  it('revokes a single credential through the store from its row action', async () => {
    credState.credentials = [cred({ tokenId: 'tok-1' })]
    const wrapper = await mountSuspended(WorkloadCredentials)
    const btn = wrapper.find('button[aria-label="Revoke this credential"]')
    await btn.trigger('click')
    await flush()
    expect(revokeCredential).toHaveBeenCalledWith('tok-1')
  })

  it('revokes all credentials for an instance from the row action', async () => {
    credState.credentials = [cred({ tokenId: 'tok-1', instanceId: 'lobby-1' })]
    const wrapper = await mountSuspended(WorkloadCredentials)
    const btn = wrapper.find('button[title="Revoke all credentials for lobby-1"]')
    await btn.trigger('click')
    await flush()
    expect(revokeAllForInstance).toHaveBeenCalledWith('lobby-1')
  })

  it('bulk-revokes selected credentials and toasts success', async () => {
    credState.credentials = [
      cred({ tokenId: 'tok-1' }),
      cred({ tokenId: 'tok-2' }),
    ]
    const wrapper = await mountSuspended(WorkloadCredentials)
    // select-all checkbox in the header row
    const selectAll = wrapper.find('[aria-label="Select all credentials"]')
    await selectAll.trigger('click')
    await flush()
    const revokeBtn = wrapper.findAll('button').find(b => b.text().trim() === 'Revoke')
    await revokeBtn!.trigger('click')
    await flush()
    expect(revokeCredential).toHaveBeenCalledTimes(2)
    expect(toastSuccess).toHaveBeenCalledWith('2 credentials revoked')
  })

  it('shows the loading skeleton while the store is loading', async () => {
    credState.loading = true
    const wrapper = await mountSuspended(WorkloadCredentials)
    expect(wrapper.text()).not.toContain('No credentials issued')
    expect(wrapper.find('input').exists()).toBe(true)
  })
})
