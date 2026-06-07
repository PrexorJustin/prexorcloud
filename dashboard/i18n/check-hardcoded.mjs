// Fails (exit 1) if a product .vue template carries a user-facing string literal
// that never went through t(). This is the regression guard that locks in the
// H.3 i18n sweep: once the tree is at zero hardcoded copy, this keeps it there.
//
// Two checks, both scoped to <template> blocks only (script/style are ignored):
//   1. Text-bearing attributes (placeholder/aria-label/title/alt) with a static
//      value. Bound forms (:placeholder, v-bind:) are fine — those resolve to
//      t() in script.
//   2. Text nodes between tags. Pure {{ }} interpolation, units, and bare
//      enum/identifier tokens are allowed (see isTranslatable).
//
// Zero dependencies (`pnpm i18n:check-hardcoded`), independent of the vitest
// suite. Wired as a HARD CI gate — the H.3 batches drove the residual inventory
// to zero, so any newly-introduced user-facing literal fails the build. The
// template is walked with a small
// tag/text/quote state machine rather than a regex, because Vue bindings carry
// ">" inside quoted attribute values (v-if="a > 0", :class="[i >= 1]") which a
// naive >…< scan would mistake for text-node boundaries.
//
// Histoire stories (app/stories, *.story.vue) are dev-only demos, never shipped
// to end users, so they're excluded.
//
// When a flagged string is genuinely language-neutral (an example hostname, a
// format hint, a proper noun), add it to ALLOW below with a one-line reason
// rather than weakening the heuristic — the allowlist is the audit trail.

import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, relative } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const APP = join(HERE, '..', 'app')

// Attributes whose static value is shown to (or read aloud to) the user.
const TEXT_ATTRS = ['placeholder', 'aria-label', 'title', 'alt']

// Explicit allowlist of strings that look like copy but are deliberately
// language-neutral. Each entry: the exact (trimmed) literal, with the reason.
const ALLOW = new Set([
  // Example values / format hints in mono placeholders — illustrative, not
  // prose, identical in every language.
  'node-east-1',
  'node-pending-east-1',
  'MY_ROLE',
  'controller-2',
  'controller-1.example.internal:9090&#10;controller-3.example.internal:9090',
  '<gold>My Server <white>— <gray>A PrexorCloud network',
  // TerminalBlock labels naming a literal artifact, not UI prose.
  'cluster join-token',
  'join-token',
  // CLI command shown in a .mono span (the surrounding prose is already t()'d).
  'prexorctl node drain id',
  // Structural ARIA landmark role-name (a WAI-ARIA term of art; localizing it
  // is discouraged).
  'breadcrumb',
])

// Measurement units left hardcoded on purpose (plan H.3). A text node that
// reduces to nothing but units/numbers/symbols is not prose.
const UNITS = new Set(['ms', 's', 'MB', 'GB', 'KB', 'KiB', 'MiB', 'GiB', 'GHz', 'MHz', 'px', 'op', 'vCPU'])

// Elements whose text content is code / shell / key samples, never translated.
const CODE_TAGS = new Set(['code', 'pre', 'kbd', 'samp'])

// Unicode-aware: a "word" is 2+ cased letters in a row. Catches German
// umlauts/ß too, so flagged copy and its translation are both caught.
const WORD = /\p{L}\p{L}/u

// Bare token = no whitespace, made only of identifier-ish characters
// (UPPER_SNAKE, kebab-case, camelCase, dotted keys, units like GHz/MiB/%).
// These are the enum labels / logical keys / units left hardcoded on purpose.
const BARE_TOKEN = /^[\p{L}\p{N}_.\-:/%°+]+$/u

const isTranslatable = (raw) => {
  const s = raw.trim()
  if (!s) return false
  if (ALLOW.has(s)) return false
  if (!WORD.test(s)) return false // pure numbers, symbols, units like "%"
  // A single bare token with no spaces is an identifier/enum/unit, not prose.
  if (!/\s/.test(s) && BARE_TOKEN.test(s)) return false
  // All remaining word-ish tokens are units (e.g. "/ MB", "GB free" split off an
  // interpolation) → a unit fragment, not prose.
  const words = s.match(/\p{L}+/gu) ?? []
  if (words.length && words.every((w) => UNITS.has(w))) return false
  return true
}

const listVue = (dir, out = []) => {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'stories') continue // Histoire demos, not shipped UI
      listVue(p, out)
    } else if (e.name.endsWith('.vue') && !e.name.endsWith('.story.vue')) {
      out.push(p)
    }
  }
  return out
}

// Blank out a span (keep length and newlines) so offsets/line numbers stay
// aligned with the original source after we mask sibling SFC blocks.
const blank = (s) => s.replace(/[^\n]/g, ' ')

