import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import InstanceConsole from '../instances/InstanceConsole.vue'

const { getMock, postMock, authToken, EventSourceMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
  authToken: { value: 'tok-123' as string | null },
  EventSourceMock: vi.fn(),
}))

// xterm + its addons touch the real DOM/canvas — stub them out entirely.
vi.mock('@xterm/xterm/css/xterm.css', () => ({}))
vi.mock('@xterm/xterm', () => ({
  Terminal: class { loadAddon() {} open() {} writeln() {} dispose() {} },
}))
vi.mock('@xterm/addon-fit', () => ({ FitAddon: class { fit() {} } }))
vi.mock('@xterm/addon-web-links', () => ({ WebLinksAddon: class {} }))
vi.mock('~/lib/auth-storage', () => ({ getAuthToken: () => authToken.value }))
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: getMock, POST: postMock }),
}))

// Nuxt's own plugins read runtimeConfig.app.baseURL, so the stub keeps `app`.
mockNuxtImport('useRuntimeConfig', () => () => ({
  app: { baseURL: '/', buildId: 'test' },
  public: { apiBase: 'http://api.test' },
}))

class FakeEventSource {
  onopen: (() => void) | null = null
  onmessage: ((e: { data: string }) => void) | null = null
  onerror: (() => void) | null = null
  close = vi.fn()
  constructor(url: string) { EventSourceMock(url) }
}

vi.stubGlobal('EventSource', FakeEventSource)
vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
vi.stubGlobal('$fetch', vi.fn().mockResolvedValue({ ticket: 'ticket-1' }))

beforeEach(() => {
  getMock.mockReset().mockResolvedValue({ data: { lines: [] } })
  postMock.mockReset().mockResolvedValue({})
  EventSourceMock.mockReset()
  authToken.value = 'tok-123'
})

async function flush() {
  await new Promise(r => setTimeout(r))
}

describe('InstanceConsole', () => {
  it('renders the console header and starts in the Disconnected state', async () => {
    const wrapper = await mountSuspended(InstanceConsole, { props: { instanceId: 'lobby-1' } })
    expect(wrapper.text()).toContain('Console')
    expect(wrapper.text()).toContain('Disconnected')
  })

  it('disables the Send button until a command is typed', async () => {
    const wrapper = await mountSuspended(InstanceConsole, { props: { instanceId: 'lobby-1' } })
    const send = wrapper.find('button[aria-label="Send command"]')
    expect(send.attributes('disabled')).toBeDefined()
    await wrapper.find('input').setValue('say hi')
    expect(send.attributes('disabled')).toBeUndefined()
  })

  it('POSTs the command and clears the input on send', async () => {
    const wrapper = await mountSuspended(InstanceConsole, { props: { instanceId: 'lobby-1' } })
    const input = wrapper.find('input')
    await input.setValue('stop')
    await wrapper.find('button[aria-label="Send command"]').trigger('click')
    await flush()
    expect(postMock).toHaveBeenCalledWith('/api/v1/services/{id}/command', {
      params: { path: { id: 'lobby-1' } },
      body: { command: 'stop' },
    })
    expect((input.element as HTMLInputElement).value).toBe('')
  })

  it('does not POST when the command is blank', async () => {
    const wrapper = await mountSuspended(InstanceConsole, { props: { instanceId: 'lobby-1' } })
    await wrapper.find('input').setValue('   ')
    await wrapper.find('input').trigger('keydown.enter')
    await flush()
    expect(postMock).not.toHaveBeenCalled()
  })

  it('recalls the previous command from history on ArrowUp', async () => {
    const wrapper = await mountSuspended(InstanceConsole, { props: { instanceId: 'lobby-1' } })
    const input = wrapper.find('input')
    await input.setValue('list')
    await input.trigger('keydown.enter')
    await flush()
    expect((input.element as HTMLInputElement).value).toBe('')
    await input.trigger('keydown', { key: 'ArrowUp' })
    expect((wrapper.vm as unknown as { command: string }).command).toBe('list')
  })

  it('opens an EventSource against the apiBase when a token is present', async () => {
    await mountSuspended(InstanceConsole, { props: { instanceId: 'lobby-1' } })
    await flush()
    expect(EventSourceMock).toHaveBeenCalled()
    expect(EventSourceMock.mock.calls[0]![0]).toContain('http://api.test')
  })

  it('does not open an EventSource when no auth token is stored', async () => {
    authToken.value = null
    await mountSuspended(InstanceConsole, { props: { instanceId: 'lobby-1' } })
    await flush()
    expect(EventSourceMock).not.toHaveBeenCalled()
  })
})
