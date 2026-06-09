import { describe, it, expect, vi, beforeEach } from 'vitest'

import { useCapability } from '../useCapability'

const { mockGetAuthToken, mockBusOn, mockBusOff, mockBusConnect, fetchMock } = vi.hoisted(() => ({
  mockGetAuthToken: vi.fn(),
  mockBusOn: vi.fn(),
  mockBusOff: vi.fn(),
  mockBusConnect: vi.fn(),
  fetchMock: vi.fn(),
}))

vi.mock('~/lib/auth-storage', () => ({
  AUTH_TOKEN_KEY: 'auth_token',
  getAuthToken: mockGetAuthToken,
  setAuthToken: vi.fn(),
  clearAuthToken: vi.fn(),
}))

vi.mock('~/composables/useSseEventBus', () => ({
  useSseEventBus: () => ({ on: mockBusOn, off: mockBusOff, connect: mockBusConnect }),
}))

vi.stubGlobal('useRuntimeConfig', () => ({
  public: { apiBase: 'http://localhost:8080' },
}))
vi.stubGlobal('$fetch', fetchMock)

beforeEach(() => {
  mockGetAuthToken.mockReset()
  mockGetAuthToken.mockReturnValue('tok')
  fetchMock.mockReset()
  mockBusOn.mockReset()
  mockBusOff.mockReset()
  mockBusConnect.mockReset()
})

function lastHandler(): (e: unknown) => void {
  const calls = mockBusOn.mock.calls
  return calls[calls.length - 1]![1] as (e: unknown) => void
}

describe('useCapability', () => {
  it('subscribes to all three capability event types and connects the bus', () => {
    fetchMock.mockResolvedValue({ bindings: [] })
    useCapability('prexor.foo')
    expect(mockBusOn).toHaveBeenCalledWith(
      ['CAPABILITY_REGISTERED', 'CAPABILITY_UNREGISTERED', 'CAPABILITY_PROVIDER_CHANGED'],
      expect.any(Function),
    )
    expect(mockBusConnect).toHaveBeenCalledTimes(1)
  })

  it('returns null before the initial seed resolves', () => {
    fetchMock.mockResolvedValue({ bindings: [{ capabilityId: 'prexor.foo', version: '1.0', moduleId: 'foo-impl' }] })
    const binding = useCapability('prexor.foo')
    expect(binding.value).toBeNull()
  })

  it('hydrates from the initial /capabilities lookup', async () => {
    fetchMock.mockResolvedValue({
      bindings: [
        { capabilityId: 'prexor.foo', version: '1.0', moduleId: 'foo-impl' },
        { capabilityId: 'prexor.bar', version: '2.0', moduleId: 'bar-impl' },
      ],
    })
    const binding = useCapability('prexor.foo')
    // Initial fetch is a fire-and-forget promise inside the composable.
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    expect(binding.value).toEqual({ capabilityId: 'prexor.foo', version: '1.0', moduleId: 'foo-impl' })
  })

  it('initial seed of an unknown capability leaves the ref null', async () => {
    fetchMock.mockResolvedValue({ bindings: [] })
    const binding = useCapability('prexor.foo')
    await Promise.resolve(); await Promise.resolve()
    expect(binding.value).toBeNull()
  })

  it('skips the initial seed when no auth token is available', async () => {
    mockGetAuthToken.mockReturnValue(null)
    const binding = useCapability('prexor.foo')
    await Promise.resolve(); await Promise.resolve()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(binding.value).toBeNull()
  })

  it('swallows a /capabilities fetch failure — leaves binding null for SSE to repair', async () => {
    fetchMock.mockRejectedValue(new Error('network'))
    const binding = useCapability('prexor.foo')
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    expect(binding.value).toBeNull()
  })

  it('CAPABILITY_REGISTERED updates the binding when capabilityId matches', () => {
    fetchMock.mockResolvedValue({ bindings: [] })
    const binding = useCapability('prexor.foo')
    const handler = lastHandler()
    handler({
      type: 'CAPABILITY_REGISTERED',
      capabilityId: 'prexor.foo',
      version: '1.2',
      moduleId: 'new-impl',
    })
    expect(binding.value).toEqual({ capabilityId: 'prexor.foo', version: '1.2', moduleId: 'new-impl' })
  })

  it('CAPABILITY_REGISTERED for a different capabilityId is ignored', () => {
    fetchMock.mockResolvedValue({ bindings: [] })
    const binding = useCapability('prexor.foo')
    const handler = lastHandler()
    handler({
      type: 'CAPABILITY_REGISTERED',
      capabilityId: 'prexor.other',
      version: '1.0',
      moduleId: 'impl',
    })
    expect(binding.value).toBeNull()
  })

  it('CAPABILITY_UNREGISTERED clears the binding only when capabilityId matches', () => {
    fetchMock.mockResolvedValue({ bindings: [] })
    const binding = useCapability('prexor.foo')
    const handler = lastHandler()
    // Seed via REGISTERED first
    handler({ type: 'CAPABILITY_REGISTERED', capabilityId: 'prexor.foo', version: '1.0', moduleId: 'impl' })
    expect(binding.value).not.toBeNull()
    handler({ type: 'CAPABILITY_UNREGISTERED', capabilityId: 'prexor.other' })
    expect(binding.value).not.toBeNull()
    handler({ type: 'CAPABILITY_UNREGISTERED', capabilityId: 'prexor.foo' })
    expect(binding.value).toBeNull()
  })

  it('CAPABILITY_PROVIDER_CHANGED swaps the moduleId + version', () => {
    fetchMock.mockResolvedValue({ bindings: [] })
    const binding = useCapability('prexor.foo')
    const handler = lastHandler()
    handler({ type: 'CAPABILITY_REGISTERED', capabilityId: 'prexor.foo', version: '1.0', moduleId: 'old' })
    handler({
      type: 'CAPABILITY_PROVIDER_CHANGED',
      capabilityId: 'prexor.foo',
      fromVersion: '1.0',
      toVersion: '2.0',
      moduleId: 'new',
    })
    expect(binding.value).toEqual({ capabilityId: 'prexor.foo', version: '2.0', moduleId: 'new' })
  })
})
