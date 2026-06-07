import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ref, nextTick } from 'vue'
import { useFileValidation } from '../useFileValidation'

async function flush(debounce = 0) {
  await nextTick()
  await vi.advanceTimersByTimeAsync(debounce + 1)
}

describe('useFileValidation', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('JSON: empty content is valid', async () => {
    const content = ref('')
    const lang = ref('json')
    const { valid, errors } = useFileValidation(content, lang, { debounceMs: 0 })
    await flush()
    expect(valid.value).toBe(true)
    expect(errors.value).toEqual([])
  })

  it('JSON: well-formed content is valid', async () => {
    const content = ref('{"a":1}')
    const lang = ref('json')
    const { valid } = useFileValidation(content, lang, { debounceMs: 0 })
    await flush()
    expect(valid.value).toBe(true)
  })

  it('JSON: reports parse errors with line and column when position is given', async () => {
    const content = ref('{\n  "a": ,\n}')
    const lang = ref('json')
    const { valid, errors } = useFileValidation(content, lang, { debounceMs: 0 })
    await flush()
    expect(valid.value).toBe(false)
    expect(errors.value).toHaveLength(1)
    const err = errors.value[0]!
    expect(err.severity).toBe('error')
    expect(err.line).toBeGreaterThanOrEqual(1)
    expect(err.column).toBeGreaterThanOrEqual(1)
    expect(err.message).toMatch(/JSON|Unexpected|Expected/i)
  })

  it('YAML: reports parse errors', async () => {
    const content = ref('a: [unterminated')
    const lang = ref('yaml')
    const { valid, errors } = useFileValidation(content, lang, { debounceMs: 0 })
    await flush()
    expect(valid.value).toBe(false)
    expect(errors.value.length).toBeGreaterThan(0)
    expect(errors.value[0]!.severity).toBe('error')
  })

  it('YAML: well-formed content is valid', async () => {
    const content = ref('a: 1\nb: 2\n')
    const lang = ref('yaml')
    const { valid } = useFileValidation(content, lang, { debounceMs: 0 })
    await flush()
    expect(valid.value).toBe(true)
  })

  it('XML: well-formed content is valid', async () => {
    const content = ref('<root><a>1</a></root>')
    const lang = ref('xml')
    const { valid } = useFileValidation(content, lang, { debounceMs: 0 })
    await flush()
    expect(valid.value).toBe(true)
  })

  it('XML: reports parse errors', async () => {
    const content = ref('<root><a></root>')
    const lang = ref('xml')
    const { valid, errors } = useFileValidation(content, lang, { debounceMs: 0 })
    await flush()
    expect(valid.value).toBe(false)
    expect(errors.value.length).toBeGreaterThan(0)
  })

  it('unknown language never reports errors', async () => {
    const content = ref('!!! not parsed !!!')
    const lang = ref('plaintext')
    const { valid, errors } = useFileValidation(content, lang, { debounceMs: 0 })
    await flush()
    expect(valid.value).toBe(true)
    expect(errors.value).toEqual([])
  })

  it('re-validates when content changes', async () => {
    const content = ref('{"ok":1}')
    const lang = ref('json')
    const { valid } = useFileValidation(content, lang, { debounceMs: 0 })
    await flush()
    expect(valid.value).toBe(true)
    content.value = '{not json}'
    await flush()
    expect(valid.value).toBe(false)
  })

  it('re-validates when language changes', async () => {
    const content = ref('a: 1')
    const lang = ref('json')
    const { valid } = useFileValidation(content, lang, { debounceMs: 0 })
    await flush()
    expect(valid.value).toBe(false) // bad JSON
    lang.value = 'yaml'
    await flush()
    expect(valid.value).toBe(true)
  })

  it('honours custom debounce delay', async () => {
    const content = ref('{"ok":1}')
    const lang = ref('json')
    const { valid } = useFileValidation(content, lang, { debounceMs: 500 })
    await flush(500)
    expect(valid.value).toBe(true)

    content.value = '{bad'
    await nextTick()
    // Before the debounce window elapses the previous valid result holds.
    await vi.advanceTimersByTimeAsync(100)
    expect(valid.value).toBe(true)
    await vi.advanceTimersByTimeAsync(500)
    expect(valid.value).toBe(false)
  })
})
