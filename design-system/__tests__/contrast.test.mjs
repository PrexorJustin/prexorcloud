// Guards WCAG colour-contrast at the canon (northstar Track E, E-P1.2). The
// rendered-DOM axe pass (E-P1.1) catches contrast in context; this catches it at
// the *source* — a token edit that drops a foreground/surface pair below its
// floor fails CI here, before it ever reaches a surface. Zero-dep, like the
// sibling token tests.
//
// Thresholds follow WCAG 2.1:
//   • BODY  — small-text reading surfaces must hit AA 4.5:1.
//   • FLOOR — every opaque foreground/surface pair must clear 3.0:1 (1.4.3 large
//     text / 1.4.11 UI components). Filled controls and badges use ≥14px
//     semibold labels (= "large"), so they're held to the 3.0 floor, not 4.5;
//     borderline ones surface in the rendered axe pass.
//
// Translucent tokens (8-digit hex with alpha — borders, glows, glass) are skipped:
// their contrast depends on what they composite over, which only the DOM knows.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const tokens = JSON.parse(readFileSync(join(HERE, '..', 'tokens.json'), 'utf8')).color

// ── WCAG relative-luminance contrast ─────────────────────────────────────────
const toRgb = (hex) => {
  let h = hex.replace('#', '')
  if (h.length === 3) h = [...h].map((c) => c + c).join('')
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16))
}
const channel = (c) => {
  const s = c / 255
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
}
const luminance = (hex) => {
  const [r, g, b] = toRgb(hex)
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}
const ratio = (a, b) => {
  const L1 = luminance(a)
  const L2 = luminance(b)
  return (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05)
}
const isOpaque = (hex) => /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(hex)

// Small-text reading surfaces — held to AA 4.5:1.
const BODY = [
  ['foreground', 'background'],
  ['cardForeground', 'card'],
  ['popoverForeground', 'popover'],
  ['mutedForeground', 'background'], // secondary/helper text on the page
]

// Every foreground-on-surface pairing that can render as text/control — held to
// the 3.0:1 floor (BODY is the stricter subset of this).
const ALL = [
  ...BODY,
  ['mutedForeground', 'muted'],
  ['mutedForeground', 'card'],
  ['sidebarForeground', 'sidebar'],
  ['primaryForeground', 'primary'],
  ['secondaryForeground', 'secondary'],
  ['accentForeground', 'accent'],
  ['destructiveForeground', 'destructive'],
  // status colours used as text on the base surfaces
  ['success', 'card'],
  ['warning', 'card'],
  ['destructive', 'card'],
]

const pairsFor = (theme, list) =>
  list
    .map(([fg, bg]) => [fg, bg, tokens[theme][fg], tokens[theme][bg]])
    .filter(([fg, bg, f, b]) => {
      assert.ok(f, `missing token ${theme}.${fg}`)
      assert.ok(b, `missing token ${theme}.${bg}`)
      return isOpaque(f) && isOpaque(b)
    })

for (const theme of ['dark', 'light']) {
  test(`every ${theme} text/surface pair clears the 3.0:1 floor`, () => {
    for (const [fg, bg, f, b] of pairsFor(theme, ALL)) {
      const r = ratio(f, b)
      assert.ok(
        r >= 3.0,
        `${theme} ${fg} on ${bg} is ${r.toFixed(2)}:1 (${f} on ${b}) — below the 3.0:1 floor`,
      )
    }
  })

  test(`${theme} body-text pairs meet AA 4.5:1`, () => {
    for (const [fg, bg, f, b] of pairsFor(theme, BODY)) {
      const r = ratio(f, b)
      assert.ok(
        r >= 4.5,
        `${theme} ${fg} on ${bg} is ${r.toFixed(2)}:1 (${f} on ${b}) — below AA 4.5:1 for body text`,
      )
    }
  })
}
