#!/usr/bin/env -S node --experimental-strip-types
/*
 * tools/gen-cli-docs.ts — generate Markdown documentation for every
 * `prexorctl` subcommand by walking `--help` output recursively.
 *
 * Output goes to `docs/public/en/reference/cli/_generated/<cmd-tree>.md`.
 * The `_generated` segment is intentionally underscore-prefixed so the
 * Astro content collection in `website/src/content.config.ts` will skip
 * it (the glob pattern excludes `_*` paths). The hand-curated wrapping
 * pages under `docs/public/en/reference/cli/` link to specific
 * `_generated/...` files where useful — they stay the canonical entry
 * points; this tree is the drift-detector / underlying truth.
 *
 * Usage:
 *   node --experimental-strip-types tools/gen-cli-docs.ts          # uses ./cli/prexorctl
 *   node --experimental-strip-types tools/gen-cli-docs.ts --build  # rebuilds binary first
 *
 * Run from the repo root.
 *
 * Requires: Node ≥ 22 (built-in TS stripping). Pure stdlib — no deps.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

const ROOT = resolve(import.meta.dirname, '..');
const BIN = process.platform === 'win32' ? 'prexorctl.exe' : 'prexorctl';
const BIN_PATH = resolve(ROOT, 'cli', BIN);
const OUT_DIR = resolve(ROOT, 'docs/public/en/reference/cli/_generated');

const args = new Set(process.argv.slice(2));

function buildBinary(): void {
  console.log('[gen-cli-docs] building cli/prexorctl…');
  execFileSync('make', ['build'], { cwd: resolve(ROOT, 'cli'), stdio: 'inherit' });
}

// Strip ANSI escape codes — prexorctl ships a custom help template that
// uses bold `\x1b[1m…\x1b[m` for section headers even with NO_COLOR set.
const ANSI_RE = /\x1b\[[0-9;]*[A-Za-z]/g;

function help(...path: string[]): string {
  const raw = execFileSync(BIN_PATH, [...path, '--help'], {
    encoding: 'utf8',
    env: { ...process.env, NO_COLOR: '1', TERM: 'dumb' },
  });
  return raw.replace(ANSI_RE, '');
}

interface ParsedHelp {
  short: string;
  long: string;
  usage: string;
  flags: string;
  globalFlags: string;
  examples: string;
  subcommands: string[];
}

function parse(out: string): ParsedHelp {
  // Two header formats are supported:
  //   • Stock Cobra: "Usage:", "Available Commands:", "Flags:", "Global Flags:".
  //   • prexorctl's custom template: bare-line "USAGE", "COMMANDS", "FLAGS",
  //     "EXAMPLES", "GLOBAL FLAGS" (uppercase, no trailing colon).
  // Both flavours feed into the same canonical bucket names below.
  const HEADER_ALIASES: Record<string, string> = {
    'Usage:': 'Usage',
    'USAGE': 'Usage',
    'Available Commands:': 'Available Commands',
    'COMMANDS': 'Available Commands',
    'SUBCOMMANDS': 'Available Commands',
    'Examples:': 'Examples',
    'EXAMPLES': 'Examples',
    'Aliases:': 'Aliases',
    'ALIASES': 'Aliases',
    'Flags:': 'Flags',
    'FLAGS': 'Flags',
    'Global Flags:': 'Global Flags',
    'GLOBAL FLAGS': 'Global Flags',
  };
  const lines = out.split(/\r?\n/);
  const sections: Record<string, string[]> = {
    head: [],
    Usage: [],
    'Available Commands': [],
    Examples: [],
    Aliases: [],
    Flags: [],
    'Global Flags': [],
  };
  let cur = 'head';
  for (const line of lines) {
    const trimmed = line.trim();
    const canonical = HEADER_ALIASES[trimmed];
    if (canonical && sections[canonical] !== undefined) {
      cur = canonical;
      continue;
    }
    sections[cur].push(line);
  }
  const headLines = sections.head
    .map(l => l.trimEnd())
    .filter(l => l.length > 0)
    // prexorctl's custom template prints a `▲ prexorctl <cmd>  •  <subtitle>`
    // banner at the top; not useful as a description.
    .filter(l => !/^▲\s+prexorctl/.test(l))
    .filter(l => !/^Use ["']?.+["']? for more information/i.test(l));
  const description = headLines.join('\n').trim();
  const short = description.split(/\.\s/)[0]?.trim() ?? '';

  // Strip the same trailers from any other section so `Global flags` doesn't
  // end with "Use prexorctl <cmd> [command] --help for more information".
  const cleanSection = (lines: string[]) =>
    lines.filter(l => !/^Use ["']?.+--help.+for more information/i.test(l.trim()));
  for (const k of Object.keys(sections)) {
    sections[k] = cleanSection(sections[k]);
  }

  const subcommands: string[] = [];
  for (const line of sections['Available Commands']) {
    const m = line.match(/^\s+([a-z][a-z0-9-]*)\s/i);
    if (m && m[1] !== 'help' && m[1] !== 'completion') subcommands.push(m[1]);
  }

  return {
    short,
    long: description,
    usage: sections.Usage.join('\n').trim(),
    flags: sections.Flags.join('\n').trim(),
    globalFlags: sections['Global Flags'].join('\n').trim(),
    examples: sections.Examples.join('\n').trim(),
    subcommands,
  };
}

function indentToCodeBlock(s: string): string {
  if (!s) return '';
  return ['```text', s, '```'].join('\n');
}

function pageFor(path: string[], parsed: ParsedHelp, parentSlug: string | null, childSlugs: { name: string; slug: string }[]): string {
  const cmd = ['prexorctl', ...path].join(' ');
  const titlePath = path.length > 0 ? path.join(' ') : '(root)';
  const title = `prexorctl ${titlePath}`.trim();
  const description = parsed.short || `Reference for \`${cmd}\`.`;
  const out: string[] = [];
  out.push('---');
  out.push(`title: ${JSON.stringify(title)}`);
  out.push(`description: ${JSON.stringify(description.slice(0, 160))}`);
  out.push('---');
  out.push('');
  out.push(`<!-- auto-generated by tools/gen-cli-docs.ts — do not edit by hand. -->`);
  out.push('');
  if (parsed.long && parsed.long !== parsed.short) {
    out.push(parsed.long);
    out.push('');
  }
  out.push('## Usage');
  out.push('');
  out.push(indentToCodeBlock(parsed.usage));
  out.push('');
  if (parsed.examples) {
    out.push('## Examples');
    out.push('');
    out.push(indentToCodeBlock(parsed.examples));
    out.push('');
  }
  if (parsed.flags) {
    out.push('## Flags');
    out.push('');
    out.push(indentToCodeBlock(parsed.flags));
    out.push('');
  }
  if (parsed.globalFlags) {
    out.push('## Global flags');
    out.push('');
    out.push(indentToCodeBlock(parsed.globalFlags));
    out.push('');
  }
  if (childSlugs.length > 0) {
    out.push('## Subcommands');
    out.push('');
    for (const c of childSlugs) {
      out.push(`- [\`${c.name}\`](./${c.slug}/)`);
    }
    out.push('');
  }
  if (parentSlug !== null) {
    out.push('## Parent');
    out.push('');
    out.push(`- [${parentSlug || '(root)'}](../)`);
    out.push('');
  }
  return out.join('\n');
}

function slugFor(path: string[]): string {
  return path.length === 0 ? 'index' : path.join('-');
}

function walk(path: string[]): { name: string; slug: string }[] {
  const parsed = parse(help(...path));
  const childPairs = parsed.subcommands.map(c => {
    const childPath = [...path, c];
    walk(childPath);
    return { name: c, slug: slugFor(childPath) };
  });
  const slug = slugFor(path);
  const parentSlug = path.length === 0 ? null : path.slice(0, -1).join(' ');
  const dest = join(OUT_DIR, slug === 'index' ? 'index.md' : `${slug}.md`);
  mkdirSync(dirname(dest), { recursive: true });
  writeFileSync(dest, pageFor(path, parsed, parentSlug, childPairs));
  return childPairs;
}

function main(): void {
  if (args.has('--build') || !existsSync(BIN_PATH)) buildBinary();
  if (!existsSync(BIN_PATH)) {
    console.error(`[gen-cli-docs] cannot find ${BIN_PATH}; pass --build to compile it.`);
    process.exit(1);
  }

  // Wipe and recreate so removed commands don't leave stale files.
  if (existsSync(OUT_DIR)) rmSync(OUT_DIR, { recursive: true });
  mkdirSync(OUT_DIR, { recursive: true });

  walk([]);

  const count = readdirSync(OUT_DIR, { recursive: true })
    .filter(f => typeof f === 'string' && f.endsWith('.md')).length;
  console.log(`[gen-cli-docs] wrote ${count} MD files to ${OUT_DIR}`);
}

main();
