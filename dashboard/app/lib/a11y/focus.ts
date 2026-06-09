/**
 * Keyboard-focus helpers for accessibility (WCAG 2.4.3 Focus Order).
 *
 * Used to move focus into newly revealed content — e.g. the next panel of a
 * multi-step dialog — so keyboard and screen-reader users follow the change
 * instead of being stranded on the now-changed trigger button.
 *
 * Layout-free by design: visibility is decided from attributes/inline styles
 * (hidden, [hidden], aria-hidden, display/visibility), never computed geometry,
 * so the helpers behave identically under jsdom and a real browser. Content
 * toggled with `v-if` is removed from the DOM entirely, so the "currently
 * visible" set is just "what's in the tree".
 */

const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  '[tabindex]:not([tabindex="-1"])',
].join(",")

function isHidden(el: HTMLElement): boolean {
  for (let node: HTMLElement | null = el; node; node = node.parentElement) {
    if (node.hidden) return true
    if (node.getAttribute("aria-hidden") === "true") return true
    const style = node.style
    if (style && (style.display === "none" || style.visibility === "hidden")) return true
  }
  return false
}

/**
 * First keyboard-focusable, non-disabled, non-hidden descendant of {@code root}
 * in DOM order — or null if there is none.
 */
export function firstFocusable(root: HTMLElement | null | undefined): HTMLElement | null {
  if (!root) return null
  for (const el of root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)) {
    if (!isHidden(el)) return el
  }
  return null
}

/**
 * Move focus to the first focusable control inside {@code root}. When {@code root}
 * has no focusable descendant it is made programmatically focusable and focused
 * itself, so focus never silently stays on stale content. Returns the element
 * that received focus, or null if {@code root} is absent.
 */
export function focusFirst(root: HTMLElement | null | undefined): HTMLElement | null {
  if (!root) return null
  const target = firstFocusable(root)
  if (target) {
    target.focus()
    return target
  }
  if (root.tabIndex < 0) root.tabIndex = -1
  root.focus()
  return root
}
