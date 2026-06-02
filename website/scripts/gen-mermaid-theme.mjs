// Generates src/scripts/mermaid-theme.generated.ts from the design-system's
// single source of truth (design-system/dist/tokens.json). The Mermaid palette
// used to be hand-copied hex values in mermaid.ts; now it's derived from named
// token scale steps so a token change can't silently drift the docs diagrams.
//
// Runs as `predev` / `prebuild` (see package.json), the same pattern as
// sync-openapi.mjs. The output is committed so `astro check` resolves the import
// without a prior build; CI regenerates and asserts it stayed fresh.

import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const SRC = resolve(here, '../../design-system/dist/tokens.json');
const DST = resolve(here, '../src/scripts/mermaid-theme.generated.ts');

const tokens = JSON.parse(await readFile(SRC, 'utf8'));
const { cyan, slate, sand } = tokens.color.scale;

// Each palette entry is [mermaid key, token value, token name for the comment].
// Dark = slate spine + cyan-9 accent; light = sand spine + cyan-8 accent — the
// "wireframe kubernetes diagram" look from the design-system brief (README §10).
const dark = [
  ['background', slate['1'], 'slate-1'],
  ['primaryColor', slate['3'], 'slate-3 — node fill'],
  ['primaryTextColor', slate['12'], 'slate-12'],
  ['primaryBorderColor', cyan['9'], 'cyan-9 — accent border'],
  ['secondaryColor', slate['4'], 'slate-4'],
  ['secondaryTextColor', slate['12'], 'slate-12'],
  ['secondaryBorderColor', slate['7'], 'slate-7'],
  ['tertiaryColor', slate['3'], 'slate-3'],
  ['tertiaryTextColor', slate['12'], 'slate-12'],
  ['tertiaryBorderColor', slate['7'], 'slate-7'],
  ['lineColor', slate['9'], 'slate-9 — arrows'],
  ['textColor', slate['12'], 'slate-12'],
  ['mainBkg', slate['3'], 'slate-3'],
  ['clusterBkg', slate['2'], 'slate-2 — subgraph fill'],
  ['clusterBorder', cyan['9'], 'cyan-9 — subgraph border'],
  ['edgeLabelBackground', slate['1'], 'slate-1'],
  ['fontSize', '14px', null],
];

const light = [
  ['background', sand['4'], 'sand-4'],
  ['primaryColor', sand['2'], 'sand-2 — node fill'],
  ['primaryTextColor', sand['12'], 'sand-12'],
  ['primaryBorderColor', cyan['8'], 'cyan-8 — accent border'],
  ['secondaryColor', sand['3'], 'sand-3'],
  ['secondaryTextColor', sand['12'], 'sand-12'],
  ['secondaryBorderColor', sand['8'], 'sand-8'],
  ['tertiaryColor', sand['2'], 'sand-2'],
  ['tertiaryTextColor', sand['12'], 'sand-12'],
  ['tertiaryBorderColor', sand['8'], 'sand-8'],
  ['lineColor', sand['10'], 'sand-10'],
  ['textColor', sand['12'], 'sand-12'],
  ['mainBkg', sand['2'], 'sand-2'],
  ['clusterBkg', sand['1'], 'sand-1 — subgraph fill'],
  ['clusterBorder', cyan['8'], 'cyan-8 — subgraph border'],
  ['edgeLabelBackground', sand['4'], 'sand-4'],
  ['fontSize', '14px', null],
];

const emit = (entries) =>
  entries.map(([k, v, c]) => `  ${k}: '${v}',${c ? ` /* ${c} */` : ''}`).join('\n');

const file = `// GENERATED from design-system/dist/tokens.json by scripts/gen-mermaid-theme.mjs — do not edit.
// Re-emitted on every dev/build (predev/prebuild) and committed so \`astro check\` resolves it.

export const mermaidFontFamily = '${tokens.font.sans}'

export const mermaidThemeDark = {
${emit(dark)}
} as const

export const mermaidThemeLight = {
${emit(light)}
} as const
`;

await writeFile(DST, file);
console.log('wrote src/scripts/mermaid-theme.generated.ts');
