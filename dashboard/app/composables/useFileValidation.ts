import YAML from 'yaml'
import { watchDebounced } from '@vueuse/core'

export interface ValidationError {
  line: number
  column: number
  message: string
  severity: 'error' | 'warning'
}

export function useFileValidation(
  content: Ref<string>,
  language: Ref<string>,
  options?: { debounceMs?: number },
) {
  const errors = ref<ValidationError[]>([])
  const debounceMs = options?.debounceMs ?? 500

  function validateJson(text: string): ValidationError[] {
    if (!text.trim()) return []
    try {
      JSON.parse(text)
      return []
    } catch (e) {
      const msg = (e as SyntaxError).message
      // Try to extract line/column from error message like "... at position 42"
      let line = 1
      let column = 1
      const posMatch = msg.match(/position\s+(\d+)/)
      if (posMatch) {
        const pos = Number(posMatch[1])
        const before = text.slice(0, pos)
        const lines = before.split('\n')
        line = lines.length
        column = (lines[lines.length - 1]?.length ?? 0) + 1
      }
      return [{ line, column, message: msg, severity: 'error' }]
    }
  }

  function validateYaml(text: string): ValidationError[] {
    if (!text.trim()) return []
    const doc = YAML.parseDocument(text)
    return doc.errors.map((err) => {
      const pos = err.pos?.[0] ?? 0
      const before = text.slice(0, pos)
      const lines = before.split('\n')
      const line = lines.length
      const column = (lines[lines.length - 1]?.length ?? 0) + 1
      return {
        line,
        column,
        message: err.message,
        severity: 'error' as const,
      }
    })
  }

  function validateXml(text: string): ValidationError[] {
    if (!text.trim()) return []
    if (typeof DOMParser === 'undefined') return []
    const parser = new DOMParser()
    const doc = parser.parseFromString(text, 'text/xml')
    const errorNode = doc.querySelector('parsererror')
    if (!errorNode) return []

    const errorText = errorNode.textContent ?? 'XML parse error'
    // Try to extract line/column from browser error message
    let line = 1
    let column = 1
    const lineMatch = errorText.match(/line\s+(\d+)/i)
    const colMatch = errorText.match(/column\s+(\d+)/i)
    if (lineMatch) line = Number(lineMatch[1])
    if (colMatch) column = Number(colMatch[1])

    return [{ line, column, message: errorText.split('\n')[0] ?? 'XML parse error', severity: 'error' }]
  }

  function validate() {
    const lang = language.value
    const text = content.value

    switch (lang) {
      case 'json':
        errors.value = validateJson(text)
        break
      case 'yaml':
        errors.value = validateYaml(text)
        break
      case 'xml':
        errors.value = validateXml(text)
        break
      default:
        errors.value = []
    }
  }

  watchDebounced(
    [content, language],
    () => validate(),
    { debounce: debounceMs, immediate: true },
  )

  const valid = computed(() => errors.value.length === 0)

  return { valid, errors }
}
