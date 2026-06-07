// Crypto helpers used by the wizard to fill in JWT secret, admin password,
// node UUIDs etc. Pure helpers — no DOM, no Pinia. Mirrors the cryptoUuid /
// genSecret / genPassword helpers from the legacy single-file wizard.

function randBytes(n: number): Uint8Array {
  const buf = new Uint8Array(n);
  crypto.getRandomValues(buf);
  return buf;
}

export function genUuid(): string {
  // Use the platform helper when available; fall back to RFC-4122 v4.
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = randBytes(16);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

// Alphanumeric secret of the requested character length. Matches the legacy
// `genSecret` alphabet so the JWT secret + join-token formats stay identical.
export function genSecret(len = 64): string {
  const bytes = new Uint8Array(Math.ceil(len * 1.5));
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes))
    .replace(/[^a-zA-Z0-9]/g, '')
    .slice(0, len);
}

// 16-char human-friendly password (mix of upper/lower/digit/symbol, no easily
// confused glyphs). Matches the legacy `genPassword` alphabet so generated
// values look identical to operators who've used the previous wizard.
export function genPassword(len = 16): string {
  const alphabet = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!#$%*+-=?@';
  const b = new Uint32Array(len);
  crypto.getRandomValues(b);
  let out = '';
  for (let i = 0; i < len; i++) out += alphabet[b[i] % alphabet.length];
  return out;
}

// Daemon bootstrap token. The `pcjt_` prefix is what the controller's
// /api/v1/bootstrap/exchange endpoint expects.
export function genJoinToken(): string {
  return 'pcjt_' + genSecret(36);
}
