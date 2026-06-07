import { describe, it, expect, vi, beforeEach } from 'vitest'
import { ref } from 'vue'

import { toast } from 'vue-sonner'
import { withLoading, withMutation } from '../useStoreCrud'

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

beforeEach(() => {
  vi.mocked(toast.success).mockReset()
  vi.mocked(toast.error).mockReset()
})

describe('withLoading', () => {
  it('toggles loading around success', async () => {
    const loading = ref(false)
    let observed = false
    await withLoading(loading, 'err', async () => {
      observed = loading.value
    })
    expect(observed).toBe(true)
    expect(loading.value).toBe(false)
  })

  it('clears loading and toasts on rejection without throwing', async () => {
    const loading = ref(false)
    await withLoading(loading, 'load failed', async () => {
      throw new Error('boom')
    })
    expect(loading.value).toBe(false)
    expect(toast.error).toHaveBeenCalledWith('load failed')
  })
})

describe('withMutation', () => {
  it('resolves with the result, runs onSuccess, then toasts success', async () => {
    const onSuccess = vi.fn().mockResolvedValue(undefined)
    const order: string[] = []
    const fn = vi.fn().mockImplementation(async () => {
      order.push('fn')
      return 'value'
    })
    onSuccess.mockImplementation(async () => { order.push('onSuccess') })
    vi.mocked(toast.success).mockImplementation(() => { order.push('toast'); return '' })

    const result = await withMutation(fn, {
      success: { message: 'ok' },
      errorMsg: 'no',
      onSuccess,
    })

    expect(result).toBe('value')
    expect(order).toEqual(['fn', 'onSuccess', 'toast'])
  })

  it('passes description to toast.success when provided', async () => {
    await withMutation(async () => 1, {
      success: { message: 'created', description: 'item X' },
      errorMsg: 'failed',
    })
    expect(toast.success).toHaveBeenCalledWith('created', { description: 'item X' })
  })

  it('omits the options arg to toast.success when no description', async () => {
    await withMutation(async () => 1, {
      success: { message: 'created' },
      errorMsg: 'failed',
    })
    expect(toast.success).toHaveBeenCalledWith('created', undefined)
  })

  it('toasts error and re-throws on rejection', async () => {
    const err = new Error('boom')
    await expect(
      withMutation(async () => { throw err }, {
        success: { message: 'ok' },
        errorMsg: 'mutation failed',
      }),
    ).rejects.toBe(err)
    expect(toast.error).toHaveBeenCalledWith('mutation failed')
  })

  it('does not run onSuccess on failure', async () => {
    const onSuccess = vi.fn()
    await expect(
      withMutation(async () => { throw new Error('boom') }, {
        success: { message: 'ok' },
        errorMsg: 'no',
        onSuccess,
      }),
    ).rejects.toThrow('boom')
    expect(onSuccess).not.toHaveBeenCalled()
    expect(toast.success).not.toHaveBeenCalled()
  })
})
