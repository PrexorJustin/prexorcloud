// Fails (exit 1) if en.json and de.json don't have the identical key set — i.e.
// a string was added to one locale but not the other, which ships as silently
// untranslated UI (English fallback in the German app, or vice versa). Zero
// dependencies; runs as a hard CI gate (`pnpm i18n:check`), independent of the
// vitest suite. Values may legitimately match across locales (proper nouns like
// "Dashboard", "Bytes", "URL"), so only KEYS are compared, never values.

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const load = (name) => JSON.parse(readFileSync(join(HERE, 'locales', name), 'utf8'))

const flatten = (obj, prefix = '', out = {}) => {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object' && !Array.isArray(v)) flatten(v, key, out)
    else out[key] = v
  }
  return out
}

const en = Object.keys(flatten(load('en.json')))
const de = Object.keys(flatten(load('de.json')))
const enSet = new Set(en)
const deSet = new Set(de)

const missingInDe = en.filter((k) => !deSet.has(k))
const missingInEn = de.filter((k) => !enSet.has(k))

if (missingInDe.length === 0 && missingInEn.length === 0) {
  console.log(`i18n locale parity OK — ${en.length} keys in en.json and de.json`)
  process.exit(0)
}

console.error('i18n locale parity FAILED — locales have diverged:\n')
if (missingInDe.length) console.error(`  missing in de.json (${missingInDe.length}):\n    ${missingInDe.join('\n    ')}\n`)
if (missingInEn.length) console.error(`  missing in en.json (${missingInEn.length}):\n    ${missingInEn.join('\n    ')}\n`)
console.error('Add the missing keys to keep both locales in sync.')
process.exit(1)
