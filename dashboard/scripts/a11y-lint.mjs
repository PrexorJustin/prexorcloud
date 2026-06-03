// Static accessibility lint for product .vue templates. Catches two high-signal,
// statically-decidable WCAG defects that an authed SPA can't easily cover with a
// running-app axe scan (most routes are behind login):
//
//   1. <img> without an `alt` (or bound `:alt`) — WCAG 1.1.1. `alt=""` is fine
//      (explicitly decorative); a missing attribute is the failure.
//   2. Icon-only interactive controls (<button>, <a>, <NuxtLink>, <Button>) with
//      no accessible name — WCAG 4.1.2. A name comes from text content, an
//      interpolation, aria-label/aria-labelledby/title, or an sr-only label span.
//
// Zero dependencies; same tag/text/quote tokenizer as check-hardcoded.mjs (Vue
// bindings carry ">" inside quoted attribute values). Histoire stories are
// excluded. Runs as `pnpm a11y:check`.
//
// When a flag is a genuine false positive (a control named by a mechanism this
// lint can't see), add aria-label or an sr-only span rather than suppressing —
// that is the actual fix. Truly-decorative icons belong in non-interactive
// elements or carry aria-hidden.

import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const APP = join(HERE, '..', 'app');

// Interactive elements whose icon-only form needs an explicit accessible name.
const INTERACTIVE = new Set(['button', 'a', 'nuxtlink', 'routerlink']);
// The design-system button primitive forwards to a native <button>.
const INTERACTIVE_COMPONENTS = new Set(['Button']);

const NAME_ATTRS = /(?:^|\s)(?::?aria-label|aria-labelledby|:?title)\s*=/;

const listVue = (dir, out = []) => {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === 'stories') continue;
      listVue(p, out);
    } else if (e.name.endsWith('.vue') && !e.name.endsWith('.story.vue')) {
      out.push(p);
    }
  }
  return out;
};

const blank = (s) => s.replace(/[^\n]/g, ' ');

const templateBlock = (src) => {
  const masked = src
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, blank)
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, blank);
  const m = /<template[^>]*>/i.exec(masked);
  if (!m) return null;
  const start = m.index + m[0].length;
  const end = masked.lastIndexOf('</template>');
  if (end < 0 || end < start) return null;
  return { text: masked.slice(start, end), offset: start };
};

// Tokenize into tags/text; quotes inside a tag shield ">" (v-if="a > 0").
function* tokenize(tpl) {
  let i = 0;
  const n = tpl.length;
  while (i < n) {
    if (tpl[i] === '<') {
      if (tpl.startsWith('<!--', i)) {
        const end = tpl.indexOf('-->', i + 4);
        i = end < 0 ? n : end + 3;
        continue;
      }
      let j = i + 1;
      let quote = null;
      while (j < n) {
        const c = tpl[j];
        if (quote) {
          if (c === quote) quote = null;
        } else if (c === '"' || c === "'") {
          quote = c;
        } else if (c === '>') {
          break;
        }
        j++;
      }
      yield { type: 'tag', text: tpl.slice(i, j + 1), start: i, end: j + 1 };
      i = j + 1;
    } else {
      let j = i;
      while (j < n && tpl[j] !== '<') j++;
      yield { type: 'text', text: tpl.slice(i, j), start: i };
      i = j;
    }
  }
}

const lineAt = (src, idx) => src.slice(0, idx).split('\n').length;
const tagInfo = (tagText) => {
  const m = /^<\s*(\/?)\s*([a-zA-Z][\w-]*)/.exec(tagText);
  if (!m) return null;
  return { closing: !!m[1], name: m[2], selfClosing: /\/>\s*$/.test(tagText) };
};

// An interactive element is "named" if its open tag carries a name attribute, or
// its inner markup (until the matching close) holds text, an interpolation, an
// sr-only label, or a nested aria-label. These elements don't self-nest in
// practice, so the first matching close tag bounds the content.
const hasAccessibleName = (openTag, innerHtml) => {
  if (NAME_ATTRS.test(openTag)) return true;
  if (/\{\{/.test(innerHtml)) return true; // dynamic text content
  if (/sr-only/.test(innerHtml)) return true; // visually-hidden label span
  if (/aria-label|aria-labelledby/.test(innerHtml)) return true; // labelled child
  const textOnly = innerHtml
    .replace(/<[^>]*>/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  return textOnly.length > 0;
};

const findings = [];

for (const file of listVue(APP)) {
  const src = readFileSync(file, 'utf8');
  const tpl = templateBlock(src);
  if (!tpl) continue;
  const rel = relative(join(HERE, '..'), file);
  const tokens = [...tokenize(tpl.text)];

  for (let k = 0; k < tokens.length; k++) {
    const tok = tokens[k];
    if (tok.type !== 'tag') continue;
    const info = tagInfo(tok.text);
    if (!info || info.closing) continue;
    const lname = info.name.toLowerCase();
    const abs = tpl.offset + tok.start;

    // (1) <img> without alt / :alt
    if (lname === 'img') {
      if (!/(?:^|\s):?alt\s*=/.test(tok.text)) {
        findings.push({ rel, line: lineAt(src, abs), rule: 'img-alt', detail: 'img without alt' });
      }
      continue;
    }

    const isInteractive = INTERACTIVE.has(lname) || INTERACTIVE_COMPONENTS.has(info.name);
    if (!isInteractive) continue;

    // Reconstruct inner markup up to the matching close tag (same name).
    let inner = '';
    if (!info.selfClosing) {
      let depth = 1;
      for (let j = k + 1; j < tokens.length; j++) {
        const t = tokens[j];
        if (t.type === 'tag') {
          const ti = tagInfo(t.text);
          if (ti && ti.name.toLowerCase() === lname && !ti.selfClosing) {
            if (ti.closing) {
              depth--;
              if (depth === 0) break;
            } else {
              depth++;
            }
          }
        }
        inner += t.text;
      }
    }
    if (!hasAccessibleName(tok.text, inner)) {
      findings.push({
        rel,
        line: lineAt(src, abs),
        rule: 'control-name',
        detail: `<${info.name}> has no accessible name (icon-only)`,
      });
    }
  }
}

if (findings.length === 0) {
  console.log('a11y lint OK — no missing image alts or unnamed icon controls');
  process.exit(0);
}

findings.sort((a, b) => a.rel.localeCompare(b.rel) || a.line - b.line);
console.error(`a11y lint FAILED — ${findings.length} issue(s):\n`);
for (const f of findings) {
  console.error(`  ${f.rel}:${f.line}  [${f.rule}]  ${f.detail}`);
}
console.error('\nFix: give <img> an alt (alt="" if decorative); give icon-only controls an');
console.error('aria-label, a title, or an sr-only label span.');
process.exit(1);
