/**
 * Single source of truth for keyboard shortcuts. The `?` overlay reads from
 * here, and `useShortcuts()` installs a global keydown listener that
 * dispatches based on these bindings.
 *
 * Chord syntax: space-separated tokens. Single-modifier chords use `+`
 * (e.g. `meta+k`). Two-key sequences are space-separated (e.g. `g i`).
 * Modifier chords always fire immediately; sequences wait for the second
 * key with a 1.2s timeout.
 */
export interface Shortcut {
  /** Chord representation: e.g. "g i", "meta+k", "?", "esc". */
  keys: string
  label: string
  section: "Navigation" | "Search" | "Actions" | "Help"
  /** Where to send the user. Mutually exclusive with `handler`. */
  to?: string
  /** Imperative handler. Mutually exclusive with `to`. */
  handler?: () => void
  /** Hide from the overlay (useful for handlers wired elsewhere). */
  hidden?: boolean
}

export const shortcuts: Shortcut[] = [
  // Navigation chords — `g` then a letter.
  { keys: "g d",  label: "Go to dashboard",     section: "Navigation", to: "/" },
  { keys: "g m",  label: "Go to map",           section: "Navigation", to: "/map" },
  { keys: "g i",  label: "Go to instances",    section: "Navigation", to: "/instances" },
  { keys: "g g",  label: "Go to groups",       section: "Navigation", to: "/groups" },
  { keys: "g n",  label: "Go to nodes",        section: "Navigation", to: "/nodes" },
  { keys: "g t",  label: "Go to templates",    section: "Navigation", to: "/templates" },
  { keys: "g c",  label: "Go to crashes",      section: "Navigation", to: "/crashes" },
  { keys: "g a",  label: "Go to activity",     section: "Navigation", to: "/observability/activity" },
  { keys: "g l",  label: "Go to logs",         section: "Navigation", to: "/observability/logs" },
  { keys: "g s",  label: "Go to system status", section: "Navigation", to: "/observability/system" },
  { keys: "g u",  label: "Go to users",        section: "Navigation", to: "/users" },
  { keys: "g b",  label: "Go to backups",      section: "Navigation", to: "/operations/backups" },

  // Search / overlays — handler-bound at the layout level.
  { keys: "meta+k", label: "Open command palette", section: "Search", hidden: true },
  { keys: "?",      label: "Show keyboard shortcuts", section: "Help", hidden: true },
  { keys: "esc",    label: "Close panel / dialog", section: "Actions", hidden: true },
]

/**
 * Try to match the given keypress against the active sequence buffer.
 * Returns the matched shortcut, or null if no match (or partial match).
 */
export function matchShortcut(buffer: string[], pressed: string): Shortcut | null {
  // Build the candidate chord from buffer + pressed.
  const candidate = [...buffer, pressed].join(" ")
  for (const s of shortcuts) {
    if (s.keys === candidate) return s
  }
  return null
}

export function isShortcutPrefix(buffer: string[], pressed: string): boolean {
  const prefix = [...buffer, pressed].join(" ") + " "
  return shortcuts.some(s => s.keys.startsWith(prefix))
}
