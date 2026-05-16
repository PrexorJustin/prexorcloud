import { toast } from 'vue-sonner'

/**
 * Helpers for the loading + try/catch/toast/finally boilerplate that every
 * Pinia store wrapped around its API calls. Use the variants that match the
 * store's needs:
 *
 * - {@link withLoading}: wraps a fetch with loading flag + toast on failure.
 *   Returns void; caller assigns to its own ref inside `fn`.
 * - {@link withMutation}: wraps a mutation (POST/PUT/PATCH/DELETE) with a
 *   success toast on resolve and an error toast on reject. Re-throws the
 *   original error so callers can chain.
 *
 * Both keep the toast-message strings explicit at call site so the failure
 * notification stays grep-able.
 */

import type { Ref } from 'vue'

export async function withLoading(
  loading: Ref<boolean>,
  errorMsg: string,
  fn: () => Promise<void>,
): Promise<void> {
  loading.value = true
  try {
    await fn()
  } catch {
    toast.error(errorMsg)
  } finally {
    loading.value = false
  }
}

export async function withMutation<T>(
  fn: () => Promise<T>,
  opts: {
    success: { message: string; description?: string }
    errorMsg: string
    /** Run after success (typically `await fetchX()`). */
    onSuccess?: () => Promise<unknown> | void
  },
): Promise<T> {
  try {
    const result = await fn()
    if (opts.onSuccess) await opts.onSuccess()
    toast.success(opts.success.message, opts.success.description ? { description: opts.success.description } : undefined)
    return result
  } catch (err) {
    toast.error(opts.errorMsg)
    throw err
  }
}
