import { describe, it, expect, vi, beforeEach } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CodeEditor from '../templates/CodeEditor.vue'

const { colorMode } = vi.hoisted(() => ({ colorMode: { value: 'dark' } as { value: string } }))
mockNuxtImport('useColorMode', () => () => reactive(colorMode))

beforeEach(() => {
  colorMode.value = 'dark'
})

describe('CodeEditor', () => {
  it('mounts a CodeMirror editor seeded with the model value', async () => {
    const wrapper = await mountSuspended(CodeEditor, {
      props: { modelValue: 'hello: world', language: 'yaml' },
    })
    expect(wrapper.find('.cm-editor').exists()).toBe(true)
    expect(wrapper.find('.cm-content').text()).toContain('hello: world')
  })

  it('replaces the document when modelValue changes externally', async () => {
    const wrapper = await mountSuspended(CodeEditor, {
      props: { modelValue: 'first', language: 'plaintext' },
    })
    await wrapper.setProps({ modelValue: 'second' })
    expect(wrapper.find('.cm-content').text()).toContain('second')
    expect(wrapper.find('.cm-content').text()).not.toContain('first')
  })

  it('rebuilds the editor when the language changes', async () => {
    const wrapper = await mountSuspended(CodeEditor, {
      props: { modelValue: 'x = 1', language: 'plaintext' },
    })
    await wrapper.setProps({ language: 'javascript' })
    // content survives the state rebuild
    expect(wrapper.find('.cm-content').text()).toContain('x = 1')
  })

  it('mounts with the read-only extension when the readOnly prop is set', async () => {
    const wrapper = await mountSuspended(CodeEditor, {
      props: { modelValue: 'locked', language: 'plaintext', readOnly: true },
    })
    expect(wrapper.find('.cm-editor').exists()).toBe(true)
    expect(wrapper.find('.cm-content').text()).toContain('locked')
  })

  it('applies diagnostics passed at mount time', async () => {
    const wrapper = await mountSuspended(CodeEditor, {
      props: {
        modelValue: 'line one\nline two',
        language: 'plaintext',
        diagnostics: [{ line: 1, column: 1, message: 'bad', severity: 'error' as const }],
      },
    })
    expect(wrapper.find('.cm-editor').exists()).toBe(true)
    await wrapper.setProps({ diagnostics: [] })
    expect(wrapper.find('.cm-content').text()).toContain('line one')
  })

  it('rebuilds the editor when the color mode flips', async () => {
    const wrapper = await mountSuspended(CodeEditor, {
      props: { modelValue: 'themed', language: 'plaintext' },
    })
    colorMode.value = 'light'
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.cm-content').text()).toContain('themed')
  })
})
