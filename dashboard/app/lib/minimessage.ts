/**
 * Minimal MiniMessage → HTML converter for dashboard preview purposes.
 * Supports color tags, decorations, and reset. Not a full implementation.
 */

const COLOR_MAP: Record<string, string> = {
  black: '#000000', dark_blue: '#0000AA', dark_green: '#00AA00',
  dark_aqua: '#00AAAA', dark_red: '#AA0000', dark_purple: '#AA00AA',
  gold: '#FFAA00', gray: '#AAAAAA', dark_gray: '#555555',
  blue: '#5555FF', green: '#55FF55', aqua: '#55FFFF',
  red: '#FF5555', light_purple: '#FF55FF', yellow: '#FFFF55',
  white: '#FFFFFF',
}

interface Style {
  color?: string
  bold?: boolean
  italic?: boolean
  underline?: boolean
  strikethrough?: boolean
}

export function renderMiniMessage(input: string): string {
  const stack: Style[] = [{}]

  function current(): Style {
    return stack[stack.length - 1] ?? {}
  }

  function applyStyle(html: string): string {
    const s = current()
    let result = html
    if (s.bold) result = `<b>${result}</b>`
    if (s.italic) result = `<em>${result}</em>`
    if (s.underline) result = `<u>${result}</u>`
    if (s.strikethrough) result = `<s>${result}</s>`
    if (s.color) result = `<span style="color:${s.color}">${result}</span>`
    return result
  }

  const TAG_RE = /<(\/?)([^>]+)>/g
  let out = ''
  let last = 0

  for (const match of input.matchAll(TAG_RE)) {
    const [full, slash, tag] = match
    const idx = match.index!

    if (idx > last) {
      const text = escapeHtml(input.slice(last, idx))
      out += applyStyle(text)
    }
    last = idx + (full?.length ?? 0)

    const closing = slash === '/'
    const lower = (tag ?? '').toLowerCase()

    if (lower === 'reset' || (closing && lower === 'reset')) {
      stack.splice(1)
    } else if (closing) {
      if (stack.length > 1) stack.pop()
    } else if (lower === 'bold' || lower === 'b') {
      stack.push({ ...current(), bold: true })
    } else if (lower === 'italic' || lower === 'i' || lower === 'em') {
      stack.push({ ...current(), italic: true })
    } else if (lower === 'underlined' || lower === 'u') {
      stack.push({ ...current(), underline: true })
    } else if (lower === 'strikethrough' || lower === 'st') {
      stack.push({ ...current(), strikethrough: true })
    } else if (COLOR_MAP[lower]) {
      stack.push({ ...current(), color: COLOR_MAP[lower] })
    } else if (lower.startsWith('#') && /^#[0-9a-f]{6}$/i.test(lower)) {
      stack.push({ ...current(), color: lower })
    } else if (lower.startsWith('color:')) {
      const c = lower.slice(6).trim()
      const hex = COLOR_MAP[c] ?? (c.startsWith('#') ? c : undefined)
      if (hex) stack.push({ ...current(), color: hex })
    }
  }

  if (last < input.length) {
    out += applyStyle(escapeHtml(input.slice(last)))
  }

  return out
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
