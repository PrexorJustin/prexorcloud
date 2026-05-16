// Cheap YAML syntax highlighter — line by line, no real parsing. Output is
// HTML with .yk/.ys/.yn/.yb/.yc/.yd spans the Reef palette colours in
// wizard.css. Ported verbatim from the legacy wizard.

export function escapeHtml(s: string | number | null | undefined): string {
  return String(s ?? '').replace(/[&<>"']/g, (c) => {
    switch (c) {
      case '&': return '&amp;';
      case '<': return '&lt;';
      case '>': return '&gt;';
      case '"': return '&quot;';
      case "'": return '&#39;';
      default: return c;
    }
  });
}

function highlightValue(v: string): string {
  if (v === '' || v == null) return '';
  if (v === 'true' || v === 'false') return `<span class="yb">${v}</span>`;
  if (v === 'null' || v === '~') return `<span class="yc">${v}</span>`;
  if (/^-?\d+(\.\d+)?$/.test(v)) return `<span class="yn">${v}</span>`;
  const hashAt = v.indexOf(' #');
  if (hashAt > 0) {
    return `<span class="ys">${escapeHtml(v.slice(0, hashAt))}</span><span class="yc">${escapeHtml(v.slice(hashAt))}</span>`;
  }
  return `<span class="ys">${escapeHtml(v)}</span>`;
}

export function highlightYaml(text: string): string {
  return text
    .split('\n')
    .map((raw) => {
      if (/^\s*#/.test(raw)) return `<span class="yc">${escapeHtml(raw)}</span>`;
      const m = raw.match(/^(\s*)(- )?(.*)$/);
      const indent = m?.[1] ?? '';
      const dash = m?.[2] ?? '';
      const rest = m?.[3] ?? '';
      const kv = rest.match(/^([A-Za-z0-9_.\-]+)(:)(\s*)(.*)$/);
      let body: string;
      if (kv) {
        const [, key, colon, sp, val] = kv;
        body = `<span class="yk">${escapeHtml(key)}</span>${colon}${sp}${highlightValue(val)}`;
      } else {
        body = highlightValue(rest);
      }
      const dashHtml = dash ? `<span class="yd">${dash}</span>` : '';
      return indent + dashHtml + body;
    })
    .join('\n');
}
