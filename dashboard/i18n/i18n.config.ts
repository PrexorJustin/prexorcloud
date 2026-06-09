import en from './locales/en.json'
import de from './locales/de.json'

// Messages are bundled (not lazy-loaded files) so they are available
// synchronously everywhere — including the Vitest `nuxt` environment, where
// async locale-file imports are never awaited.
export default defineI18nConfig(() => ({
  legacy: false,
  fallbackLocale: 'en',
  messages: { en, de },
}))
