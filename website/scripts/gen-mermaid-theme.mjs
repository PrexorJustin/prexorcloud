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
const { reef, ink, sand } = tokens.color.scale;

// Each palette entry is [mermaid key, token value, token name for the comment].
// Dark = ink spine + reef accent; light = sand spine + reef accent — the
// "wireframe kubernetes diagram" look (README §10) in the Quiet Studio palette,
// so the docs diagrams match the docs site's own theme.
const dark = [
  ['background', ink['1'], 'ink-1'],
  ['primaryColor', ink['3'], 'ink-3 — node fill'],
  ['primaryTextColor', ink['9'], 'ink-9'],
  ['primaryBorderColor', reef['dark'], 'reef-dark — accent border'],
  ['secondaryColor', ink['4'], 'ink-4'],
  ['secondaryTextColor', ink['9'], 'ink-9'],
  ['secondaryBorderColor', ink['6'], 'ink-6'],
  ['tertiaryColor', ink['3'], 'ink-3'],
  ['tertiaryTextColor', ink['9'], 'ink-9'],
  ['tertiaryBorderColor', ink['6'], 'ink-6'],
  ['lineColor', ink['7'], 'ink-7 — arrows'],
  ['textColor', ink['9'], 'ink-9'],
  ['mainBkg', ink['3'], 'ink-3'],
  ['clusterBkg', ink['2'], 'ink-2 — subgraph fill'],
  ['clusterBorder', reef['dark'], 'reef-dark — subgraph border'],
  ['edgeLabelBackground', ink['1'], 'ink-1'],
  ['fontSize', '14px', null],
];

const light = [
  ['background', sand['1'], 'sand-1'],
  ['primaryColor', sand['2'], 'sand-2 — node fill'],
  ['primaryTextColor', sand['9'], 'sand-9'],
  ['primaryBorderColor', reef['light'], 'reef-light — accent border'],
  ['secondaryColor', sand['5'], 'sand-5'],
  ['secondaryTextColor', sand['9'], 'sand-9'],
  ['secondaryBorderColor', sand['6'], 'sand-6'],
  ['tertiaryColor', sand['2'], 'sand-2'],
  ['tertiaryTextColor', sand['9'], 'sand-9'],
  ['tertiaryBorderColor', sand['6'], 'sand-6'],
  ['lineColor', sand['7'], 'sand-7 — arrows'],
  ['textColor', sand['9'], 'sand-9'],
  ['mainBkg', sand['2'], 'sand-2'],
  ['clusterBkg', sand['4'], 'sand-4 — subgraph fill'],
  ['clusterBorder', reef['light'], 'reef-light — subgraph border'],
  ['edgeLabelBackground', sand['1'], 'sand-1'],
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
