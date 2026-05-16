import { EditorView } from "@codemirror/view"
import { HighlightStyle } from "@codemirror/language"
import { tags } from "@lezer/highlight"
import { yaml } from "@codemirror/lang-yaml"
import { json } from "@codemirror/lang-json"
import { xml } from "@codemirror/lang-xml"
import { html } from "@codemirror/lang-html"
import { css } from "@codemirror/lang-css"
import { javascript } from "@codemirror/lang-javascript"
import { java } from "@codemirror/lang-java"
import { markdown } from "@codemirror/lang-markdown"

// Language extensions mapped by key
export const langMap: Record<string, () => ReturnType<typeof yaml>> = {
  yaml, json, xml, html, css, java, markdown,
  javascript: () => javascript(),
  typescript: () => javascript({ typescript: true }),
}

/**
 * Highlight style — colors come from CSS vars so they auto-swap with the
 * dashboard theme. The fallback OKLCH values live in mid-luminosity so they
 * read on both dark slate and light sand backgrounds.
 */
export const prexorHighlight = HighlightStyle.define([
  { tag: tags.keyword, color: "var(--cm-keyword, var(--primary))" },
  { tag: tags.comment, color: "var(--cm-comment, var(--muted-foreground))", fontStyle: "italic" },
  { tag: tags.string, color: "var(--cm-string, oklch(0.55 0.15 152))" },
  { tag: tags.number, color: "var(--cm-number, oklch(0.58 0.17 25))" },
  { tag: tags.bool, color: "var(--cm-keyword, var(--primary))" },
  { tag: tags.null, color: "var(--cm-keyword, var(--primary))" },
  { tag: tags.propertyName, color: "var(--cm-property, var(--foreground))" },
  { tag: tags.variableName, color: "var(--cm-variable, var(--foreground))" },
  { tag: tags.definition(tags.variableName), color: "var(--cm-keyword, var(--primary))" },
  { tag: tags.typeName, color: "var(--cm-type, var(--primary))" },
  { tag: tags.tagName, color: "var(--cm-keyword, var(--primary))" },
  { tag: tags.attributeName, color: "var(--cm-property, oklch(0.6 0.17 55))" },
  { tag: tags.attributeValue, color: "var(--cm-string, oklch(0.55 0.15 152))" },
  { tag: tags.meta, color: "var(--cm-comment, var(--muted-foreground))" },
  { tag: tags.bracket, color: "var(--cm-bracket, var(--muted-foreground))" },
  { tag: tags.operator, color: "var(--cm-operator, var(--muted-foreground))" },
  { tag: tags.punctuation, color: "var(--cm-punctuation, var(--muted-foreground))" },
  { tag: tags.heading, color: "var(--cm-keyword, var(--primary))", fontWeight: "bold" },
  { tag: tags.link, color: "var(--cm-string, var(--primary))", textDecoration: "underline" },
  { tag: tags.atom, color: "var(--cm-number, oklch(0.58 0.17 25))" },
])

const baseSpec = {
  "&": {
    backgroundColor: "var(--background)",
    color: "var(--foreground)",
    fontSize: "13px",
    height: "100%",
  },
  ".cm-content": {
    caretColor: "var(--primary)",
    fontFamily: "'JetBrains Mono', ui-monospace, 'Cascadia Code', 'Source Code Pro', Menlo, Consolas, monospace",
    padding: "12px 0",
  },
  ".cm-cursor, .cm-dropCursor": {
    borderLeftColor: "var(--primary)",
    borderLeftWidth: "2px",
  },
  ".cm-selectionBackground": {
    backgroundColor: "color-mix(in oklch, var(--primary) 20%, transparent) !important",
  },
  "&.cm-focused .cm-selectionBackground": {
    backgroundColor: "color-mix(in oklch, var(--primary) 25%, transparent) !important",
  },
  ".cm-activeLine": { backgroundColor: "var(--glass)" },
  ".cm-activeLineGutter": { backgroundColor: "var(--glass)" },
  ".cm-gutters": {
    backgroundColor: "var(--background)",
    color: "var(--muted-foreground)",
    border: "none",
    paddingRight: "8px",
  },
  ".cm-lineNumbers .cm-gutterElement": {
    minWidth: "3ch",
    padding: "0 8px 0 12px",
    fontSize: "12px",
  },
  ".cm-activeLineGutter .cm-gutterElement": { color: "var(--foreground)" },
  ".cm-foldGutter .cm-gutterElement": {
    padding: "0 4px",
    color: "var(--muted-foreground)",
    cursor: "pointer",
    "&:hover": { color: "var(--foreground)" },
  },
  ".cm-matchingBracket": {
    backgroundColor: "color-mix(in oklch, var(--primary) 20%, transparent)",
    outline: "1px solid color-mix(in oklch, var(--primary) 40%, transparent)",
  },
  ".cm-scroller": {
    overflow: "auto",
    scrollbarWidth: "thin",
    scrollbarColor: "color-mix(in oklch, var(--muted-foreground) 30%, transparent) transparent",
  },
  ".cm-tooltip": {
    backgroundColor: "var(--popover)",
    color: "var(--popover-foreground)",
    border: "1px solid var(--glass-border)",
    borderRadius: "8px",
    boxShadow: "var(--shadow-lg)",
  },
  ".cm-panels": {
    backgroundColor: "var(--glass)",
    color: "var(--foreground)",
    borderBottom: "1px solid var(--glass-border)",
  },
  ".cm-panels input, .cm-panels button": { color: "var(--foreground)" },
  ".cm-searchMatch": {
    backgroundColor: "color-mix(in oklch, var(--primary) 25%, transparent)",
    outline: "1px solid color-mix(in oklch, var(--primary) 40%, transparent)",
  },
  ".cm-searchMatch.cm-searchMatch-selected": {
    backgroundColor: "color-mix(in oklch, var(--primary) 40%, transparent)",
  },
}

/**
 * Dark theme: tells CodeMirror to apply the `cm-dark` class so its built-in
 * styles use dark-appropriate defaults for anything we haven't overridden.
 */
export const prexorThemeDark = EditorView.theme(baseSpec, { dark: true })

/**
 * Light theme: same selectors, but no `cm-dark` flag — CodeMirror's defaults
 * adapt to a light background. All colors still resolve via CSS vars which
 * the dashboard's `.light` class swaps to the Sand palette.
 */
export const prexorThemeLight = EditorView.theme(baseSpec, { dark: false })

/** Convenience: pick the right theme for the current `dark` boolean. */
export function prexorTheme(dark: boolean) {
  return dark ? prexorThemeDark : prexorThemeLight
}
