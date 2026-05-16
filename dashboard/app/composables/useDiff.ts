import { diffLines } from 'diff'

export interface DiffLine {
  type: 'added' | 'removed' | 'unchanged'
  content: string
  oldLineNumber: number | null
  newLineNumber: number | null
}

export interface DiffResult {
  lines: DiffLine[]
  additions: number
  deletions: number
  hasChanges: boolean
}

export function useDiff(original: Ref<string>, modified: Ref<string>): ComputedRef<DiffResult> {
  return computed(() => {
    const changes = diffLines(original.value, modified.value)
    const lines: DiffLine[] = []
    let oldLine = 1
    let newLine = 1
    let additions = 0
    let deletions = 0

    for (const change of changes) {
      // diffLines returns values with trailing newlines; split into individual lines
      const content = change.value
      const lineTexts = content.endsWith('\n')
        ? content.slice(0, -1).split('\n')
        : content.split('\n')

      for (const text of lineTexts) {
        if (change.added) {
          lines.push({ type: 'added', content: text, oldLineNumber: null, newLineNumber: newLine })
          newLine++
          additions++
        } else if (change.removed) {
          lines.push({ type: 'removed', content: text, oldLineNumber: oldLine, newLineNumber: null })
          oldLine++
          deletions++
        } else {
          lines.push({ type: 'unchanged', content: text, oldLineNumber: oldLine, newLineNumber: newLine })
          oldLine++
          newLine++
        }
      }
    }

    return {
      lines,
      additions,
      deletions,
      hasChanges: additions > 0 || deletions > 0,
    }
  })
}
