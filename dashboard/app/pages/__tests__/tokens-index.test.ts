import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TokensIndex from '../tokens/index.vue'
import type { AdminToken } from '~/stores/adminTokens'

const { store } = vi.hoisted(() => ({
  store: {
    tokens: [] as AdminToken[],
    loading: false,
    fetchTokens: vi.fn(() => Promise.resolve()),
    generateToken: vi.fn(() => Promise.resolve(null as AdminToken | null)),
    revokeToken: vi.fn(() => Promise.resolve()),
  },
}))

mockNuxtImport('useAdminTokensStore', () => () => reactive(store))

function token(over: Partial<AdminToken> = {}): AdminToken {
  return {
    tokenId: 'tok-1',
    nodeId: 'node-east-1',
    expiresAt: new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString(),
    createdAt: new Date().toISOString(),
    ...over,
  }
}

async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await new Promise(r => setTimeout(r))
  }
}

beforeEach(() => {
  store.tokens = []
  store.loading = false
  store.fetchTokens.mockReset().mockResolvedValue(undefined)
  store.generateToken.mockReset().mockResolvedValue(null)
  store.revokeToken.mockReset().mockResolvedValue(undefined)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('TokensIndex', () => {
  it('renders the header and fetches tokens on mount', async () => {
    const wrapper = await mountSuspended(TokensIndex)
    await flush()
    expect(wrapper.text()).toContain('Tokens')
    expect(wrapper.text()).toContain('Single-use join tokens')
    expect(store.fetchTokens).toHaveBeenCalled()
  })

  it('shows the empty state when there are no active tokens', async () => {
    const wrapper = await mountSuspended(TokensIndex)
    await flush()
    expect(wrapper.text()).toContain('No active tokens')
  })

  it('renders a row per token with id and node', async () => {
    store.tokens = [
      token({ tokenId: 'tok-1', nodeId: 'node-east-1' }),
      token({ tokenId: 'tok-2', nodeId: null }),
    ]
    const wrapper = await mountSuspended(TokensIndex)
    await flush()
    expect(wrapper.text()).toContain('tok-1')
    expect(wrapper.text()).toContain('tok-2')
    expect(wrapper.text()).toContain('node-east-1')
  })

  it('shows an Expired badge for a past expiry', async () => {
    store.tokens = [token({ expiresAt: new Date(Date.now() - 1000).toISOString() })]
    const wrapper = await mountSuspended(TokensIndex)
    await flush()
    expect(wrapper.text()).toContain('Expired')
  })

  it('revoking a token calls the store', async () => {
    store.tokens = [token({ tokenId: 'tok-1' })]
    const wrapper = await mountSuspended(TokensIndex)
    await flush()
    const revokeBtn = wrapper.find('button[aria-label="Revoke token"]')
    await revokeBtn.trigger('click')
    await flush()
    expect(store.revokeToken).toHaveBeenCalledWith('tok-1')
  })

  it('generating a token shows the issued one-time secret', async () => {
    store.generateToken.mockResolvedValue(token({ tokenId: 'new', joinToken: 'RAW-SECRET-123' }))
    const wrapper = await mountSuspended(TokensIndex)
    await flush()
    const openBtn = wrapper.findAll('button').find(b => b.text().includes('Generate token'))!
    await openBtn.trigger('click')
    await flush()
    const nodeInput = document.body.querySelector('#ct-nodeId') as HTMLInputElement
    nodeInput.value = 'node-pending-1'
    nodeInput.dispatchEvent(new Event('input'))
    await flush()
    const form = document.body.querySelector('form') as HTMLFormElement
    form.dispatchEvent(new Event('submit'))
    await flush()
    expect(store.generateToken).toHaveBeenCalledWith({ nodeId: 'node-pending-1' })
    expect(document.body.textContent).toContain('Copy this token now')
    expect(document.body.textContent).toContain('RAW-SECRET-123')
  })

  it('copy button writes the issued token to the clipboard', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    store.generateToken.mockResolvedValue(token({ tokenId: 'new', joinToken: 'RAW-SECRET-123' }))
    const wrapper = await mountSuspended(TokensIndex)
    await flush()
    const openBtn = wrapper.findAll('button').find(b => b.text().includes('Generate token'))!
    await openBtn.trigger('click')
    await flush()
    const form = document.body.querySelector('form') as HTMLFormElement
    form.dispatchEvent(new Event('submit'))
    await flush()
    const copyBtn = Array.from(document.body.querySelectorAll('button'))
      .find(b => b.textContent?.includes('Copy')) as HTMLElement
    copyBtn.click()
    await flush()
    expect(writeText).toHaveBeenCalledWith('RAW-SECRET-123')
  })

  it('filters tokens by search term', async () => {
    store.tokens = [
      token({ tokenId: 'alpha-token', nodeId: 'node-a' }),
      token({ tokenId: 'beta-token', nodeId: 'node-b' }),
    ]
    const wrapper = await mountSuspended(TokensIndex)
    await flush()
    await wrapper.find('input').setValue('alpha')
    await flush()
    expect(wrapper.text()).toContain('alpha-token')
    expect(wrapper.text()).not.toContain('beta-token')
  })
})