// Pull out the top-level <template>…</template> block, with its start offset so
// match indices map back to absolute line numbers in the source file. <script>
// and <style> are masked first — a JSDoc example in <script setup> can contain a
// literal `<template #slot>` that would otherwise be mistaken for the real one.
const templateBlock = (src) => {
  const masked = src
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, blank)
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, blank)
  const m = /<template[^>]*>/i.exec(masked)
  if (!m) return null
  const start = m.index + m[0].length
  const end = masked.lastIndexOf('</template>')
  if (end < 0 || end < start) return null
  return { text: masked.slice(start, end), offset: start }
}

// Walk the template into tag / text tokens. A tag runs from "<" to the matching
// unquoted ">"; quotes inside a tag shield ">" so attribute values like
// v-if="a > 0" don't end the tag. HTML comments are skipped. In well-formed
// markup a "<" in text always opens a tag (literal "<" must be &lt;), so text is
// simply the run up to the next "<".
function* tokenize(tpl) {
  let i = 0
  const n = tpl.length
  while (i < n) {
    if (tpl[i] === '<') {
      if (tpl.startsWith('<!--', i)) {
        const end = tpl.indexOf('-->', i + 4)
        i = end < 0 ? n : end + 3
        continue
      }
      let j = i + 1
      let quote = null
      while (j < n) {
        const c = tpl[j]
        if (quote) {
          if (c === quote) quote = null
        } else if (c === '"' || c === "'") {
          quote = c
        } else if (c === '>') {
          break
        }
        j++
      }
      yield { type: 'tag', text: tpl.slice(i, j + 1), start: i }
      i = j + 1
    } else {
      let j = i
      while (j < n && tpl[j] !== '<') j++
      yield { type: 'text', text: tpl.slice(i, j), start: i }
      i = j
    }
  }
}

const lineAt = (src, idx) => src.slice(0, idx).split('\n').length

// Static attrs within a single tag. The lookbehind drops bound/namespaced forms
// (:title, v-bind:title, @, data-title, aria-…-title) so only literal values hit.
const attrRe = new RegExp(`(?<![\\w:.@-])(${TEXT_ATTRS.join('|')})\\s*=\\s*"([^"]*)"`, 'g')

const findings = []

for (const file of listVue(APP)) {
  const src = readFileSync(file, 'utf8')
  const tpl = templateBlock(src)
  if (!tpl) continue
  const rel = relative(join(HERE, '..'), file)

  let codeDepth = 0 // inside <code>/<pre>/<kbd>/<samp> → text is a code sample
  for (const tok of tokenize(tpl.text)) {
    if (tok.type === 'tag') {
      const t = /^<\s*(\/?)\s*([a-zA-Z][\w-]*)/.exec(tok.text)
      if (t && CODE_TAGS.has(t[2].toLowerCase()) && !/\/>\s*$/.test(tok.text)) {
        codeDepth += t[1] ? -1 : 1
        if (codeDepth < 0) codeDepth = 0
      }
      for (const m of tok.text.matchAll(attrRe)) {
        if (isTranslatable(m[2])) {
          const idx = tpl.offset + tok.start + m.index
          findings.push({ rel, line: lineAt(src, idx), kind: m[1], text: m[2].trim() })
        }
      }
    } else if (codeDepth === 0) {
      // Drop {{ interpolation }} and HTML entity refs (&gt; &mdash; &nbsp; …) —
      // entities are punctuation/symbols, never the translatable word itself.
      const stripped = tok.text
        .replace(/\{\{[\s\S]*?\}\}/g, ' ')
        .replace(/&(?:#\d+|#x[0-9a-f]+|[a-z]+);/gi, ' ')
        .replace(/\s+/g, ' ')
      if (isTranslatable(stripped)) {
        const idx = tpl.offset + tok.start
        findings.push({ rel, line: lineAt(src, idx), kind: 'text', text: stripped.trim() })
      }
    }
  }
}

if (findings.length === 0) {
  console.log('i18n hardcoded-string check OK — no user-facing literals outside t()')
  process.exit(0)
}

findings.sort((a, b) => a.rel.localeCompare(b.rel) || a.line - b.line)
console.error(`i18n hardcoded-string check FAILED — ${findings.length} user-facing literal(s) not routed through t():\n`)
for (const f of findings) {
  console.error(`  ${f.rel}:${f.line}  [${f.kind}]  ${JSON.stringify(f.text)}`)
}
console.error('\nWrap each in t() with a new key in i18n/locales/{en,de}.json, or — if it is')
console.error('genuinely language-neutral (example value, unit, proper noun) — add it to the')
console.error('ALLOW set in i18n/check-hardcoded.mjs with a reason.')
process.exit(1)
