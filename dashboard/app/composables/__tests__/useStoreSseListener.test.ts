import { describe, it, expect, vi, beforeEach } from 'vitest'

import { useStoreSseListener } from '../useStoreSseListener'

const { mockBusOn, mockBusOff, mockBusConnect } = vi.hoisted(() => ({
  mockBusOn: vi.fn(),
  mockBusOff: vi.fn(),
  mockBusConnect: vi.fn(),
}))

vi.mock('~/composables/useSseEventBus', () => ({
  useSseEventBus: () => ({ on: mockBusOn, off: mockBusOff, connect: mockBusConnect }),
}))

beforeEach(() => {
  mockBusOn.mockReset()
  mockBusOff.mockReset()
  mockBusConnect.mockReset()
})

describe('useStoreSseListener', () => {
  it('connect() subscribes the handler and connects the bus', () => {
    const handler = vi.fn()
    const { connect } = useStoreSseListener(['NODE_STATUS', 'NODE_CONNECTED'], handler)
    connect()
    expect(mockBusOn).toHaveBeenCalledWith(['NODE_STATUS', 'NODE_CONNECTED'], handler)
    expect(mockBusConnect).toHaveBeenCalledTimes(1)
  })

  it('connect() is idempotent — a second call does not re-subscribe', () => {
    const { connect } = useStoreSseListener(['NODE_STATUS'], vi.fn())
    connect()
    connect()
    expect(mockBusOn).toHaveBeenCalledTimes(1)
    expect(mockBusConnect).toHaveBeenCalledTimes(1)
  })

  it('disconnect() unsubscribes the handler', () => {
    const handler = vi.fn()
    const { connect, disconnect } = useStoreSseListener(['NODE_STATUS'], handler)
    connect()
    disconnect()
    expect(mockBusOff).toHaveBeenCalledWith(['NODE_STATUS'], handler)
  })

  it('disconnect() before connect() is a no-op', () => {
    const { disconnect } = useStoreSseListener(['NODE_STATUS'], vi.fn())
    disconnect()
    expect(mockBusOff).not.toHaveBeenCalled()
  })

  it('disconnect() is idempotent — a second call does not re-unsubscribe', () => {
    const { connect, disconnect } = useStoreSseListener(['NODE_STATUS'], vi.fn())
    connect()
    disconnect()
    disconnect()
    expect(mockBusOff).toHaveBeenCalledTimes(1)
  })

  it('connect() after disconnect() re-subscribes', () => {
    const { connect, disconnect } = useStoreSseListener(['NODE_STATUS'], vi.fn())
    connect()
    disconnect()
    connect()
    expect(mockBusOn).toHaveBeenCalledTimes(2)
    expect(mockBusConnect).toHaveBeenCalledTimes(2)
  })
})
