import { describe, it, expect } from 'vitest'
import { useDialogState } from '../useDialogState'

describe('useDialogState', () => {
  it('starts closed, idle, and error-free', () => {
    const { open, loading, error } = useDialogState()
    expect(open.value).toBe(false)
    expect(loading.value).toBe(false)
    expect(error.value).toBe('')
  })

  it('exposes mutable refs', () => {
    const { open, loading, error } = useDialogState()
    open.value = true
    loading.value = true
    error.value = 'boom'
    expect(open.value).toBe(true)
    expect(loading.value).toBe(true)
    expect(error.value).toBe('boom')
  })

  it('reset() clears error and loading but leaves open alone', () => {
    const { open, loading, error, reset } = useDialogState()
    open.value = true
    loading.value = true
    error.value = 'boom'
    reset()
    expect(open.value).toBe(true)
    expect(loading.value).toBe(false)
    expect(error.value).toBe('')
  })

  it('each call yields independent refs', () => {
    const a = useDialogState()
    const b = useDialogState()
    a.open.value = true
    a.error.value = 'a'
    expect(b.open.value).toBe(false)
    expect(b.error.value).toBe('')
  })
})
