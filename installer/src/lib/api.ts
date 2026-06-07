// In --public mode the Go server gates /api/* behind a 32-byte token; the
// wizard URL prints it in the URL fragment (#token=…) so it never appears in
// server logs or Referer headers. Read it once on boot and attach as a
// Bearer header on every /api/* request. In loopback mode the hash is empty
// and the server's requireToken wrapper is a no-op pass-through.
export const SETUP_TOKEN: string = (() => {
  const m = (location.hash || '').match(/(?:^#|&)token=([A-Za-z0-9_-]+)/);
  return m ? m[1] : '';
})();

export function apiHeaders(extra?: Record<string, string>): Record<string, string> {
  const h: Record<string, string> = { ...(extra ?? {}) };
  if (SETUP_TOKEN) h['Authorization'] = 'Bearer ' + SETUP_TOKEN;
  return h;
}

export async function apiFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const headers = apiHeaders(init.headers as Record<string, string> | undefined);
  return fetch(input, { ...init, headers });
}

export async function apiPostJson<T = unknown>(url: string, body: unknown): Promise<T> {
  const res = await apiFetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const j = (await res.json().catch(() => ({}))) as { ok?: boolean; error?: string };
  if (!res.ok || j.ok === false) {
    throw new Error(j.error || `HTTP ${res.status}`);
  }
  return j as T;
}
