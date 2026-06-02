// Guards the design-token pipeline:
//   1. freshness — the committed dist/ is exactly what build-tokens.mjs emits
//      from tokens.json (so a tokens.json edit without a rebuild fails CI).
//   2. parity   — every color in tokens.json matches the canonical, human-edited
//      colors_and_type.css. This operationalizes the README's "if the two drift,
//      this folder wins" rule: colors_and_type.css is canonical, tokens.json is
//      the machine mirror, and CI now proves they agree.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { generate } from '../build-tokens.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const DS = join(HERE, '..')
const read = (p) => readFileSync(join(DS, p), 'utf8')

const tokens = JSON.parse(read('tokens.json'))

// ── 1. freshness ────────────────────────────────────────────────────────────
test('dist/ is freshly generated from tokens.json', () => {
  const out = generate(tokens)
  for (const [name, key] of [
    ['dist/tokens.css', 'css'],
    ['dist/tokens.ts', 'ts'],
    ['dist/tokens.json', 'json'],
  ]) {
    assert.equal(
      read(name),
      out[key],
      `${name} is stale — run \`node build-tokens.mjs\` and commit the result.`,
    )
  }
})

// ── parse colors_and_type.css into resolvable var maps ───────────────────────
function parseCanonicalVars() {
  const css = read('colors_and_type.css').replace(/\/\*[\s\S]*?\*\//g, '')
  const base = {} // :root + :root,.dark (dark is the default theme)
  const lightOverlay = {}
  for (const m of css.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
    const selector = m[1]
    const target = selector.includes('.light') ? lightOverlay : selector.includes(':root') ? base : null
    if (!target) continue
    for (const decl of m[2].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
      target[decl[1].trim()] = decl[2].trim()
    }
  }
  return { base, light: { ...base, ...lightOverlay } }
}

const resolve = (value, map, seen = new Set()) => {
  const m = value.match(/^var\(\s*(--[\w-]+)\s*\)$/)
  if (!m) return value.toLowerCase()
  assert.ok(!seen.has(m[1]), `circular var reference at ${m[1]}`)
  assert.ok(m[1] in map, `canonical css is missing ${m[1]}`)
  return resolve(map[m[1]], map, seen.add(m[1]))
}

const camelToKebab = (s) => s.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase())
const stepToSuffix = (k) => (/^light\d+$/.test(k) ? 'light-' + k.slice(5) : k)

const { base, light } = parseCanonicalVars()

// ── 2a. parity — raw scales ──────────────────────────────────────────────────
test('color scales match colors_and_type.css', () => {
  for (const [name, steps] of Object.entries(tokens.color.scale)) {
    for (const [step, value] of Object.entries(steps)) {
      const v = `--${name}-${stepToSuffix(step)}`
      assert.ok(v in base, `canonical css is missing ${v}`)
      assert.equal(resolve(base[v], base), value.toLowerCase(), `${v} drifted`)
    }
  }
})

// ── 2b. parity — semantic dark + light ───────────────────────────────────────
for (const [theme, map] of [
  ['dark', base],
  ['light', light],
]) {
  test(`semantic ${theme} colors match colors_and_type.css`, () => {
    for (const [key, value] of Object.entries(tokens.color[theme])) {
      const v = `--${camelToKebab(key)}`
      assert.ok(v in map, `canonical css is missing ${v} for ${theme}`)
      assert.equal(resolve(map[v], map), value.toLowerCase(), `${theme} ${v} drifted`)
    }
  })
}
