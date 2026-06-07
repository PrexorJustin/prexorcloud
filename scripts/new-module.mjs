#!/usr/bin/env node
/**
 * new-module.mjs — generator for PrexorCloud module scaffolds.
 *
 * Walks `java/cloud-modules/example/` as the template root and emits a new module
 * at `java/cloud-modules/<name>/`, performing token substitution in both file
 * contents and path segments.
 *
 * Usage (from repo root or anywhere inside it):
 *   node scripts/new-module.mjs <name> \
 *       [--package me.org.prexorcloud.modules.foo] \
 *       [--strip-comments] [--force] [--install] [--dry]
 *
 * Token replacements applied to both content and path segments:
 *
 *   example-playtime                                  → <name>             (kebab)
 *   ExamplePlaytime                                   → <PascalName>        (Pascal)
 *   examplePlaytime                                   → <camelName>         (camel)
 *   me.prexorjustin.prexorcloud.modules.example       → <package>           (dotted)
 *   me/prexorjustin/prexorcloud/modules/example       → <package>/<as>/<path> (slash form)
 *
 * The settings.gradle.kts file at `java/settings.gradle.kts` is patched by
 * inserting new `include("cloud-modules:<name>...")` entries immediately after the
 * `// ---- MODULES ---- //` anchor. The script refuses to run if the anchor
 * is missing — do not invent it on a whim. Add it to `java/settings.gradle.kts`
 * explicitly before re-running.
 */

import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const posix = path.posix

// ── Template / output constants ─────────────────────────────────────────────
const TEMPLATE_NAME_KEBAB = 'example-playtime'
const TEMPLATE_NAME_PASCAL = 'ExamplePlaytime'
const TEMPLATE_NAME_CAMEL = 'examplePlaytime'
const TEMPLATE_PACKAGE_DOT = 'me.prexorjustin.prexorcloud.modules.example'
const TEMPLATE_PACKAGE_SLASH = 'me/prexorjustin/prexorcloud/modules/example'
const TEMPLATE_MODULE_DIR = 'example'

const SETTINGS_ANCHOR = '// ---- MODULES ---- //'

// Files we never want to carry over from the template.
const IGNORE_DIRS = new Set(['build', 'node_modules', 'dist', '.gradle', '.idea'])
const IGNORE_FILES = new Set(['.DS_Store'])

// ── CLI parsing ─────────────────────────────────────────────────────────────
function parseArgs(argv) {
  const opts = {
    name: null,
    pkg: null,
    stripComments: false,
    force: false,
    install: false,
    dry: false,
  }
  const positional = []
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    switch (a) {
      case '--strip-comments': opts.stripComments = true; break
      case '--force': opts.force = true; break
      case '--install': opts.install = true; break
      case '--dry': opts.dry = true; break
      case '--package': opts.pkg = argv[++i]; break
      case '--help':
      case '-h':
        printUsage(); process.exit(0); break
      default:
        if (a.startsWith('--')) die(`unknown flag: ${a}`)
        positional.push(a)
    }
  }
  if (positional.length !== 1) die('expected exactly one positional argument: <name>')
  opts.name = positional[0]
  return opts
}

function printUsage() {
  process.stdout.write(
    'usage: node scripts/new-module.mjs <name> ' +
      '[--package me.org.prexorcloud.modules.foo] ' +
      '[--strip-comments] [--force] [--install] [--dry]\n',
  )
}

function die(msg) {
  process.stderr.write(`new-module: ${msg}\n`)
  process.exit(1)
}

// ── Name normalisation ──────────────────────────────────────────────────────
function toPascal(kebab) {
  return kebab
    .split(/[-_]/)
    .filter(Boolean)
    .map((s) => s[0].toUpperCase() + s.slice(1).toLowerCase())
    .join('')
}
function toCamel(kebab) {
  const p = toPascal(kebab)
  return p[0].toLowerCase() + p.slice(1)
}

