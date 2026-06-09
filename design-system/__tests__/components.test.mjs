// Enforces the headless principle for components.css: styling comes *only*
// through tokens. A component that hardcodes a colour (instead of referencing a
// token) fails CI — which is what keeps the component layer restyleable purely
// by swapping tokens, and keeps it honest against the canon.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const read = (p) => readFileSync(join(HERE, '..', p), 'utf8')

const stripComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '')

const components = stripComments(read('components.css'))

test('components.css hardcodes no raw colours — only tokens', () => {
  const hex = components.match(/#[0-9a-fA-F]{3,8}\b/g)
  assert.equal(hex, null, `raw hex colours found: ${hex}`)

  const fns = components.match(/\b(rgba?|hsla?)\(/g)
  assert.equal(fns, null, `raw colour functions found: ${fns} (use a token via var())`)
})

test('every var(--token) in components.css is defined in colors_and_type.css', () => {
  // Collect the tokens the canonical layer defines.
  const tokenCss = read('colors_and_type.css')
  const defined = new Set(
    [...tokenCss.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]),
  )
  const referenced = new Set(
    [...components.matchAll(/var\((--[\w-]+)\)/g)].map((m) => m[1]),
  )
  const dangling = [...referenced].filter((t) => !defined.has(t))
  assert.deepEqual(dangling, [], `components reference undefined tokens: ${dangling}`)
})
