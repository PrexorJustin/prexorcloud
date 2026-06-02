import { ref } from "vue"

/**
 * The trace id of the most recent API response that carried an `X-Trace-Id` header (Track D.3).
 * The controller sets that header on every traced request; the API client records it here so the
 * UI can deep-link to the trace of the action the operator just performed.
 */
export const lastTraceId = ref<string>("")

/** Record a trace id from a response header. No-op for empty/missing values. */
export function recordTraceId(id: string | null | undefined): void {
  if (id) lastTraceId.value = id
}

/**
 * Build a trace-UI deep link from the controller's configured template (which contains a literal
 * `{traceId}` placeholder). Returns null when there is no template or no trace id.
 */
export function buildTraceUrl(template: string | undefined, traceId: string): string | null {
  if (!template || !traceId) return null
  return template.replace("{traceId}", traceId)
}
