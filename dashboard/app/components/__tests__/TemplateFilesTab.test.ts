import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TemplateFilesTab from '../templates/TemplateFilesTab.vue'

const TreeStub = {
  name: 'TemplateFileTreePanel',
  props: ['tree', 'treeLoading', 'openFilePath', 'draggedPath', 'dropTargetPath', 'renamingPath', 'renameValue', 'showNewInput', 'newFileTargetPath', 'newItemName', 'showSearch', 'searchResults', 'searchLoading', 'searchQuery', 'getChangeType'],
  emits: ['upload', 'search-select'],
  template: '<div class="tree-stub" />',
}
const EditorStub = {
  name: 'TemplateFileEditorPanel',
  props: ['openFile', 'binaryFile', 'extracting', 'editorLanguage', 'validationErrors', 'fileIsModified', 'changeTypeStyles', 'getChangeType', 'getFileIcon'],
  emits: ['update:openFileContent'],
  template: '<div class="editor-stub" />',
}
const StagedStub = {
  name: 'TemplateStagedChangesPanel',
  props: ['changes', 'changeTypeStyles', 'saving'],
  emits: ['unstage', 'save'],
  template: '<div class="staged-stub" />',
}

function editor(overrides: Record<string, unknown> = {}) {
  return {
    tree: ref([{ name: 'a' }]),
    treeLoading: ref(false),
    openFile: ref<{ path: string; content: string } | null>({ path: 'config.yml', content: 'x' }),
    draggedNode: ref(null),
    dropTargetPath: ref(null),
    renamingPath: ref(null),
    renameValue: ref(''),
    showNewInput: ref(false),
    newFileTargetPath: ref(null),
    newItemName: ref(''),
    binaryFile: ref(null),
    extracting: ref(false),
    editorLanguage: ref('yaml'),
    fileIsModified: ref(false),
    hasChanges: ref(false),
    stagedChangesList: ref([]),
    saving: ref(false),
    getChangeType: () => undefined,
    getFileIcon: () => 'File',
    loadRoot: () => {},
    startNewFile: () => {}, startNewDir: () => {}, onSearch: () => {},
    cancelNewItem: () => {}, confirmNewItem: () => {}, onFileClick: () => {},
    startRename: () => {}, requestDelete: () => {}, extractZipFile: () => {},
    downloadFile: () => {}, handleDragAction: () => {}, confirmRename: () => {},
    cancelRename: () => {}, onRootDragOver: () => {}, onRootDragLeave: () => {},
    onRootDrop: () => {}, revertCurrentFile: () => {}, unstageChange: () => {},
    ...overrides,
  }
}

function meta() {
  return {
    showSearch: ref(false),
    searchResults: ref([]),
    searchLoading: ref(false),
    searchQuery: ref(''),
    onSearch: () => {},
  }
}

function mount(editorOverrides: Record<string, unknown> = {}) {
  return mountSuspended(TemplateFilesTab, {
    props: {
      editor: editor(editorOverrides) as never,
      meta: meta() as never,
      validationErrors: [],
      changeTypeStyles: {} as never,
    },
    global: { stubs: { TemplateFileTreePanel: TreeStub, TemplateFileEditorPanel: EditorStub, TemplateStagedChangesPanel: StagedStub } },
  })
}

describe('TemplateFilesTab', () => {
  it('renders the tree and editor panels', async () => {
    const wrapper = await mount()
    expect(wrapper.findComponent(TreeStub).exists()).toBe(true)
    expect(wrapper.findComponent(EditorStub).exists()).toBe(true)
  })

  it('forwards the open file path to the tree panel', async () => {
    const wrapper = await mount()
    expect(wrapper.findComponent(TreeStub).props('openFilePath')).toBe('config.yml')
  })

  it('hides the staged-changes panel when there are no changes', async () => {
    const wrapper = await mount()
    expect(wrapper.findComponent(StagedStub).exists()).toBe(false)
  })

  it('shows the staged-changes panel when the editor has changes', async () => {
    const wrapper = await mount({ hasChanges: ref(true) })
    expect(wrapper.findComponent(StagedStub).exists()).toBe(true)
  })

  it('re-emits upload from the tree panel', async () => {
    const wrapper = await mount()
    wrapper.findComponent(TreeStub).vm.$emit('upload')
    expect(wrapper.emitted('upload')).toHaveLength(1)
  })

  it('re-emits searchSelect with the result payload', async () => {
    const wrapper = await mount()
    const result = { path: 'a.yml' }
    wrapper.findComponent(TreeStub).vm.$emit('search-select', result)
    expect(wrapper.emitted('searchSelect')?.[0]).toEqual([result])
  })

  it('re-emits save from the staged-changes panel', async () => {
    const wrapper = await mount({ hasChanges: ref(true) })
    wrapper.findComponent(StagedStub).vm.$emit('save')
    expect(wrapper.emitted('save')).toHaveLength(1)
  })

  it('writes editor content updates back onto the open file', async () => {
    const ed = editor()
    const wrapper = await mountSuspended(TemplateFilesTab, {
      props: { editor: ed as never, meta: meta() as never, validationErrors: [], changeTypeStyles: {} as never },
      global: { stubs: { TemplateFileTreePanel: TreeStub, TemplateFileEditorPanel: EditorStub, TemplateStagedChangesPanel: StagedStub } },
    })
    wrapper.findComponent(EditorStub).vm.$emit('update:openFileContent', 'new content')
    expect((ed.openFile.value as { content: string }).content).toBe('new content')
  })
})
