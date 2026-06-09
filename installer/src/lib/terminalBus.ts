// A tiny pub/sub bridge between the install stream (in the Pinia store) and the
// xterm.js terminal instance (owned by InstallConsole.vue). Raw VPS console
// bytes can't live in reactive state — they're an append-only byte stream the
// terminal consumes imperatively — so the store writes here and the component
// subscribes. Decouples the data path from the DOM and survives the component
// remounting (late subscribers replay the buffer so nothing is lost).

type DataHandler = (chunk: string) => void;

const handlers = new Set<DataHandler>();
// Replay buffer so a terminal that mounts after streaming began still renders
// everything. Bounded to avoid unbounded growth on a very chatty install.
let buffer = '';
const MAX_BUFFER = 512 * 1024;

export function writeTerminal(chunk: string): void {
  buffer += chunk;
  if (buffer.length > MAX_BUFFER) buffer = buffer.slice(buffer.length - MAX_BUFFER);
  for (const h of handlers) h(chunk);
}

export function resetTerminal(): void {
  buffer = '';
}

// Subscribe to incoming chunks. The handler is immediately replayed the current
// buffer so a freshly-mounted terminal catches up. Returns an unsubscribe fn.
export function onTerminalData(handler: DataHandler): () => void {
  handlers.add(handler);
  if (buffer) handler(buffer);
  return () => handlers.delete(handler);
}
