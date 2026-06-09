// Translator for use *outside* Vue component setup — Pinia store actions,
// SSE handlers, plain modules. Components should use `useI18n()` directly.
//
// Backed by the `@nuxtjs/i18n` global composer (`nuxtApp.$i18n`), so it shares
// the active locale and message catalogue with the rest of the app.
import type { Composer } from 'vue-i18n'

export function t(key: string, named?: Record<string, unknown>, plural?: number): string {
  const i18n = useNuxtApp().$i18n as Composer
  if (plural !== undefined) return i18n.t(key, named ?? {}, plural)
  return named ? i18n.t(key, named) : i18n.t(key)
}