// ── Token replacement ───────────────────────────────────────────────────────
function makeReplacer({ kebab, pascal, camel, pkgDot, pkgSlash }) {
  // Order matters: replace longest first so pkgSlash doesn't get clobbered
  // by kebab/camel/pascal replacements that could match substrings.
  return function replace(input) {
    let out = input
    out = out.split(TEMPLATE_PACKAGE_SLASH).join(pkgSlash)
    out = out.split(TEMPLATE_PACKAGE_DOT).join(pkgDot)
    out = out.split(TEMPLATE_NAME_KEBAB).join(kebab)
    out = out.split(TEMPLATE_NAME_PASCAL).join(pascal)
    out = out.split(TEMPLATE_NAME_CAMEL).join(camel)
    return out
  }
}

// ── Teaching-comment stripper ───────────────────────────────────────────────
// Removes `// STEP ...` line comments and `/** STEP ... */` javadoc blocks.
// Deliberately conservative: we don't try to parse comments in the middle of
// lines — only full-line `// STEP` comments and block comments whose first
// line of text starts with STEP.
function stripTeachingComments(source) {
  // Drop full-line // STEP comments (preserve indentation removal).
  const lines = source.split('\n')
  const kept = []
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (/^\s*\/\/\s*STEP\b/.test(line)) continue
    kept.push(line)
  }
  let out = kept.join('\n')

  // Drop /** ... */ blocks whose first non-* line begins with "STEP ".
  out = out.replace(/\/\*\*[\s\S]*?\*\//g, (block) => {
    const firstMeaningful = block
      .split('\n')
      .map((l) => l.replace(/^\s*\*\s?/, '').replace(/^\/\*\*\s?/, '').trim())
      .find((l) => l.length > 0)
    if (firstMeaningful && /^STEP\b/.test(firstMeaningful)) return ''
    return block
  })

  // Collapse runs of blank lines left by stripped blocks.
  out = out.replace(/\n{3,}/g, '\n\n')
  return out
}

// ── Walker ──────────────────────────────────────────────────────────────────
function walk(rootAbs) {
  const results = [] // { relPosix, absolute, isDir }
  function recurse(dirAbs, relPosix) {
    for (const entry of readdirSync(dirAbs)) {
      if (IGNORE_FILES.has(entry)) continue
      const absChild = path.join(dirAbs, entry)
      const relChild = relPosix ? posix.join(relPosix, entry) : entry
      const st = statSync(absChild)
      if (st.isDirectory()) {
        if (IGNORE_DIRS.has(entry)) continue
        results.push({ relPosix: relChild, absolute: absChild, isDir: true })
        recurse(absChild, relChild)
      } else {
        results.push({ relPosix: relChild, absolute: absChild, isDir: false })
      }
    }
  }
  recurse(rootAbs, '')
  return results
}

