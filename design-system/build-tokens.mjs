// Generates dist/tokens.{css,ts,json} from tokens.json — the single source of
// truth for PrexorCloud's design tokens. Zero runtime dependencies on purpose:
// the bespoke token shape (nested dark/light semantics + ANSI) would need custom
// Style-Dictionary formats anyway, and a self-hosted, cosign/Rekor-hardened
// project does not want a token toolchain in its supply chain for this.
//
// Run:  node build-tokens.mjs        (writes dist/)
// The committed dist/ is checked for freshness in CI — regenerate after editing
// tokens.json or the build fails.

import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = join(HERE, 'tokens.json')
const DIST = join(HERE, 'dist')

const GENERATED = '/* GENERATED from tokens.json by build-tokens.mjs — do not edit. */'

const camelToKebab = (s) => s.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase())

// "light9" -> "light-9" so it reads as the canonical --green-light-9 var name.
const stepToSuffix = (k) => (/^light\d+$/.test(k) ? 'light-' + k.slice(5) : k)

function cssBlock(selector, lines) {
  return `${selector} {\n${lines.map((l) => (l === '' ? '' : '  ' + l)).join('\n')}\n}`
}

function scaleVars(scale) {
  const out = []
  for (const [name, steps] of Object.entries(scale)) {
    for (const [step, value] of Object.entries(steps)) {
      out.push(`--${name}-${stepToSuffix(step)}: ${value};`)
    }
  }
  return out
}

const semanticVars = (obj) =>
  Object.entries(obj).map(([k, v]) => `--${camelToKebab(k)}: ${v};`)

// Hex (#rgb / #rrggbb / #rrggbbaa) -> "h s% l%" triplet, or "h s% l% / a" when
// the source carries alpha. This is the form Tailwind surfaces consume so they
// can apply alpha at use-site (`hsl(var(--primary) / 0.2)`, `bg-primary/20`).
function hexToHslTriplet(hex) {
  let h = hex.replace('#', '')
  let alpha = null
  if (h.length === 8) {
    alpha = parseInt(h.slice(6, 8), 16) / 255
    h = h.slice(0, 6)
  }
  if (h.length === 3) h = [...h].map((c) => c + c).join('')
  const r = parseInt(h.slice(0, 2), 16) / 255
  const g = parseInt(h.slice(2, 4), 16) / 255
  const b = parseInt(h.slice(4, 6), 16) / 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const l = (max + min) / 2
  const d = max - min
  let s = 0
  let hue = 0
  if (d !== 0) {
    s = d / (1 - Math.abs(2 * l - 1))
    if (max === r) hue = ((g - b) / d) % 6
    else if (max === g) hue = (b - r) / d + 2
    else hue = (r - g) / d + 4
    hue *= 60
    if (hue < 0) hue += 360
  }
  const triplet = `${Math.round(hue)} ${Math.round(s * 100)}% ${Math.round(l * 100)}%`
  return alpha === null ? triplet : `${triplet} / ${Number(alpha.toFixed(3))}`
}

const semanticHslVars = (obj) =>
  Object.entries(obj).map(([k, v]) => `--${camelToKebab(k)}: ${hexToHslTriplet(v)};`)

const groupVars = (prefix, obj) =>
  Object.entries(obj).map(([k, v]) => `--${prefix}-${camelToKebab(k)}: ${v};`)

export function generate(tokens) {
  const { color, font, size, space, radius, shadow } = tokens

  // ── tokens.css ──────────────────────────────────────────────────────────
  const rootLines = [
    '/* raw color scales */',
    ...scaleVars(color.scale),
    '',
    '/* type */',
    ...Object.entries(font).map(([k, v]) => `--font-${k}: ${v};`),
    ...groupVars('text', size.text),
    ...groupVars('leading', size.leading),
    ...groupVars('tracking', size.tracking),
    '',
    '/* spacing / radius / shadow */',
    ...groupVars('space', space),
    ...groupVars('radius', radius),
    ...groupVars('shadow', shadow),
  ]

  const css =
    `${GENERATED}\n\n` +
    [
      cssBlock(':root', rootLines),
      cssBlock(':root, .dark', ['/* semantic — dark (default) */', ...semanticVars(color.dark)]),
      cssBlock('.light', ['/* semantic — light */', ...semanticVars(color.light)]),
    ].join('\n\n') +
    '\n'

  // ── tokens.hsl.css ────────────────────────────────────────────────────────
  // Same semantic tokens as tokens.css, but as "h s% l%" triplets (unwrapped) so
  // Tailwind/HSL surfaces (the website) can consume them with use-site alpha.
  // Raw scales stay in tokens.css (hex); this file is the semantic HSL layer.
  const hslCss =
    `${GENERATED}\n\n` +
    [
      cssBlock(':root, .dark', ['/* semantic — dark (default), HSL triplets */', ...semanticHslVars(color.dark)]),
      cssBlock('.light', ['/* semantic — light, HSL triplets */', ...semanticHslVars(color.light)]),
    ].join('\n\n') +
    '\n'

  // ── tokens.ts ───────────────────────────────────────────────────────────
  const ts =
    `// ${GENERATED.slice(3, -3).trim()}\n` +
    `export const tokens = ${JSON.stringify(stripComment(tokens), null, 2)} as const\n\n` +
    `export const colorScale = tokens.color.scale\n` +
    `export const colorDark = tokens.color.dark\n` +
    `export const colorLight = tokens.color.light\n` +
    `export const ansi = tokens.color.ansi\n` +
    `export const { font, size, space, radius, shadow } = tokens\n`

  // ── tokens.json (normalized build output, e.g. for Figma sync) ────────────
  const json = JSON.stringify(stripComment(tokens), null, 2) + '\n'

  return { css, hslCss, ts, json }
}

function stripComment(tokens) {
  const { $comment, ...rest } = tokens
  return rest
}

async function main() {
  const tokens = JSON.parse(await readFile(SRC, 'utf8'))
  const { css, hslCss, ts, json } = generate(tokens)
  await mkdir(DIST, { recursive: true })
  await Promise.all([
    writeFile(join(DIST, 'tokens.css'), css),
    writeFile(join(DIST, 'tokens.hsl.css'), hslCss),
    writeFile(join(DIST, 'tokens.ts'), ts),
    writeFile(join(DIST, 'tokens.json'), json),
  ])
  console.log('wrote dist/tokens.css, dist/tokens.hsl.css, dist/tokens.ts, dist/tokens.json')
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  await main()
}
