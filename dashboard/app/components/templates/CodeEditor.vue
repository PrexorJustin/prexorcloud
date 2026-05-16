<script setup lang="ts">
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, rectangularSelection } from "@codemirror/view"
import { EditorState } from "@codemirror/state"
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands"
import { syntaxHighlighting, defaultHighlightStyle, indentOnInput, bracketMatching, foldGutter, foldKeymap } from "@codemirror/language"
import { lintGutter, setDiagnostics } from "@codemirror/lint"
import { prexorTheme, prexorHighlight, langMap } from "~/lib/codemirror-theme"

const props = defineProps<{
  modelValue: string
  language?: string
  readOnly?: boolean
  diagnostics?: { line: number; column: number; message: string; severity: 'error' | 'warning' }[]
}>()

const emit = defineEmits<{
  "update:modelValue": [value: string]
}>()

const colorMode = useColorMode()
const isDark = computed(() => colorMode.value === 'dark')

const editorRef = ref<HTMLDivElement>()
let view: EditorView | null = null

function getLangExtension() {
  const lang = props.language ?? "plaintext"
  const factory = langMap[lang]
  return factory ? [factory()] : []
}

function createState(content: string) {
  const extensions = [
    prexorTheme(isDark.value),
    syntaxHighlighting(prexorHighlight),
    syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
    lineNumbers(),
    highlightActiveLine(),
    highlightActiveLineGutter(),
    drawSelection(),
    rectangularSelection(),
    indentOnInput(),
    bracketMatching(),
    foldGutter(),
    history(),
    lintGutter(),
    keymap.of([
      ...defaultKeymap,
      ...historyKeymap,
      ...foldKeymap,
      indentWithTab,
    ]),
    ...getLangExtension(),
    EditorView.updateListener.of((update) => {
      if (update.docChanged) {
        emit("update:modelValue", update.state.doc.toString())
      }
    }),
    EditorView.lineWrapping,
  ]

  if (props.readOnly) {
    extensions.push(EditorState.readOnly.of(true))
  }

  return EditorState.create({ doc: content, extensions })
}

function applyDiagnostics(diagnosticsList: typeof props.diagnostics) {
  if (!view) return
  if (!diagnosticsList || diagnosticsList.length === 0) {
    view.dispatch(setDiagnostics(view.state, []))
    return
  }

  const cmDiagnostics = diagnosticsList.map((d) => {
    const line = view!.state.doc.line(Math.min(d.line, view!.state.doc.lines))
    const from = line.from + Math.max(0, Math.min(d.column - 1, line.length))
    const to = Math.min(from + 1, line.to)
    return {
      from,
      to,
      severity: d.severity as 'error' | 'warning',
      message: d.message,
    }
  })

  view.dispatch(setDiagnostics(view.state, cmDiagnostics))
}

onMounted(() => {
  if (!editorRef.value) return
  view = new EditorView({
    state: createState(props.modelValue),
    parent: editorRef.value,
  })
  // Apply initial diagnostics if provided
  if (props.diagnostics?.length) {
    applyDiagnostics(props.diagnostics)
  }
})

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})

// When file changes externally (switching files, revert), replace content
watch(() => props.modelValue, (val) => {
  if (!view) return
  const current = view.state.doc.toString()
  if (val !== current) {
    view.dispatch({
      changes: { from: 0, to: view.state.doc.length, insert: val },
    })
  }
})

// When language changes, recreate the editor state
watch(() => props.language, () => {
  if (!view) return
  const content = view.state.doc.toString()
  view.setState(createState(content))
})

// When the dashboard theme flips, rebuild the editor so the correct
// CodeMirror theme (with/without `cm-dark` baseline) is applied.
watch(isDark, () => {
  if (!view) return
  const content = view.state.doc.toString()
  view.setState(createState(content))
})

// When diagnostics change, update error markers
watch(() => props.diagnostics, (diagnosticsList) => {
  applyDiagnostics(diagnosticsList)
}, { deep: true })
</script>

<template>
  <div ref="editorRef" class="size-full" />
</template>