// ── Main ────────────────────────────────────────────────────────────────────
function main() {
  const opts = parseArgs(process.argv.slice(2))
  if (!/^[a-z][a-z0-9-]*$/.test(opts.name))
    die(`invalid name "${opts.name}" — use kebab-case: ^[a-z][a-z0-9-]*$`)

  const kebab = opts.name
  const pascal = toPascal(kebab)
  const camel = toCamel(kebab)
  const pkgDot = opts.pkg ?? `me.prexorjustin.prexorcloud.modules.${camel.toLowerCase()}`
  const pkgSlash = pkgDot.split('.').join('/')

  const scriptDir = path.dirname(fileURLToPath(import.meta.url))
  const repoRoot = path.resolve(scriptDir, '..')
  const templateRoot = path.join(repoRoot, 'java', 'cloud-modules', TEMPLATE_MODULE_DIR)
  const destRoot = path.join(repoRoot, 'java', 'cloud-modules', kebab)
  const settingsFile = path.join(repoRoot, 'java', 'settings.gradle.kts')

  if (!existsSync(templateRoot)) die(`template not found at ${templateRoot}`)
  if (!existsSync(settingsFile)) die(`settings.gradle.kts not found at ${settingsFile}`)
  if (existsSync(destRoot) && !opts.force) die(`destination exists: ${destRoot} (pass --force to overwrite)`)

  const replace = makeReplacer({ kebab, pascal, camel, pkgDot, pkgSlash })
  const entries = walk(templateRoot)

  process.stdout.write(`→ new module: ${kebab}\n`)
  process.stdout.write(`  package:  ${pkgDot}\n`)
  process.stdout.write(`  dest:     ${posix.relative(repoRoot.split(path.sep).join('/'), destRoot.split(path.sep).join('/'))}\n`)
  if (opts.dry) process.stdout.write('  (dry run — no files written)\n')

  let created = 0
  for (const entry of entries) {
    const relOut = replace(entry.relPosix)
    const destAbs = path.join(destRoot, relOut.split('/').join(path.sep))
    if (entry.isDir) {
      if (!opts.dry) mkdirSync(destAbs, { recursive: true })
      continue
    }
    // Binary-ish files (none are expected in the template, but guard anyway).
    const raw = readFileSync(entry.absolute)
    const looksBinary = raw.includes(0)
    let out
    if (looksBinary) {
      out = raw
    } else {
      let text = raw.toString('utf8')
      text = replace(text)
      if (opts.stripComments) text = stripTeachingComments(text)
      out = text
    }
    if (!opts.dry) {
      mkdirSync(path.dirname(destAbs), { recursive: true })
      writeFileSync(destAbs, out)
    }
    created++
  }
  process.stdout.write(`  ${created} files\n`)

  // ── Patch settings.gradle.kts ─────────────────────────────────────────────
  const settingsText = readFileSync(settingsFile, 'utf8')
  if (!settingsText.includes(SETTINGS_ANCHOR))
    die(
      `settings.gradle.kts is missing the anchor line "${SETTINGS_ANCHOR}". ` +
        `Add it under the include( block before re-running — this script refuses to invent it.`,
    )

  const newIncludes = [
    `    "cloud-modules:${kebab}",`,
    `    "cloud-modules:${kebab}:plugin:paper",`,
    `    "cloud-modules:${kebab}:plugin:folia",`,
    `    "cloud-modules:${kebab}:plugin:velocity",`,
  ]
  // Idempotent: skip if already present.
  const alreadyPatched = newIncludes.every((line) => settingsText.includes(line.trim()))
  if (!alreadyPatched) {
    const anchorIdx = settingsText.indexOf(SETTINGS_ANCHOR)
    const afterAnchor = settingsText.indexOf('\n', anchorIdx) + 1
    const patched =
      settingsText.slice(0, afterAnchor) + newIncludes.join('\n') + '\n' + settingsText.slice(afterAnchor)
    if (!opts.dry) writeFileSync(settingsFile, patched)
    process.stdout.write(`  patched settings.gradle.kts (+${newIncludes.length} includes)\n`)
  } else {
    process.stdout.write('  settings.gradle.kts already contains the new includes — skipped\n')
  }

  // ── Optional: pnpm install for the frontend workspace package ─────────────
  if (opts.install && !opts.dry) {
    const frontendDir = path.join(destRoot, 'frontend')
    if (existsSync(path.join(frontendDir, 'package.json'))) {
      const cmd = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
      process.stdout.write(`  running ${cmd} install in ${frontendDir}…\n`)
      const result = spawnSync(cmd, ['install'], { cwd: frontendDir, stdio: 'inherit' })
      if (result.status !== 0) die(`${cmd} install failed with exit code ${result.status}`)
    }
  }

  // ── Next steps ────────────────────────────────────────────────────────────
  process.stdout.write('\nnext:\n')
  process.stdout.write(`  cd java && ./gradlew :cloud-modules:${kebab}:build\n`)
  if (!opts.install) {
    process.stdout.write('  (run pnpm install from the dashboard workspace to pick up the new frontend package)\n')
  }
}

main()
