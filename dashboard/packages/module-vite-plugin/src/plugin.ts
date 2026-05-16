import type { Plugin, PluginOption } from 'vite'
import type { OutputBundle, OutputChunk, OutputAsset } from 'rollup'
import { createHash } from 'node:crypto'
import { createRequire } from 'node:module'
import { dirname, relative, resolve } from 'node:path'
import { mkdirSync, writeFileSync } from 'node:fs'
import type { PrexorModulePluginOptions } from './types'

const pluginRequire = createRequire(import.meta.url)

function contentHash(content: string | Uint8Array): string {
  return createHash('sha256').update(content).digest('hex').slice(0, 8)
}

/**
 * Resolve the filesystem path of `@prexorcloud/module-sdk/theme.css`.
 * Resolves from the consumer's project root so that module frontends used
 * outside the dashboard workspace can find the package in their own
 * node_modules rather than in the plugin's directory.
 */
function resolveThemeCss(consumerRoot: string): string {
  // Resolve from the consumer project so the link: symlink in node_modules is found
  const consumerRequire = createRequire(`${consumerRoot}/package.json`)
  try {
    return consumerRequire.resolve('@prexorcloud/module-sdk/theme.css')
  } catch {
    try {
      return resolve(
        dirname(consumerRequire.resolve('@prexorcloud/module-sdk/package.json')),
        'src',
        'theme.css',
      )
    } catch {
      // Last-resort: resolve relative to this plugin's own package (monorepo dev)
      return resolve(
        dirname(pluginRequire.resolve('@prexorcloud/module-sdk/package.json')),
        'src',
        'theme.css',
      )
    }
  }
}

