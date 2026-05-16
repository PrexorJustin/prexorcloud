import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

// STEP 14a — Vite library build. Output is a single ESM chunk staged by the
// prexorcloud.module convention plugin into META-INF/frontend/ of the module
// jar. The dashboard lazy-loads it via `import('/modules/<id>/frontend/index.js')`,
// which means `@prexorcloud/module-sdk` and `vue` must be treated as external —
// the host dashboard provides them via its import map.
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    cssCodeSplit: false,
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      name: 'ExamplePlaytimeModule',
      formats: ['es'],
      fileName: () => 'index.js',
    },
    rollupOptions: {
      external: ['@prexorcloud/module-sdk', 'vue'],
      output: {
        assetFileNames: (asset) =>
          asset.name && asset.name.endsWith('.css') ? 'index.css' : 'assets/[name][extname]',
      },
    },
  },
})
