import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

import { useCan } from '../useCan'

const { mockCan, mockCanAny } = vi.hoisted(() => ({
  mockCan: vi.fn(),
  mockCanAny: vi.fn(),
}))

mockNuxtImport('useAuthStore', () => {
  return () => ({
    can: mockCan,
    canAny: mockCanAny,
  })
})

describe('useCan', () => {
  beforeEach(() => {
    mockCan.mockReset()
    mockCanAny.mockReset()
  })

  it('delegates can() to auth store', () => {
    mockCan.mockReturnValue(true)
    const { can } = useCan()
    expect(can('audit.view')).toBe(true)
    expect(mockCan).toHaveBeenCalledWith('audit.view')
  })

  it('returns false when permission is missing', () => {
    mockCan.mockReturnValue(false)
    const { can } = useCan()
    expect(can('admin.delete')).toBe(false)
  })

  it('delegates canAny() to auth store', () => {
    mockCanAny.mockReturnValue(true)
    const { canAny } = useCan()
    expect(canAny('users.create', 'users.update')).toBe(true)
    expect(mockCanAny).toHaveBeenCalledWith('users.create', 'users.update')
  })

  it('returns false when no permissions match', () => {
    mockCanAny.mockReturnValue(false)
    const { canAny } = useCan()
    expect(canAny('admin.delete', 'admin.create')).toBe(false)
  })
})
