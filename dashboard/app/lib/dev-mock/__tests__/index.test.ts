import { describe, it, expect } from 'vitest'
import { isDevMockAvailable, DEV_MOCK_TOKEN, mockUser } from '../index'

describe('lib/dev-mock/index', () => {
  it('isDevMockAvailable reflects the Vite DEV flag', () => {
    expect(isDevMockAvailable()).toBe(import.meta.env.DEV)
  })

  it('re-exports the dev-mock token and user fixture', () => {
    expect(DEV_MOCK_TOKEN).toBe('dev-mock-token-v1')
    expect(mockUser).toMatchObject({ username: expect.any(String) })
  })
})
