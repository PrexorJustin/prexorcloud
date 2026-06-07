import { defineVitestConfig } from '@nuxt/test-utils/config'

export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    setupFiles: ['./tests/vitest-setup.ts'],
    include: ['app/**/*.{test,spec}.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      include: ['app/**/*.{ts,vue}'],
      exclude: [
        'app/components/ui/**',
        'app/**/*.test.ts',
        'app/**/*.spec.ts',
        'app/**/*.story.vue',
      ],
    },
  },
})
