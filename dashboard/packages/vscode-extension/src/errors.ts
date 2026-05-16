export function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}

/** Best-effort human string for an openapi-fetch `error` payload (controller ErrorResponse). */
export function describeApiError(error: unknown): string {
  if (error && typeof error === 'object') {
    const body = error as { message?: string; error?: { message?: string; code?: string } }
    if (body.error?.message) return body.error.message
    if (body.error?.code) return body.error.code
    if (body.message) return body.message
  }
  return 'unknown error'
}
