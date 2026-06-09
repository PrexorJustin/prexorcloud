#!/usr/bin/env node
// tools/check-doc-links.mjs — zero-dependency dead-link gate for the docs and READMEs.
//
// Walks every Markdown file under docs/ plus every README.md in the repo, extracts
// relative links and images, and fails if any target does not resolve to a real file
// or directory. This is the I.4 link gate promised in docs/engineering/DOCS_STYLE.md §7.
//
// What it checks:   relative links/images, after stripping #anchors and ?queries.
// What it skips:     http(s)/mailto/tel, protocol-relative //, bare #anchors, and
//                    fenced/inline code (so code samples never trip it).
//
// Usage:  node tools/check-doc-links.mjs        (exit 1 on any broken link)

import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs'
import { dirname, resolve, join } from 'node:path'

const REPO_ROOT = resolve(dirname(new URL(import.meta.url).pathname), '..')
const SKIP_DIRS = new Set([
  'node_modules', '.git', 'build', 'dist', '.gradle', '.nuxt', '.output',
  'target', 'bin', 'out', '.astro', 'coverage', '.vite',
])

// Collect the files in scope: all of docs/**.md, and every README.md elsewhere.
function walk(dir, acc) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('.') && entry.name !== '.github') continue
    const abs = join(dir, entry.name)
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue
      walk(abs, acc)
    } else if (entry.isFile()) {
      const rel = abs.slice(REPO_ROOT.length + 1)
      const isDoc = rel.startsWith('docs/') && rel.endsWith('.md')
      const isReadme = entry.name === 'README.md'
      if ((isDoc || isReadme) && inScope(rel)) acc.push(abs)
    }
  }
  return acc
}

// Strip fenced code blocks and inline code so `[x](y)` inside samples is ignored.
function stripCode(text) {
  return text
    .replace(/```[\s\S]*?```/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/~~~[\s\S]*?~~~/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/`[^`\n]*`/g, (m) => ' '.repeat(m.length))
}

function isExternal(target) {
  return (
    /^[a-z][a-z0-9+.-]*:/i.test(target) || // http:, https:, mailto:, tel:, etc.
    target.startsWith('//') ||
    target.startsWith('#') ||
    target.startsWith('/') || // site-root URL (Starlight resolves these at build) — not a file path
    target.startsWith('{{') // unfilled template placeholder
  )
}

// docs/public/ is Astro/Starlight content: its links live in the site's URL space
// (trailing-slash routes, site-root paths), validated by the website build — not by a
// filesystem check. We police the GitHub-rendered surface: READMEs and the engineering/
// runbook/security notes, where a relative link is a real file path. The placeholder
// template is skipped (its example links resolve only once copied into a package).
function inScope(rel) {
  if (rel.startsWith('docs/public/')) return false
  if (rel.startsWith('docs/engineering/templates/')) return false
  return true
}

// Pull every link/image target out of one line, with the column for reporting.
const INLINE = /!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*\)/g
const REFDEF = /^\s*\[[^\]]+\]:\s+<?([^\s>]+)>?/

function targetsIn(line) {
  const out = []
  let m
  INLINE.lastIndex = 0
  while ((m = INLINE.exec(line)) !== null) out.push({ target: m[1], col: m.index + 1 })
  const r = REFDEF.exec(line)
  if (r) out.push({ target: r[1], col: 1 })
  return out
}

function resolveTarget(fileAbs, target) {
  // Drop anchor and query, decode %20 etc.
  let path = target.split('#')[0].split('?')[0]
  if (!path) return null // pure anchor — nothing to check
  try {
    path = decodeURIComponent(path)
  } catch {
    /* leave as-is if not valid encoding */
  }
  const base = path.startsWith('/') ? join(REPO_ROOT, path) : resolve(dirname(fileAbs), path)
  return base
}

const broken = []
let linkCount = 0
const files = walk(REPO_ROOT, [])

for (const fileAbs of files) {
  const lines = stripCode(readFileSync(fileAbs, 'utf8')).split('\n')
  lines.forEach((line, i) => {
    for (const { target, col } of targetsIn(line)) {
      if (isExternal(target)) continue
      linkCount++
      const abs = resolveTarget(fileAbs, target)
      if (abs === null) continue
      if (!existsSync(abs)) {
        broken.push({ file: fileAbs.slice(REPO_ROOT.length + 1), line: i + 1, col, target })
      }
    }
  })
}

const rel = (p) => p
console.log(`checked ${linkCount} relative links across ${files.length} files`)

if (broken.length > 0) {
  console.error(`\n✗ ${broken.length} broken link${broken.length === 1 ? '' : 's'}:\n`)
  for (const b of broken) {
    const loc = `${rel(b.file)}:${b.line}:${b.col}`
    console.error(`  ${loc}  →  ${b.target}`)
    if (process.env.GITHUB_ACTIONS) {
      console.error(`::error file=${b.file},line=${b.line},col=${b.col}::Broken link: ${b.target}`)
    }
  }
  console.error('\nEvery link you add, you click — see docs/engineering/DOCS_STYLE.md §7.')
  process.exit(1)
}

console.log('✓ no broken relative links')
