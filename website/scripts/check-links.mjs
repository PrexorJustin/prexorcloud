// Quick site-relative-link checker. Walks docs/public/en/, extracts every
// markdown link with a path that begins `/`, and verifies the target either
// resolves to a markdown file in the tree or matches one of the known
// auto-generated route prefixes (REST API endpoints, /playground, /).
//
// Run with `node scripts/check-links.mjs` from website/.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const docsRoot = resolve(here, '../../docs/public/en');

const knownRoutes = new Set(['/', '/playground/']);

// Auto-gen prefixes — matches starlight-openapi output under /reference/rest-api/operations/.
const autogenPrefixes = ['/reference/rest-api/'];

function listMd(dir, out = []) {
  for (const ent of readdirSync(dir, { withFileTypes: true })) {
    // Skip auto-generated trees (`_generated/`, `_*` in general). The
    // docs collection (website/src/content.config.ts) has the same rule;
    // keeping them aligned avoids "broken link" reports for files that
    // never reach the published site.
    if (ent.name.startsWith('_')) continue;
    const p = join(dir, ent.name);
    if (ent.isDirectory()) listMd(p, out);
    else if (ent.isFile() && /\.(md|mdx)$/i.test(ent.name)) out.push(p);
  }
  return out;
}

const files = listMd(docsRoot);
const fileSet = new Set(files);

function pathToSlug(file) {
  // docs/public/en/foo/bar.md  ->  /foo/bar/
  // docs/public/en/foo/index.md -> /foo/
  let rel = file.slice(docsRoot.length);
  rel = rel.replace(/\.(md|mdx)$/i, '');
  if (rel.endsWith('/index')) rel = rel.slice(0, -'/index'.length);
  if (rel === '') rel = '/';
  if (!rel.endsWith('/')) rel += '/';
  return rel;
}

const slugSet = new Set(files.map(pathToSlug));
slugSet.add('/');
for (const r of knownRoutes) slugSet.add(r);

const linkRe = /\[[^\]]+\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g;

const issues = [];
for (const f of files) {
  const txt = readFileSync(f, 'utf8');
  let m;
  while ((m = linkRe.exec(txt))) {
    let href = m[1];
    if (!href.startsWith('/')) continue; // skip external/relative
    if (href.startsWith('//')) continue; // protocol-relative
    href = href.split('#')[0].split('?')[0];
    if (!href.endsWith('/')) href += '/';
    if (slugSet.has(href)) continue;
    if (autogenPrefixes.some((p) => href.startsWith(p))) continue;
    issues.push({ file: f.slice(docsRoot.length), href });
  }
}

if (issues.length === 0) {
  console.log(`OK — checked ${files.length} files, no broken site-relative links.`);
  process.exit(0);
}

const byFile = new Map();
for (const { file, href } of issues) {
  if (!byFile.has(file)) byFile.set(file, new Set());
  byFile.get(file).add(href);
}
console.error(`FAIL — ${issues.length} broken links across ${byFile.size} files.\n`);
for (const [file, hrefs] of [...byFile.entries()].sort()) {
  console.error(`  ${file}`);
  for (const h of [...hrefs].sort()) console.error(`    ${h}`);
}
process.exit(1);
