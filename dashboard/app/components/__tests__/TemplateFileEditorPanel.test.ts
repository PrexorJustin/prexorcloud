import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TemplateFileEditorPanel from '../templates/TemplateFileEditorPanel.vue'

type ChangeType = 'modified' | 'created' | 'deleted' | 'renamed'

const changeTypeStyles: Record<ChangeType, { label: string; class: string; dot: string }> = {
  modified: { label: 'M', class: 'text-warning', dot: 'bg-warning' },
  created: { label: 'A', class: 'text-success', dot: 'bg-success' },
  deleted: { label: 'D', class: 'text-destructive', dot: 'bg-destructive' },
  renamed: { label: 'R', class: 'text-primary', dot: 'bg-primary' },
}

function openFile(overrides: Record<string, unknown> = {}) {
  return {
    path: 'plugins/config.yml', name: 'config.yml',
    originalContent: 'a: 1', content: 'a: 1', loading: false,
    ...overrides,
  }
}

function props(overrides: Record<string, unknown> = {}) {
  return {
    openFile: null,
    binaryFile: null,
    extracting: false,
    editorLanguage: 'yaml',
    validationErrors: [],
    fileIsModified: false,
    changeTypeStyles,
    getChangeType: () => null,
    getFileIcon: () => 'text-yellow-500',
    ...overrides,
  }
}

const mountOpts = { global: { stubs: { TemplatesCodeEditor: true } } }

describe('TemplateFileEditorPanel', () => {
  it('shows the "No file selected" placeholder when nothing is open', async () => {
    const wrapper = await mountSuspended(TemplateFileEditorPanel, { props: props(), ...mountOpts })
    expect(wrapper.text()).toContain('No file selected')
  })

  it('renders the binary-file view with name, size and a download button', async () => {
    const wrapper = await mountSuspended(TemplateFileEditorPanel, {
      props: props({ binaryFile: { path: 'world.dat', name: 'world.dat', size: 4096 } }),
      ...mountOpts,
    })
    expect(wrapper.text()).toContain('world.dat')
    expect(wrapper.text()).toContain('Binary file')
    const download = wrapper.findAll('button').find(b => b.text().includes('Download'))!
    await download.trigger('click')
    expect(wrapper.emitted('download')?.[0]).toEqual(['world.dat'])
  })

  it('shows an Extract action only for binary .zip files', async () => {
    const plain = await mountSuspended(TemplateFileEditorPanel, {
      props: props({ binaryFile: { path: 'world.dat', name: 'world.dat', size: 1 } }),
      ...mountOpts,
    })
    expect(plain.findAll('button').some(b => b.text().includes('Extract'))).toBe(false)
    const zip = await mountSuspended(TemplateFileEditorPanel, {
      props: props({ binaryFile: { path: 'world.zip', name: 'world.zip', size: 1 } }),
      ...mountOpts,
    })
    await zip.findAll('button').find(b => b.text().includes('Extract'))!.trigger('click')
    expect(zip.emitted('extract')?.[0]).toEqual(['world.zip'])
  })

  it('renders the editor toolbar with the open file path and language', async () => {
    const wrapper = await mountSuspended(TemplateFileEditorPanel, {
      props: props({ openFile: openFile() }),
      ...mountOpts,
    })
    expect(wrapper.text()).toContain('plugins/config.yml')
    expect(wrapper.text().toLowerCase()).toContain('yaml')
  })

  it('shows the unsaved, staged and error badges based on state', async () => {
    const wrapper = await mountSuspended(TemplateFileEditorPanel, {
      props: props({
        openFile: openFile(),
        fileIsModified: true,
        getChangeType: () => 'modified',
        validationErrors: [{ line: 1, column: 1, message: 'bad', severity: 'error' }],
      }),
      ...mountOpts,
    })
    expect(wrapper.text()).toContain('unsaved')
    expect(wrapper.text()).toContain('staged')
    expect(wrapper.text()).toContain('1 error')
  })

  it('shows a loading spinner instead of the editor while the file loads', async () => {
    const wrapper = await mountSuspended(TemplateFileEditorPanel, {
      props: props({ openFile: openFile({ loading: true }) }),
      ...mountOpts,
    })
    expect(wrapper.find('.animate-spin').exists()).toBe(true)
  })

  it('disables Revert unless the file is modified and emits revert when clicked', async () => {
    const clean = await mountSuspended(TemplateFileEditorPanel, {
      props: props({ openFile: openFile() }),
      ...mountOpts,
    })
    expect(clean.findAll('button').find(b => b.text().includes('Revert'))!.attributes('disabled')).toBeDefined()
    const dirty = await mountSuspended(TemplateFileEditorPanel, {
      props: props({ openFile: openFile(), fileIsModified: true }),
      ...mountOpts,
    })
    const revert = dirty.findAll('button').find(b => b.text().includes('Revert'))!
    expect(revert.attributes('disabled')).toBeUndefined()
    await revert.trigger('click')
    expect(dirty.emitted('revert')).toHaveLength(1)
  })

  it('emits download from the toolbar download button', async () => {
    const wrapper = await mountSuspended(TemplateFileEditorPanel, {
      props: props({ openFile: openFile() }),
      ...mountOpts,
    })
    await wrapper.find('button[title="Download file"]').trigger('click')
    expect(wrapper.emitted('download')?.[0]).toEqual(['plugins/config.yml'])
  })
})