export function prexorModulePlugin(options: PrexorModulePluginOptions): PluginOption[] {
  const {
    name,
    displayName,
    icon = null,
    permissions = [],
    routes,
    events = [],
    entry = 'src/main.ts',
  } = options

  // Auto-include Vue SFC support — module devs don't need to add it manually
  let vuePlugin: any
  try {
    const vue = pluginRequire('@vitejs/plugin-vue')
    vuePlugin = (vue.default ?? vue)()
  } catch {
    // Fallback: not installed (shouldn't happen since it's a dependency)
  }

  let themeCssPath: string
  let projectRoot: string

  const prexorPlugin: Plugin = {
    name: 'prexor-module',
    enforce: 'pre' as const,

    configResolved(config) {
      projectRoot = config.root
      themeCssPath = resolveThemeCss(config.root)
    },

    config() {
      // Dynamically import @tailwindcss/postcss — it's a dependency of this plugin
      let tailwindPlugin: any
      try {
        tailwindPlugin = pluginRequire('@tailwindcss/postcss')
      } catch {
        // Not installed — Tailwind features will be unavailable
      }

      return {
        define: {
          __PREXORCLOUD_MODULE_NAME__: JSON.stringify(name),
        },
        resolve: {
          alias: {
            'vue': '@prexorcloud/module-sdk',
            'pinia': '@prexorcloud/module-sdk',
          },
        },
        build: {
          lib: {
            entry,
            formats: ['es'],
            fileName: 'index',
          },
          rollupOptions: {
            external: ['@prexorcloud/module-sdk'],
            output: {
              inlineDynamicImports: true,
            },
          },
          cssCodeSplit: false,
        },
        // Auto-configure PostCSS with Tailwind — zero config for module devs
        ...(tailwindPlugin ? {
          css: {
            postcss: {
              plugins: [tailwindPlugin()],
            },
          },
        } : {}),
      }
    },

    resolveId(source) {
      // Allow theme.css to resolve normally — do NOT let the alias
      // rewrite it to the bare SDK specifier (which would be externalized)
      if (source === '@prexorcloud/module-sdk/theme.css') {
        return null
      }

      return null
    },

    transform(code, id) {
      // Auto-inject the theme CSS import into the module's entry file.
      // We generate a real .css file on disk (not a virtual module) because
      // Tailwind's PostCSS plugin needs to stat/read real files.
      const normalizedId = id.replace(/\\/g, '/')
      const normalizedEntry = entry.replace(/\\/g, '/')

      if (normalizedId.endsWith(normalizedEntry) || normalizedId.endsWith(normalizedEntry.replace(/^\.\//, ''))) {
        // Generate a real CSS entry file that Tailwind/PostCSS can process
        const generatedDir = resolve(projectRoot, 'node_modules', '.prexorcloud')
        const generatedCss = resolve(generatedDir, 'theme-entry.css')

        const srcDir = resolve(projectRoot, 'src')
        const relSourcePath = relative(generatedDir, srcDir).replace(/\\/g, '/')
        const relThemePath = relative(generatedDir, themeCssPath).replace(/\\/g, '/')

        mkdirSync(generatedDir, { recursive: true })
        writeFileSync(generatedCss, [
          `@import '${relThemePath}';`,
          `@source "${relSourcePath}/**/*.{vue,ts,tsx,jsx,js}";`,
        ].join('\n'))

        const importPath = generatedCss.replace(/\\/g, '/')
        return {
          code: `import '${importPath}';\n${code}`,
          map: null,
        }
      }

      return null
    },

    generateBundle(_outputOptions, bundle: OutputBundle) {
      let jsFileName: string | null = null
      let cssFileName: string | null = null

      // Find the JS chunk and CSS asset
      for (const [fileName, output] of Object.entries(bundle)) {
        if (output.type === 'chunk' && fileName.endsWith('.js')) {
          jsFileName = fileName
        } else if (output.type === 'asset' && fileName.endsWith('.css')) {
          cssFileName = fileName
        }
      }

      // Rename JS with content hash
      let finalJsName = 'index.js'
      let jsHash = ''
      if (jsFileName) {
        const chunk = bundle[jsFileName] as OutputChunk
        jsHash = contentHash(chunk.code)
        finalJsName = `index.${jsHash}.js`
        if (jsFileName !== finalJsName) {
          chunk.fileName = finalJsName
          bundle[finalJsName] = chunk
          delete bundle[jsFileName]
        }
      }

      // Rename CSS with content hash (if present in rollup bundle)
      let finalCssName: string | null = null
      if (cssFileName) {
        const asset = bundle[cssFileName] as OutputAsset
        const cssContent = typeof asset.source === 'string' ? asset.source : asset.source
        const cssHash = contentHash(cssContent)
        finalCssName = `style.${cssHash}.css`
        if (cssFileName !== finalCssName) {
          asset.fileName = finalCssName
          bundle[finalCssName] = asset
          delete bundle[cssFileName]
        }
      }

      // Vite may extract CSS outside of rollup's bundle (cssCodeSplit: false).
      // Check for the conventional Vite CSS output file name.
      if (!finalCssName) {
        // Vite names the CSS after the lib entry: index.css or style.css
        for (const [fileName] of Object.entries(bundle)) {
          if (fileName.endsWith('.css')) {
            finalCssName = fileName
            break
          }
        }
        // If still not found in bundle, check if entry-named CSS exists (generated by Vite outside rollup)
        if (!finalCssName) {
          finalCssName = 'index.css'
        }
      }

      // Generate module-frontend.json manifest
      const manifest = {
        version: 1,
        displayName,
        entry: finalJsName,
        css: finalCssName ?? null,
        icon,
        contentHash: jsHash,
        permissions,
        routes: routes.map(r => ({
          path: r.path,
          component: r.component,
          title: r.title,
          icon: r.icon ?? null,
          nav: r.nav ?? false,
          navGroup: r.navGroup ?? null,
          permission: r.permission ?? null,
          adminOnly: r.adminOnly ?? false,
        })),
        events,
      }

      this.emitFile({
        type: 'asset',
        fileName: 'module-frontend.json',
        source: JSON.stringify(manifest, null, 2),
      })
    },
  }

  return [vuePlugin, prexorPlugin].filter(Boolean)
}
