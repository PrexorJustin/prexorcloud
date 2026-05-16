import { toast } from 'vue-sonner'
import { t } from '~/lib/translate'

export type ErrorCategory = 'network' | 'auth' | 'validation' | 'server' | 'unknown'

interface ClassifiedError {
  category: ErrorCategory
  message: string
  statusCode?: number
  retryable: boolean
}

/**
 * Centralized error handler. Classifies errors and shows toasts whose copy
 * names a fix per the design system rule: never just "Connection failed".
 */
export function useErrorHandler() {
  function classify(err: unknown): ClassifiedError {
    if (err && typeof err === 'object' && 'statusCode' in err) {
      const statusCode = (err as { statusCode: number }).statusCode
      const message = (err as { data?: { error?: { message?: string } } }).data?.error?.message
        || (err as { message?: string }).message
        || 'No detail provided.'

      if (statusCode === 401 || statusCode === 403) {
        return { category: 'auth', message, statusCode, retryable: false }
      }
      if (statusCode === 422 || statusCode === 400) {
        return { category: 'validation', message, statusCode, retryable: false }
      }
      if (statusCode === 429) {
        return {
          category: 'server',
          message: t('errorHandler.tooManyRequests'),
          statusCode,
          retryable: true,
        }
      }
      if (statusCode >= 500) {
        return { category: 'server', message, statusCode, retryable: true }
      }
      return { category: 'unknown', message, statusCode, retryable: false }
    }

    if (err instanceof TypeError && (err.message.includes('fetch') || err.message.includes('network'))) {
      return {
        category: 'network',
        message: t('errorHandler.networkMessage'),
        retryable: true,
      }
    }

    const message = err instanceof Error ? err.message : String(err)
    return { category: 'unknown', message, retryable: false }
  }

  /**
   * Show a toast for the error. `context` is the imperative action that failed
   * (e.g. "Start instance"); it becomes the toast title.
   */
  function handle(err: unknown, context?: string): ClassifiedError {
    const classified = classify(err)

    // Auth failures are handled globally (redirect via useApi).
    if (classified.category === 'auth') return classified

    const title = context ? t('errorHandler.actionFailed', { context }) : titleFor(classified.category)

    switch (classified.category) {
      case 'network':
        toast.error(title, { description: classified.message })
        break
      case 'validation':
        toast.warning(title, { description: classified.message })
        break
      case 'server':
        toast.error(title, {
          description: classified.retryable
            ? `${classified.message}\n${t('errorHandler.retrySuffix')}`
            : classified.message,
        })
        break
      default:
        toast.error(title, { description: classified.message })
    }

    return classified
  }

  function titleFor(category: ErrorCategory): string {
    switch (category) {
      case 'network':    return t('errorHandler.title.network')
      case 'validation': return t('errorHandler.title.validation')
      case 'server':     return t('errorHandler.title.server')
      default:           return t('errorHandler.title.unknown')
    }
  }

  /**
   * Wrap an async operation with error handling.
   */
  async function withErrorHandling<T>(
    fn: () => Promise<T>,
    context?: string,
  ): Promise<T | undefined> {
    try {
      return await fn()
    } catch (err) {
      handle(err, context)
      return undefined
    }
  }

  return { classify, handle, withErrorHandling }
}
