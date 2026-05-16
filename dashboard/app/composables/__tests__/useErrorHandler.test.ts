import { describe, it, expect, vi } from 'vitest'
import { useErrorHandler } from '../useErrorHandler'

import { toast } from 'vue-sonner'

// Mock vue-sonner toast
vi.mock('vue-sonner', () => ({
  toast: {
    error: vi.fn(),
    warning: vi.fn(),
    success: vi.fn(),
    info: vi.fn(),
  },
}))

describe('useErrorHandler', () => {
  describe('classify', () => {
    it('classifies 401 as auth error', () => {
      const { classify } = useErrorHandler()
      const result = classify({ statusCode: 401, message: 'Unauthorized' })
      expect(result.category).toBe('auth')
      expect(result.retryable).toBe(false)
    })

    it('classifies 403 as auth error', () => {
      const { classify } = useErrorHandler()
      const result = classify({ statusCode: 403, message: 'Forbidden' })
      expect(result.category).toBe('auth')
    })

    it('classifies 400 as validation error', () => {
      const { classify } = useErrorHandler()
      const result = classify({ statusCode: 400, message: 'Bad request' })
      expect(result.category).toBe('validation')
      expect(result.retryable).toBe(false)
    })

    it('classifies 422 as validation error', () => {
      const { classify } = useErrorHandler()
      const result = classify({ statusCode: 422, message: 'Validation failed' })
      expect(result.category).toBe('validation')
    })

    it('classifies 429 as retryable server error', () => {
      const { classify } = useErrorHandler()
      const result = classify({ statusCode: 429 })
      expect(result.category).toBe('server')
      expect(result.retryable).toBe(true)
      expect(result.message).toContain('Too many requests')
    })

    it('classifies 500+ as retryable server error', () => {
      const { classify } = useErrorHandler()
      const result = classify({ statusCode: 500, message: 'Internal error' })
      expect(result.category).toBe('server')
      expect(result.retryable).toBe(true)
    })

    it('classifies 502 as server error', () => {
      const { classify } = useErrorHandler()
      const result = classify({ statusCode: 502, message: 'Bad gateway' })
      expect(result.category).toBe('server')
    })

    it('classifies unknown status codes as unknown', () => {
      const { classify } = useErrorHandler()
      const result = classify({ statusCode: 418, message: 'Teapot' })
      expect(result.category).toBe('unknown')
      expect(result.retryable).toBe(false)
    })

    it('extracts nested error message', () => {
      const { classify } = useErrorHandler()
      const result = classify({
        statusCode: 400,
        data: { error: { message: 'Name is required' } },
      })
      expect(result.message).toBe('Name is required')
    })

    it('classifies TypeError with fetch as network error', () => {
      const { classify } = useErrorHandler()
      const result = classify(new TypeError('Failed to fetch'))
      expect(result.category).toBe('network')
      expect(result.retryable).toBe(true)
    })

    it('classifies TypeError with network as network error', () => {
      const { classify } = useErrorHandler()
      const result = classify(new TypeError('network error'))
      expect(result.category).toBe('network')
    })

    it('classifies plain Error as unknown', () => {
      const { classify } = useErrorHandler()
      const result = classify(new Error('Something broke'))
      expect(result.category).toBe('unknown')
      expect(result.message).toBe('Something broke')
    })

    it('classifies string error as unknown', () => {
      const { classify } = useErrorHandler()
      const result = classify('plain string error')
      expect(result.category).toBe('unknown')
      expect(result.message).toBe('plain string error')
    })
  })

  describe('handle', () => {
    it('does not toast for auth errors', () => {
      const { handle } = useErrorHandler()
      vi.mocked(toast.error).mockClear()
      handle({ statusCode: 401, message: 'Unauthorized' })
      expect(toast.error).not.toHaveBeenCalled()
      expect(toast.warning).not.toHaveBeenCalled()
    })

    it('toasts error for network errors', () => {
      const { handle } = useErrorHandler()
      vi.mocked(toast.error).mockClear()
      handle(new TypeError('Failed to fetch'))
      expect(toast.error).toHaveBeenCalledWith("Can't reach the controller", expect.any(Object))
    })

    it('toasts warning for validation errors', () => {
      const { handle } = useErrorHandler()
      vi.mocked(toast.warning).mockClear()
      handle({ statusCode: 422, message: 'Invalid input' }, 'Create group')
      expect(toast.warning).toHaveBeenCalledWith('Create group failed', expect.objectContaining({
        description: expect.stringContaining('Invalid input'),
      }))
    })

    it('toasts error for server errors', () => {
      const { handle } = useErrorHandler()
      vi.mocked(toast.error).mockClear()
      handle({ statusCode: 500, message: 'Internal error' })
      expect(toast.error).toHaveBeenCalledWith('Controller error', expect.any(Object))
    })

    it('returns classified error', () => {
      const { handle } = useErrorHandler()
      const result = handle({ statusCode: 500, message: 'err' })
      expect(result.category).toBe('server')
    })
  })

  describe('withErrorHandling', () => {
    it('returns result on success', async () => {
      const { withErrorHandling } = useErrorHandler()
      const result = await withErrorHandling(() => Promise.resolve(42))
      expect(result).toBe(42)
    })

    it('returns undefined on error and toasts', async () => {
      const { withErrorHandling } = useErrorHandler()
      vi.mocked(toast.error).mockClear()
      const result = await withErrorHandling(() => Promise.reject({ statusCode: 500, message: 'fail' }))
      expect(result).toBeUndefined()
      expect(toast.error).toHaveBeenCalled()
    })
  })
})
