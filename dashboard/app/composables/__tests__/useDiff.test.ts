import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { useDiff } from '../useDiff'

describe('useDiff', () => {
  it('detects no changes for identical strings', () => {
    const original = ref('line1\nline2\n')
    const modified = ref('line1\nline2\n')
    const result = useDiff(original, modified)
    expect(result.value.hasChanges).toBe(false)
    expect(result.value.additions).toBe(0)
    expect(result.value.deletions).toBe(0)
  })

  it('detects added lines', () => {
    const original = ref('line1\n')
    const modified = ref('line1\nline2\n')
    const result = useDiff(original, modified)
    expect(result.value.hasChanges).toBe(true)
    expect(result.value.additions).toBeGreaterThan(0)
  })

  it('detects removed lines', () => {
    const original = ref('line1\nline2\n')
    const modified = ref('line1\n')
    const result = useDiff(original, modified)
    expect(result.value.hasChanges).toBe(true)
    expect(result.value.deletions).toBeGreaterThan(0)
  })

  it('detects modified lines as add + remove', () => {
    const original = ref('hello\n')
    const modified = ref('world\n')
    const result = useDiff(original, modified)
    expect(result.value.hasChanges).toBe(true)
    expect(result.value.additions).toBeGreaterThan(0)
    expect(result.value.deletions).toBeGreaterThan(0)
  })

  it('assigns correct line numbers to unchanged lines', () => {
    const original = ref('a\nb\nc\n')
    const modified = ref('a\nb\nc\n')
    const result = useDiff(original, modified)
    const unchanged = result.value.lines.filter(l => l.type === 'unchanged')
    for (const line of unchanged) {
      expect(line.oldLineNumber).not.toBeNull()
      expect(line.newLineNumber).not.toBeNull()
    }
  })

  it('assigns null oldLineNumber for added lines', () => {
    const original = ref('a\n')
    const modified = ref('a\nb\n')
    const result = useDiff(original, modified)
    const added = result.value.lines.filter(l => l.type === 'added')
    for (const line of added) {
      expect(line.oldLineNumber).toBeNull()
      expect(line.newLineNumber).not.toBeNull()
    }
  })

  it('assigns null newLineNumber for removed lines', () => {
    const original = ref('a\nb\n')
    const modified = ref('a\n')
    const result = useDiff(original, modified)
    const removed = result.value.lines.filter(l => l.type === 'removed')
    for (const line of removed) {
      expect(line.newLineNumber).toBeNull()
      expect(line.oldLineNumber).not.toBeNull()
    }
  })

  it('recomputes when refs change', () => {
    const original = ref('a\n')
    const modified = ref('a\n')
    const result = useDiff(original, modified)
    expect(result.value.hasChanges).toBe(false)

    modified.value = 'b\n'
    expect(result.value.hasChanges).toBe(true)
  })

  it('handles empty strings', () => {
    const original = ref('')
    const modified = ref('')
    const result = useDiff(original, modified)
    expect(result.value.hasChanges).toBe(false)
    expect(result.value.lines).toHaveLength(0)
  })
})
