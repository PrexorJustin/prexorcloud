// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2025-05-15',

  ssr: false,

  modules: [
    '@pinia/nuxt',
    '@nuxtjs/color-mode',
    '@nuxtjs/i18n',
    '@vueuse/nuxt',
    '@nuxt/eslint',
    'vue-sonner/nuxt',
    '~/modules/sdk-bridge',
  ],

  colorMode: {
    classSuffix: '',
    preference: 'system',
    fallback: 'dark',
  },

  i18n: {
    defaultLocale: 'en',
    strategy: 'no_prefix',
    vueI18n: './i18n.config.ts',
    locales: [
      { code: 'en', name: 'English', language: 'en-US' },
      { code: 'de', name: 'Deutsch', language: 'de-DE' },
    ],
    detectBrowserLanguage: {
      useCookie: true,
      cookieKey: 'prexorcloud_locale',
      redirectOn: 'root',
      fallbackLocale: 'en',
    },
  },

  css: ['~/assets/css/main.css'],

  postcss: {
    plugins: {
      '@tailwindcss/postcss': {},
    },
  },

  runtimeConfig: {
    public: {
      apiBase: process.env.NODE_ENV === 'development' ? '' : (process.env.NUXT_PUBLIC_API_BASE ?? 'http://localhost:8080'),
    },
  },

  nitro: {
    devProxy: {
      '/api/v1': {
        target: 'http://localhost:8080/api/v1',
        changeOrigin: true,
      },
      '/metrics': {
        target: 'http://localhost:8080/metrics',
        changeOrigin: true,
      },
    },
  },

  devtools: { enabled: true },

  app: {
    head: {
      // SPA (ssr:false) ships a static index.html; without an explicit lang the
      // document fails WCAG 3.1.1 until i18n hydrates. @nuxtjs/i18n keeps this in
      // sync with the active locale after hydration; this is the pre-hydration default.
      htmlAttrs: { lang: 'en' },
      title: 'PrexorCloud',
      meta: [
        { name: 'description', content: 'Self-hosted control plane for Minecraft server fleets.' },
        { name: 'theme-color', content: '#06b6d4' },
        { property: 'og:title', content: 'PrexorCloud' },
        { property: 'og:description', content: 'Self-hosted control plane for Minecraft server fleets.' },
        { property: 'og:type', content: 'website' },
        { property: 'og:image', content: '/logo-dark.svg' },
        { name: 'twitter:card', content: 'summary' },
        { name: 'twitter:image', content: '/logomark.svg' },
      ],
      script: [
        // Import map for module SDK — must be before any <script type="module">.
        // Maps @prexorcloud/module-sdk to the auto-generated bridge file.
        {
          type: 'importmap',
          innerHTML: JSON.stringify({
            imports: {
              '@prexorcloud/module-sdk': '/sdk/prexorcloud.mjs',
            },
          }),
        },
      ],
      link: [
        { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' },
        { rel: 'apple-touch-icon', href: '/logomark.svg' },
        { rel: 'preconnect', href: 'https://fonts.googleapis.com' },
        { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' },
        {
          rel: 'stylesheet',
          href: 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Inter+Tight:wght@500;600;700&family=Instrument+Serif:ital@0;1&family=JetBrains+Mono:wght@400;500;600;700&display=swap',
        },
      ],
    },
  },

  vite: {
    define: {
      // Bridge the build-time shell flag into the client bundle as a plain
      // global the Vite `define` plugin inlines verbatim. Vite treats
      // `import.meta.env` specially and ignores a raw define on its sub-keys,
      // and it only sources import.meta.env from .env files (not shell vars) —
      // so `VITE_DEV_MOCK=1 nuxt build` never reached the client before this,
      // the dev-mock plugin short-circuited, and the authed-axe scan saw only
      // /login (a false green). This is always inlined (true or false), so no
      // ReferenceError; unset in release builds → false → the dev-mock tree
      // shakes out. See app/lib/dev-mock/enabled.ts.
      __DEV_MOCK__: JSON.stringify(process.env.VITE_DEV_MOCK === '1'),
    },
  },

  typescript: {
    strict: true,
  },

  components: {
    dirs: [
      {
        path: '~/components',
        ignore: ['ui/**/index.ts'],
      },
    ],
  },
})
