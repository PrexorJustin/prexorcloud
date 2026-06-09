import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import AddNodeDialog from '../nodes/AddNodeDialog.vue'

const { postMock, fetchNodes, toastSuccess, writeText } = vi.hoisted(() => ({
  postMock: vi.fn(),
  fetchNodes: vi.fn(),
  toastSuccess: vi.fn(),
  writeText: vi.fn(),
}))

vi.mock('vue-sonner', () => ({ toast: { success: toastSuccess, error: vi.fn() } }))
vi.mock('~/composables/useApiClient', () => ({ useApiClient: () => ({ POST: postMock }) }))
vi.mock('~/stores/nodes', () => ({ useNodesStore: () => ({ fetchNodes }) }))

let active: { unmount: () => void } | null = null
afterEach(() => {
  active?.unmount()
  active = null
  document.body.innerHTML = ''
})

beforeEach(() => {
  postMock.mockReset().mockResolvedValue({ data: { joinToken: 'jt-abc' } })
  fetchNodes.mockReset().mockResolvedValue(undefined)
  toastSuccess.mockReset()
  writeText.mockReset().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
})

async function mountOpen() {
  const wrapper = await mountSuspended(AddNodeDialog)
  active = wrapper
  await wrapper.find('button').trigger('click')
  return wrapper
}

function bodyInput() {
  return document.body.querySelector('#node-name') as HTMLInputElement
}
function generateButton() {
  return Array.from(document.body.querySelectorAll('button'))
    .find((b) => b.textContent?.includes('Generate & Copy Token'))!
}

describe('AddNodeDialog', () => {
  it('renders the trigger button', async () => {
    const wrapper = await mountSuspended(AddNodeDialog)
    active = wrapper
    expect(wrapper.text()).toContain('Generate Token')
  })

  it('clicking the trigger opens the dialog', async () => {
    await mountOpen()
    expect(document.body.textContent).toContain('Generate Join Token')
  })

  it('defaults the TTL display to 1 hour and reflects preset clicks', async () => {
    await mountOpen()
    expect(document.body.textContent).toContain('1 hours')
    const preset30 = Array.from(document.body.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === '30m')!
    preset30.click()
    await new Promise((r) => setTimeout(r))
    expect(document.body.textContent).toContain('30 minutes')
  })

  it('disables the generate button until a node name is entered', async () => {
    await mountOpen()
    expect(generateButton().getAttribute('disabled')).not.toBeNull()
    const input = bodyInput()
    input.value = 'node-1'
    input.dispatchEvent(new Event('input'))
    await new Promise((r) => setTimeout(r))
    expect(generateButton().getAttribute('disabled')).toBeNull()
  })

  it('generates a token, copies it, refreshes nodes and toasts on success', async () => {
    await mountOpen()
    const input = bodyInput()
    input.value = 'node-1'
    input.dispatchEvent(new Event('input'))
    await new Promise((r) => setTimeout(r))
    generateButton().click()
    await new Promise((r) => setTimeout(r))
    expect(postMock).toHaveBeenCalledWith('/api/v1/admin/tokens', {
      body: { nodeId: 'node-1', ttlSeconds: 3600 },
    })
    expect(writeText).toHaveBeenCalledWith('jt-abc')
    expect(fetchNodes).toHaveBeenCalled()
    expect(toastSuccess).toHaveBeenCalled()
  })

  it('shows an error message when token generation fails', async () => {
    postMock.mockRejectedValue(new Error('boom'))
    await mountOpen()
    const input = bodyInput()
    input.value = 'node-1'
    input.dispatchEvent(new Event('input'))
    await new Promise((r) => setTimeout(r))
    generateButton().click()
    await new Promise((r) => setTimeout(r))
    expect(document.body.textContent).toContain('Failed to generate join token')
  })
})
