import { onMounted, onUnmounted, ref } from "vue"
import { useEventListener } from "@vueuse/core"
import { isShortcutPrefix, matchShortcut, shortcuts } from "~/lib/shortcuts"

/**
 * Global keyboard-shortcut dispatcher. Mounts a window keydown listener,
 * tracks chord sequences (e.g. `g i`), and fires either the `to` (router
 * push) or `handler` (imperative) for the matched shortcut.
 *
 * Skips when the focus is inside an input/textarea/contenteditable to avoid
 * stealing typing. The `?` overlay binding is wired here so the layout
 * doesn't need its own listener.
 */
export function useShortcuts(options?: {
  onShowOverlay?: () => void
  onShowPalette?: () => void
}) {
  const router = useRouter()
  const buffer = ref<string[]>([])
  let resetTimer: ReturnType<typeof setTimeout> | null = null

  function isTypingTarget(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) return false
    if (target.isContentEditable) return true
    const tag = target.tagName
    return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT"
  }

  function clearBuffer() {
    buffer.value = []
    if (resetTimer) { clearTimeout(resetTimer); resetTimer = null }
  }

  function fire(keys: string) {
    const s = shortcuts.find(x => x.keys === keys)
    if (!s) return
    if (s.to) router.push(s.to)
    s.handler?.()
  }

  useEventListener("keydown", (e: KeyboardEvent) => {
    if (isTypingTarget(e.target)) return

    // Modifier chords first — meta+k.
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
      e.preventDefault()
      options?.onShowPalette?.()
      clearBuffer()
      return
    }

    // Bare keys — ?, Esc.
    if (e.key === "?") {
      e.preventDefault()
      options?.onShowOverlay?.()
      clearBuffer()
      return
    }

    if (e.key === "Escape") {
      clearBuffer()
      return
    }

    // Two-key sequences: lowercase letters only.
    const k = e.key.toLowerCase()
    if (!/^[a-z]$/.test(k)) {
      clearBuffer()
      return
    }

    const matched = matchShortcut(buffer.value, k)
    if (matched) {
      e.preventDefault()
      fire(matched.keys)
      clearBuffer()
      return
    }

    if (isShortcutPrefix(buffer.value, k)) {
      buffer.value = [...buffer.value, k]
      if (resetTimer) clearTimeout(resetTimer)
      resetTimer = setTimeout(clearBuffer, 1200)
    } else {
      clearBuffer()
    }
  })

  onMounted(() => clearBuffer())
  onUnmounted(() => clearBuffer())

  return { buffer }
}
