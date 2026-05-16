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
