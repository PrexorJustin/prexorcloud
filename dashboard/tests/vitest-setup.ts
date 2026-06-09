// Vitest setup — runs before every test file in the `nuxt` environment.
//
// i18n: the `@nuxtjs/i18n` runtime installs a vue-i18n instance in the Nuxt
// test app, but its message catalogue is never populated (locale loading is
// not exercised by `@nuxt/test-utils`). Install a fully-populated vue-i18n
// instance as a global test plugin so `useI18n().t` resolves real strings —
// components assert on rendered copy, not keys.
//
// Node 25 ships a `globalThis.localStorage` stub that is an empty object
// without the Storage API (no `setItem`, `clear`, etc.) and Nuxt's test
// env happens to leave that stub in place instead of mounting happy-dom's
// implementation. Tests that touch `localStorage` (auth, useStorage, the
// pinia stores) explode with `localStorage.clear is not a function`.
//
// Install a minimal in-memory polyfill that mirrors the standard Storage
// interface. Cheap, deterministic, no persistence across files.

import { vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { config } from '@vue/test-utils'
import en from '../i18n/locales/en.json'
import de from '../i18n/locales/de.json'

const testI18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en, de },
})

// `~/lib/translate` reaches for `useNuxtApp().$i18n`, whose catalogue is empty
// in the test env. Back it with the populated instance so store/composable
// tests keep asserting real toast copy.
vi.mock('~/lib/translate', async () => {
  const { createI18n: make } = await import('vue-i18n')
  const enMsg = (await import('../i18n/locales/en.json')).default
  const deMsg = (await import('../i18n/locales/de.json')).default
  const i = make({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMsg, de: deMsg } })
  return {
    t: (key: string, named?: Record<string, unknown>, plural?: number) => {
      if (plural !== undefined) return i.global.t(key, named ?? {}, plural)
      return named ? i.global.t(key, named) : i.global.t(key)
    },
  }
})

// `@nuxtjs/i18n` augments the composer with `locales` / `setLocale`; mirror
// the minimum surface the dashboard components use so tests don't crash.
const globalComposer = testI18n.global as unknown as Record<string, unknown>
globalComposer.locales = ref([
  { code: 'en', name: 'English' },
  { code: 'de', name: 'Deutsch' },
])
globalComposer.setLocale = (code: 'en' | 'de') => {
  testI18n.global.locale.value = code
}

config.global.plugins.push(testI18n)

function inMemoryStorage(): Storage {
  let store: Record<string, string> = {}
  return {
    get length() { return Object.keys(store).length },
    clear() { store = {} },
    getItem(key: string) { return Object.prototype.hasOwnProperty.call(store, key) ? store[key]! : null },
    key(index: number) { return Object.keys(store)[index] ?? null },
    removeItem(key: string) { delete store[key] },
    setItem(key: string, value: string) { store[key] = String(value) },
  }
}

const needsPolyfill = (value: unknown): boolean => {
  if (value == null) return true
  const obj = value as { setItem?: unknown; getItem?: unknown; clear?: unknown }
  return typeof obj.setItem !== 'function' || typeof obj.getItem !== 'function' || typeof obj.clear !== 'function'
}

// ECharts (zrender) renders to a real <canvas> 2D context. The `nuxt` test
// environment has no canvas backend, so `canvas.getContext('2d')` returns null
// and zrender's `Layer.initContext` crashes setting `.dpr` on null — surfacing
// as an *unhandled* async error (the first paint flush fires after a test that
// mounted a Sparkline/chart has already settled). Hand zrender a no-op 2D
// context stub so headless chart components render without a canvas backend.
const canvasProto = (globalThis as { HTMLCanvasElement?: { prototype: HTMLCanvasElement } }).HTMLCanvasElement?.prototype
if (canvasProto) {
  const noop = () => {}
  canvasProto.getContext = function (this: HTMLCanvasElement, type: string) {
    if (type !== '2d') return null
    return new Proxy(
      { canvas: this },
      {
        get: (target: Record<string, unknown>, prop: string) => {
          if (prop in target) return target[prop]
          if (prop === 'measureText') return () => ({ width: 0 })
          if (prop === 'getImageData') return () => ({ data: new Uint8ClampedArray(4) })
          if (prop === 'createLinearGradient' || prop === 'createRadialGradient' || prop === 'createPattern') {
            return () => ({ addColorStop: noop })
          }
          return noop
        },
        set: () => true,
      },
    ) as unknown as CanvasRenderingContext2D
  } as typeof canvasProto.getContext
}

if (needsPolyfill((globalThis as { localStorage?: Storage }).localStorage)) {
  Object.defineProperty(globalThis, 'localStorage', {
    value: inMemoryStorage(),
    writable: true,
    configurable: true,
  })
}
if (needsPolyfill((globalThis as { sessionStorage?: Storage }).sessionStorage)) {
  Object.defineProperty(globalThis, 'sessionStorage', {
    value: inMemoryStorage(),
    writable: true,
    configurable: true,
  })
}
