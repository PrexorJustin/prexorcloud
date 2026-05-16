import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { ref, nextTick } from 'vue'

import { toast } from 'vue-sonner'
import { useTemplateMeta } from '../useTemplateMeta'
import { useTemplatesStore } from '~/stores/templates'

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

function stubStore() {
  const store = useTemplatesStore()
  const fetchInheritance = vi.fn()
  const fetchVariables = vi.fn()
  const saveVariables = vi.fn()
  const scanVariables = vi.fn()
  const searchFiles = vi.fn()
  Object.assign(store, { fetchInheritance, fetchVariables, saveVariables, scanVariables, searchFiles })
  return { store, fetchInheritance, fetchVariables, saveVariables, scanVariables, searchFiles }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  vi.mocked(toast.success).mockReset()
  vi.mocked(toast.error).mockReset()
  vi.mocked(toast.info).mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useTemplateMeta', () => {
  it('starts with empty inheritance / variables / search', () => {
    stubStore()
    const tpl = ref(null)
    const m = useTemplateMeta('paper', tpl)
    expect(m.inheritanceChain.value).toEqual([])
    expect(m.templateVariables.value).toEqual([])
    expect(m.searchResults.value).toEqual([])
    expect(m.showSearch.value).toBe(false)
    expect(m.searchQuery.value).toBe('')
  })

  it('fetches inheritance + variables when the template ref turns non-null', async () => {
    const { fetchInheritance, fetchVariables } = stubStore()
    fetchInheritance.mockResolvedValue([{ name: 'base' }, { name: 'paper' }])
    fetchVariables.mockResolvedValue([{ key: 'PORT', value: '25565', description: '' }])

    const tpl = ref<{ name: string } | null>(null)
    const m = useTemplateMeta('paper', tpl as never)
    tpl.value = { name: 'paper' }

    await nextTick()
    await vi.advanceTimersByTimeAsync(0)
    await Promise.resolve()
    await Promise.resolve()

    expect(fetchInheritance).toHaveBeenCalledWith('paper')
    expect(fetchVariables).toHaveBeenCalledWith('paper')
    expect(m.inheritanceChain.value).toEqual([
      { name: 'base', active: false, link: '/templates/base' },
      { name: 'paper', active: true, link: '/templates/paper' },
    ])
    expect(m.templateVariables.value).toHaveLength(1)
  })

  it('swallows inheritance failure independently from variables success', async () => {
    const { fetchInheritance, fetchVariables } = stubStore()
    fetchInheritance.mockRejectedValue(new Error('boom'))
    fetchVariables.mockResolvedValue([{ key: 'PORT', value: '', description: '' }])

    const tpl = ref<{ name: string } | null>({ name: 'paper' })
    const m = useTemplateMeta('paper', tpl as never)

    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()

    expect(m.inheritanceChain.value).toEqual([])
    expect(m.templateVariables.value).toHaveLength(1)
  })

  it('saveTemplateVariables forwards the array and toggles variablesSaving', async () => {
    const { saveVariables } = stubStore()
    saveVariables.mockResolvedValue(undefined)
    const tpl = ref<{ name: string } | null>(null)
    const m = useTemplateMeta('paper', tpl as never)
    m.templateVariables.value = [{ key: 'PORT', value: '25565', description: '' }]

    const promise = m.saveTemplateVariables()
    expect(m.variablesSaving.value).toBe(true)
    await promise
    expect(m.variablesSaving.value).toBe(false)
    expect(saveVariables).toHaveBeenCalledWith('paper', [{ key: 'PORT', value: '25565', description: '' }])
  })

  it('saveTemplateVariables toasts on failure but still clears variablesSaving', async () => {
    const { saveVariables } = stubStore()
    saveVariables.mockRejectedValue(new Error('boom'))
    const tpl = ref<{ name: string } | null>(null)
    const m = useTemplateMeta('paper', tpl as never)
    await m.saveTemplateVariables()
    expect(m.variablesSaving.value).toBe(false)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('scanForVariables appends only keys not already present', async () => {
    const { scanVariables } = stubStore()
    scanVariables.mockResolvedValue(['PORT', 'MAX_PLAYERS', 'DIFFICULTY'])
    const tpl = ref<{ name: string } | null>(null)
    const m = useTemplateMeta('paper', tpl as never)
    m.templateVariables.value = [{ key: 'PORT', value: '25565', description: '' }]
    await m.scanForVariables()
    expect(m.templateVariables.value.map((v) => v.key)).toEqual(['PORT', 'MAX_PLAYERS', 'DIFFICULTY'])
    expect(toast.success).toHaveBeenCalledWith('Found 2 new variables')
  })

  it('scanForVariables singularises "variable" when only one new key is found', async () => {
    const { scanVariables } = stubStore()
    scanVariables.mockResolvedValue(['ONE_KEY'])
    const tpl = ref<{ name: string } | null>(null)
    const m = useTemplateMeta('paper', tpl as never)
    await m.scanForVariables()
    expect(toast.success).toHaveBeenCalledWith('Found 1 new variable')
  })

  it('scanForVariables info-toasts when no new keys turn up', async () => {
    const { scanVariables } = stubStore()
    scanVariables.mockResolvedValue(['PORT'])
    const tpl = ref<{ name: string } | null>(null)
    const m = useTemplateMeta('paper', tpl as never)
    m.templateVariables.value = [{ key: 'PORT', value: '', description: '' }]
    await m.scanForVariables()
    expect(toast.info).toHaveBeenCalledWith('No new variables found')
  })

  it('scanForVariables toasts on failure and clears scanningVariables', async () => {
    const { scanVariables } = stubStore()
    scanVariables.mockRejectedValue(new Error('boom'))
    const tpl = ref<{ name: string } | null>(null)
    const m = useTemplateMeta('paper', tpl as never)
    await m.scanForVariables()
    expect(m.scanningVariables.value).toBe(false)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('onSearch empties results immediately on a blank query', async () => {
    stubStore()
    const tpl = ref<{ name: string } | null>(null)
    const m = useTemplateMeta('paper', tpl as never)
    m.searchResults.value = [{ path: 'a', line: 1, match: '' }] as never
    await m.onSearch('   ')
    expect(m.searchResults.value).toEqual([])
  })

  it('onSearch debounces by 300ms before calling searchFiles', async () => {
    const { searchFiles } = stubStore()
    searchFiles.mockResolvedValue([{ path: 'config.yml', line: 3, match: 'port' }])

    const tpl = ref<{ name: string } | null>(null)
    const m = useTemplateMeta('paper', tpl as never)
    void m.onSearch('port')

    await vi.advanceTimersByTimeAsync(200)
    expect(searchFiles).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(150)
    expect(searchFiles).toHaveBeenCalledWith('paper', 'port')
  })

  it('a second onSearch within the debounce window cancels the first', async () => {
    const { searchFiles } = stubStore()
    searchFiles.mockResolvedValue([])

    const tpl = ref<{ name: string } | null>(null)
    const m = useTemplateMeta('paper', tpl as never)
    void m.onSearch('first')
    await vi.advanceTimersByTimeAsync(150)
    void m.onSearch('second')
    await vi.advanceTimersByTimeAsync(150)
    expect(searchFiles).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(200)
    expect(searchFiles).toHaveBeenCalledTimes(1)
    expect(searchFiles).toHaveBeenCalledWith('paper', 'second')
  })
})
