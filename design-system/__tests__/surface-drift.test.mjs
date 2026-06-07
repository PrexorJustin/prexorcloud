// Cross-surface drift guard. The reason the canon and the live surfaces silently
// diverged (cyan-9 spec vs shipped Quiet Studio reef) is that nothing ever checked
// them against each other. This does: every raw scale value (reef/ink/sand/state)
// that the website, dashboard and installer define must equal the design-system
// canon (tokens.json). If a surface re-tunes a colour, this fails until the canon
// is updated to match — making "this folder is canonical" an enforced rule, not a
// hope. (Semantic tokens are intentionally surface-specific aliases; only the raw
// scales are required to match.)

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const ROOT = join(HERE, '..', '..')

const tokens = JSON.parse(readFileSync(join(HERE, '..', 'tokens.json'), 'utf8'))

// Flatten the canon scales to { 'reef-dark': '#4ec5d4', 'ink-1': '#0a0a10', … }.
const canon = {}
for (const [name, steps] of Object.entries(tokens.color.scale))
  for (const [step, value] of Object.entries(steps)) canon[`${name}-${step}`] = value.toLowerCase()

// website uses HSL triplets (for Tailwind alpha syntax) with a trailing hex
// comment; installer/dashboard use hex directly. Either form is accepted — we
// compare the hex the surface commits to.
const SURFACES = {
  installer: 'installer/src/styles/tokens.css',
  dashboard: 'dashboard/app/assets/css/main.css',
  website: 'website/src/styles/tokens.css',
}

function surfaceHex(css, varName) {
  // Direct hex:  --ink-1: #0a0a10;
  const direct = css.match(new RegExp(`${varName}\\s*:\\s*(#[0-9a-fA-F]{3,8})`))
  if (direct) return direct[1].toLowerCase()
  // HSL + hex comment:  --ink-1: 240 22% 5%; /* #0a0a10 */
  const commented = css.match(new RegExp(`${varName}\\s*:[^;]*;?\\s*/\\*\\s*(#[0-9a-fA-F]{3,8})`))
  return commented ? commented[1].toLowerCase() : null
}

for (const [surface, rel] of Object.entries(SURFACES)) {
  test(`${surface} raw scales match the design-system canon`, () => {
    const css = readFileSync(join(ROOT, rel), 'utf8')
    for (const [key, want] of Object.entries(canon)) {
      const got = surfaceHex(css, `--${key}`)
      assert.notEqual(got, null, `${surface} is missing --${key} (canon ${want})`)
      assert.equal(got, want, `${surface} --${key} drifted from canon`)
    }
  })
}

// The --text-* size scale is named identically across all three surfaces (unlike
// radius, which is --radius-* on website but --r-* on installer/dashboard), so it
// pins cleanly too.
const declValue = (css, varName) => {
  const m = css.match(new RegExp(`${varName}\\s*:\\s*([^;]+);`))
  return m ? m[1].trim() : null
}

for (const [surface, rel] of Object.entries(SURFACES)) {
  test(`${surface} type scale (--text-*) matches the design-system canon`, () => {
    const css = readFileSync(join(ROOT, rel), 'utf8')
    for (const [step, want] of Object.entries(tokens.size.text)) {
      const got = declValue(css, `--text-${step}`)
      assert.notEqual(got, null, `${surface} is missing --text-${step} (canon ${want})`)
      assert.equal(got, want, `${surface} --text-${step} drifted from canon`)
    }
  })
}
