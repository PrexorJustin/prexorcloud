// Generates src/styles/starlight-theme.generated.css from the design-system's
// single source of truth (design-system/dist/tokens.json). Starlight reads its
// surface colors from `--sl-color-*`; this maps them onto the DS semantic tokens
// so a token change can't silently drift the docs chrome. Mirrors the pattern of
// gen-mermaid-theme.mjs (predev/prebuild + a CI freshness guard).
//
// Scope: only the `--sl-color-*` → semantic mapping is generated. The DS values
// are taken as canon (so e.g. the accent picks up the DS reef rounding rather
// than the website's hand-tuned triplet); richer website-only tokens (accent
// ramp, spacing, z-index) stay hand-maintained in tokens.css.

import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const SRC = resolve(here, '../../design-system/dist/tokens.json');
const DST = resolve(here, '../src/styles/starlight-theme.generated.css');

// Hex (#rgb / #rrggbb / #rrggbbaa) -> "h s% l%" triplet (or with "/ a" alpha).
// Identical maths to design-system/build-tokens.mjs so values match dist/.
function hexToHslTriplet(hex) {
  let h = hex.replace('#', '');
  let alpha = null;
  if (h.length === 8) {
    alpha = parseInt(h.slice(6, 8), 16) / 255;
    h = h.slice(0, 6);
  }
  if (h.length === 3) h = [...h].map((c) => c + c).join('');
  const r = parseInt(h.slice(0, 2), 16) / 255;
  const g = parseInt(h.slice(2, 4), 16) / 255;
  const b = parseInt(h.slice(4, 6), 16) / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  let s = 0;
  let hue = 0;
  if (d !== 0) {
    s = d / (1 - Math.abs(2 * l - 1));
    if (max === r) hue = ((g - b) / d) % 6;
    else if (max === g) hue = (b - r) / d + 2;
    else hue = (r - g) / d + 4;
    hue *= 60;
    if (hue < 0) hue += 360;
  }
  const triplet = `${Math.round(hue)} ${Math.round(s * 100)}% ${Math.round(l * 100)}%`;
  return alpha === null ? triplet : `${triplet} / ${Number(alpha.toFixed(3))}`;
}

// [--sl-color-* key, semantic token name in tokens.json color.{dark,light}].
const mapping = [
  ['bg', 'background'],
  ['bg-nav', 'sidebar'],
  ['bg-sidebar', 'sidebar'],
  ['text', 'foreground'],
  ['text-accent', 'primary'],
  ['hairline', 'border'],
  ['hairline-light', 'border'],
];

const tokens = JSON.parse(await readFile(SRC, 'utf8'));

const block = (selector, theme) => {
  const lines = mapping.map(
    ([slKey, token]) => `  --sl-color-${slKey}: hsl(${hexToHslTriplet(theme[token])});`,
  );
  return `${selector} {\n${lines.join('\n')}\n}`;
};

const file = `/* GENERATED from design-system/dist/tokens.json by scripts/gen-starlight-theme.mjs — do not edit. */
/* Re-emitted on every dev/build (predev/prebuild) and committed so \`astro check\` resolves it. */

${block(":root[data-theme='dark']", tokens.color.dark)}

${block(":root[data-theme='light']", tokens.color.light)}
`;

await writeFile(DST, file);
console.log('wrote src/styles/starlight-theme.generated.css');
