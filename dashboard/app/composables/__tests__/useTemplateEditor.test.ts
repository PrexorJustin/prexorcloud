import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { nextTick } from 'vue'

import { toast } from 'vue-sonner'
import { useTemplateEditor } from '../useTemplateEditor'
import { useTemplatesStore } from '~/stores/templates'

const mockGET = vi.fn()
const mockPOST = vi.fn()
const mockDELETE = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: mockPOST, DELETE: mockDELETE, PUT: vi.fn(), PATCH: vi.fn() }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

function seedTreeFiles(files: { name: string; isDirectory: boolean; size?: number }[]) {
  mockGET.mockResolvedValueOnce({ data: files })
}

function stubStore() {
  const store = useTemplatesStore()
  Object.assign(store, {
    fetchFileContent: vi.fn(),
    renameFile: vi.fn(),
    uploadFile: vi.fn(),
    extractZip: vi.fn(),
    downloadFileUrl: vi.fn().mockReturnValue('/download/url'),
  })
  return store
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockGET.mockReset()
  mockPOST.mockReset()
  mockDELETE.mockReset()
  vi.mocked(toast.success).mockReset()
  vi.mocked(toast.error).mockReset()
  vi.mocked(toast.info).mockReset()
  vi.mocked(toast.warning).mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useTemplateEditor', () => {
  it('starts with an empty tree, no open file, and no changes', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    expect(e.tree.value).toEqual([])
    expect(e.openFile.value).toBeNull()
    expect(e.binaryFile.value).toBeNull()
    expect(e.hasChanges.value).toBe(false)
    expect(e.stagedChangesList.value).toEqual([])
    expect(e.editorLanguage.value).toBe('plaintext')
    expect(e.fileIsModified.value).toBe(false)
  })

  it('isTextFile gates by extension list', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    expect(e.isTextFile('config.yml')).toBe(true)
    expect(e.isTextFile('config.json')).toBe(true)
    expect(e.isTextFile('thing.jar')).toBe(false)
    expect(e.isTextFile('no-ext')).toBe(false)
  })

  it('getFileIcon picks a class from extension', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    expect(e.getFileIcon('a.yml')).toBe('text-yellow-500')
    expect(e.getFileIcon('a.json')).toBe('text-green-500')
    expect(e.getFileIcon('a.properties')).toBe('text-blue-400')
    expect(e.getFileIcon('a.jar')).toBe('text-orange-500')
    expect(e.getFileIcon('a.sh')).toBe('text-emerald-400')
    expect(e.getFileIcon('a.unknown')).toBe('text-muted-foreground')
  })

  it('loadRoot fetches the root directory and sorts directories first', async () => {
    stubStore()
    seedTreeFiles([
      { name: 'plugins', isDirectory: true },
      { name: 'config.yml', isDirectory: false, size: 10 },
      { name: 'world', isDirectory: true },
    ])
    const e = useTemplateEditor('paper')
    await e.loadRoot()

    expect(e.tree.value.map(n => n.name)).toEqual(['plugins', 'world', 'config.yml'])
    expect(mockGET).toHaveBeenCalledWith('/api/v1/templates/{name}/files', {
      params: { path: { name: 'paper' }, query: undefined },
    })
  })

  it('loadDirectory passes a {path} query for nested directories', async () => {
    stubStore()
    seedTreeFiles([])
    const e = useTemplateEditor('paper')
    await e.loadDirectory('subdir/here')
    expect(mockGET).toHaveBeenCalledWith('/api/v1/templates/{name}/files', {
      params: { path: { name: 'paper' }, query: { path: 'subdir/here' } },
    })
  })

  it('stageModification / stageCreation / stageDeletion populate the staged map', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    e.stageModification('a.yml', 'new')
    e.stageCreation('b.yml', 'hi')
    e.stageCreation('dir/', '', true)
    e.stageDeletion('c.yml')
    expect(e.hasChanges.value).toBe(true)
    expect(e.getChangeType('a.yml')).toBe('modified')
    expect(e.getChangeType('b.yml')).toBe('created')
    expect(e.getChangeType('dir/')).toBe('created')
    expect(e.getChangeType('c.yml')).toBe('deleted')
  })

  it('stageDeletion of a previously-created file unstages instead of marking deleted', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    e.stageCreation('b.yml', 'hi')
    e.stageDeletion('b.yml')
    expect(e.getChangeType('b.yml')).toBeNull()
    expect(e.hasChanges.value).toBe(false)
  })

  it('unstageChange drops the entry; if the file is open, loadFileContent re-fetches', async () => {
    const store = stubStore()
    ;(store.fetchFileContent as ReturnType<typeof vi.fn>).mockResolvedValue('disk')

    const e = useTemplateEditor('paper')
    e.openFile.value = { path: 'a.yml', name: 'a.yml', originalContent: 'old', content: 'edited', loading: false }
    e.stageModification('a.yml', 'edited')

    e.unstageChange('a.yml')
    await Promise.resolve(); await Promise.resolve()
    expect(e.getChangeType('a.yml')).toBeNull()
    expect(store.fetchFileContent).toHaveBeenCalledWith('paper', 'a.yml')
  })

  it('editorLanguage maps the open file extension', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    e.openFile.value = { path: 'a.yml', name: 'a.yml', originalContent: '', content: '', loading: false }
    expect(e.editorLanguage.value).toBe('yaml')
    e.openFile.value = { path: 'a.kt', name: 'a.kt', originalContent: '', content: '', loading: false }
    expect(e.editorLanguage.value).toBe('kotlin')
    e.openFile.value = { path: 'a.unknown', name: 'a.unknown', originalContent: '', content: '', loading: false }
    expect(e.editorLanguage.value).toBe('plaintext')
  })

  it('fileIsModified flips when content differs from originalContent', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    e.openFile.value = { path: 'a.yml', name: 'a.yml', originalContent: 'x', content: 'x', loading: false }
    expect(e.fileIsModified.value).toBe(false)
    e.openFile.value.content = 'y'
    expect(e.fileIsModified.value).toBe(true)
  })

  it('loadFileContent on a binary file sets binaryFile and clears openFile', async () => {
    stubStore()
    seedTreeFiles([{ name: 'thing.jar', isDirectory: false, size: 999 }])

    const e = useTemplateEditor('paper')
    await e.loadRoot()
    await e.loadFileContent('thing.jar')
    expect(e.openFile.value).toBeNull()
    expect(e.binaryFile.value).toEqual({ path: 'thing.jar', name: 'thing.jar', size: 999 })
  })

  it('loadFileContent fetches text content and hydrates openFile', async () => {
    const store = stubStore()
    ;(store.fetchFileContent as ReturnType<typeof vi.fn>).mockResolvedValue('disk content')
    const e = useTemplateEditor('paper')
    await e.loadFileContent('a.yml')
    expect(e.openFile.value?.content).toBe('disk content')
    expect(e.openFile.value?.originalContent).toBe('disk content')
    expect(e.openFile.value?.loading).toBe(false)
  })

  it('loadFileContent uses staged content when present and refreshes originalContent in the background', async () => {
    const store = stubStore()
    ;(store.fetchFileContent as ReturnType<typeof vi.fn>).mockResolvedValue('disk')
    const e = useTemplateEditor('paper')
    e.stageModification('a.yml', 'staged-edit')
    await e.loadFileContent('a.yml')
    expect(e.openFile.value?.content).toBe('staged-edit')
    await Promise.resolve(); await Promise.resolve()
    expect(e.openFile.value?.originalContent).toBe('disk')
  })

  it('onFileClick on a directory toggles it; on a file loads content', async () => {
    const store = stubStore()
    ;(store.fetchFileContent as ReturnType<typeof vi.fn>).mockResolvedValue('hi')
    const e = useTemplateEditor('paper')
    seedTreeFiles([{ name: 'plugins', isDirectory: true }])
    await e.loadRoot()
    const dir = e.tree.value[0]!
    seedTreeFiles([])
    e.onFileClick(dir)
    // onFileClick fires toggleExpand but doesn't return the promise — drain
    // microtasks until the async load completes.
    for (let i = 0; i < 5; i++) await Promise.resolve()
    expect(dir.expanded).toBe(true)

    e.onFileClick({ name: 'a.yml', path: 'a.yml', isDirectory: false, size: 0, expanded: false, loaded: true })
    for (let i = 0; i < 5; i++) await Promise.resolve()
    expect(e.openFile.value?.path).toBe('a.yml')
  })

  it('flushStage stages the diff or removes a no-op entry', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    e.openFile.value = { path: 'a.yml', name: 'a.yml', originalContent: 'a', content: 'b', loading: false }
    e.flushStage()
    expect(e.getChangeType('a.yml')).toBe('modified')

    e.openFile.value.content = 'a'
    e.flushStage()
    expect(e.getChangeType('a.yml')).toBeNull()
  })

  it('scheduleAutoStage fires flushStage after 3s of inactivity', () => {
    vi.useFakeTimers()
    stubStore()
    const e = useTemplateEditor('paper')
    e.openFile.value = { path: 'a.yml', name: 'a.yml', originalContent: 'a', content: 'b', loading: false }
    e.scheduleAutoStage()
    vi.advanceTimersByTime(2999)
    expect(e.getChangeType('a.yml')).toBeNull()
    vi.advanceTimersByTime(2)
    expect(e.getChangeType('a.yml')).toBe('modified')
  })

  it('revertCurrentFile resets content to the original and drops the staged entry', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    e.openFile.value = { path: 'a.yml', name: 'a.yml', originalContent: 'orig', content: 'edited', loading: false }
    e.stageModification('a.yml', 'edited')
    e.revertCurrentFile()
    expect(e.openFile.value?.content).toBe('orig')
    expect(e.getChangeType('a.yml')).toBeNull()
  })

  it('confirmNewItem stages a creation and refuses duplicates', async () => {
    stubStore()
    seedTreeFiles([{ name: 'config.yml', isDirectory: false }])

    const e = useTemplateEditor('paper')
    await e.loadRoot()
    e.newItemName.value = 'config.yml'
    e.newFileTargetPath.value = ''
    e.showNewInput.value = 'file'
    e.confirmNewItem()
    // duplicate — no new staged change
    expect(e.getChangeType('config.yml')).toBeNull()
    expect(toast.error).toHaveBeenCalledTimes(1)

    e.newItemName.value = 'new.yml'
    e.confirmNewItem()
    expect(e.getChangeType('new.yml')).toBe('created')
    expect(e.openFile.value?.path).toBe('new.yml')
    expect(e.showNewInput.value).toBeNull()
  })

  it('confirmNewItem for a directory stages an isDir=true entry', async () => {
    stubStore()
    seedTreeFiles([])
    const e = useTemplateEditor('paper')
    await e.loadRoot()
    e.newItemName.value = 'newdir'
    e.showNewInput.value = 'dir'
    e.confirmNewItem()
    const staged = [...e.stagedChanges.values()].find(c => c.path === 'newdir')
    expect(staged?.type).toBe('created')
    expect(staged?.isDir).toBe(true)
  })

  it('rename flow: startRename + confirmRename stages a `renamed` change and updates the node', async () => {
    stubStore()
    seedTreeFiles([{ name: 'a.yml', isDirectory: false }])
    const e = useTemplateEditor('paper')
    await e.loadRoot()
    const node = e.tree.value[0]!
    e.startRename(node)
    expect(e.renamingPath.value).toBe('a.yml')
    e.renameValue.value = 'b.yml'
    e.confirmRename(node)
    expect(e.getChangeType('a.yml')).toBe('renamed')
    expect(node.path).toBe('b.yml')
    expect(node.name).toBe('b.yml')
  })

  it('cancelRename clears renamingPath without staging', async () => {
    stubStore()
    seedTreeFiles([{ name: 'a.yml', isDirectory: false }])
    const e = useTemplateEditor('paper')
    await e.loadRoot()
    e.startRename(e.tree.value[0]!)
    e.cancelRename()
    expect(e.renamingPath.value).toBeNull()
  })

  it('delete flow: requestDelete + confirmDelete stages, cancelDelete bails', async () => {
    stubStore()
    seedTreeFiles([{ name: 'a.yml', isDirectory: false }])
    const e = useTemplateEditor('paper')
    await e.loadRoot()
    e.requestDelete('a.yml')
    expect(e.confirmDeletePath.value).toBe('a.yml')
    e.confirmDelete()
    expect(e.getChangeType('a.yml')).toBe('deleted')
    expect(toast.success).toHaveBeenCalledTimes(1)

    e.requestDelete('a.yml')
    e.cancelDelete()
    expect(e.confirmDeletePath.value).toBeNull()
  })

  it('downloadFile opens the URL the store builds in a new tab', async () => {
    const store = stubStore()
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const e = useTemplateEditor('paper')
    e.downloadFile('a.yml')
    expect(store.downloadFileUrl).toHaveBeenCalledWith('paper', 'a.yml')
    expect(open).toHaveBeenCalledWith('/download/url', '_blank')
    open.mockRestore()
  })

  it('extractZipFile delegates to the store, clears panels, and re-loads the tree', async () => {
    const store = stubStore()
    ;(store.extractZip as ReturnType<typeof vi.fn>).mockResolvedValue({ files: 3 })
    seedTreeFiles([]) // for the loadRoot inside extractZipFile

    const e = useTemplateEditor('paper')
    e.binaryFile.value = { path: 'a.zip', name: 'a.zip', size: 100 }
    await e.extractZipFile('a.zip')
    expect(store.extractZip).toHaveBeenCalledWith('paper', 'a.zip')
    expect(e.binaryFile.value).toBeNull()
    expect(e.openFile.value).toBeNull()
    expect(e.extracting.value).toBe(false)
  })

  it('saveAllChanges no-ops when nothing is staged', async () => {
    stubStore()
    const e = useTemplateEditor('paper')
    const ok = await e.saveAllChanges(() => 0)
    expect(ok).toBe(false)
  })

  it('saveAllChanges fans out delete → rename → mkdir → upload → rehash, clears staging, toasts', async () => {
    const store = stubStore()
    ;(store.renameFile as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
    ;(store.uploadFile as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
    mockDELETE.mockResolvedValue(undefined)
    mockPOST.mockResolvedValue({ data: null })
    seedTreeFiles([]) // post-save loadRoot

    const e = useTemplateEditor('paper')
    e.stageDeletion('drop.yml')
    e.stagedChanges.set('was.yml', { type: 'renamed', path: 'was.yml', newPath: 'now.yml' })
    e.stageCreation('newdir', '', true)
    e.stageCreation('new.yml', 'hello')
    e.stageModification('edited.yml', 'changed')

    const ok = await e.saveAllChanges(() => 0)
    expect(ok).toBe(true)
    expect(mockDELETE).toHaveBeenCalledWith(
      '/api/v1/templates/{name}/files',
      { params: { path: { name: 'paper' }, query: { path: 'drop.yml' } } },
    )
    expect(store.renameFile).toHaveBeenCalledWith('paper', 'was.yml', 'now.yml')
    expect(mockPOST).toHaveBeenCalledWith(
      '/api/v1/templates/{name}/files/mkdir',
      { params: { path: { name: 'paper' }, query: { path: 'newdir' } } },
    )
    expect(store.uploadFile).toHaveBeenCalledWith('paper', 'new.yml', 'hello')
    expect(store.uploadFile).toHaveBeenCalledWith('paper', 'edited.yml', 'changed')
    expect(mockPOST).toHaveBeenCalledWith(
      '/api/v1/templates/{name}/rehash',
      { params: { path: { name: 'paper' } } },
    )
    expect(e.stagedChanges.size).toBe(0)
    expect(e.hasChanges.value).toBe(false)
    expect(toast.success).toHaveBeenCalled()
  })

  it('saveAllChanges surfaces extra validation count as a warning toast', async () => {
    const store = stubStore()
    ;(store.uploadFile as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
    mockPOST.mockResolvedValue({ data: null })
    seedTreeFiles([])

    const e = useTemplateEditor('paper')
    e.openFile.value = { path: 'edited.yml', name: 'edited.yml', originalContent: 'a', content: 'b', loading: false }
    e.stageModification('edited.yml', 'b')
    const ok = await e.saveAllChanges(() => 2)
    expect(ok).toBe(true)
    expect(toast.warning).toHaveBeenCalled()
  })

  it('saveAllChanges returns false and toasts on error', async () => {
    const store = stubStore()
    ;(store.uploadFile as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('boom'))
    mockPOST.mockResolvedValue({ data: null })

    const e = useTemplateEditor('paper')
    e.stageModification('a.yml', 'x')
    const ok = await e.saveAllChanges(() => 0)
    expect(ok).toBe(false)
    expect(toast.error).toHaveBeenCalled()
    expect(e.saving.value).toBe(false)
  })

  it('discardAllChanges clears the staging map and resets the open file', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    e.stageModification('a.yml', 'edited')
    e.openFile.value = { path: 'a.yml', name: 'a.yml', originalContent: 'orig', content: 'edited', loading: false }
    mockGET.mockResolvedValue({ data: [] })
    e.discardAllChanges()
    expect(e.stagedChanges.size).toBe(0)
    expect(e.openFile.value?.content).toBe('orig')
    expect(toast.info).toHaveBeenCalled()
  })

  it('handleDragAction `start` records the source and `end` clears it', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    const node = { name: 'a.yml', path: 'a.yml', isDirectory: false, size: 0, expanded: false, loaded: true }
    const dt = { effectAllowed: '', setData: vi.fn() } as unknown as DataTransfer
    e.handleDragAction({ action: 'start', node, e: { dataTransfer: dt } as unknown as DragEvent })
    expect(e.draggedNode.value?.path).toBe('a.yml')
    expect(dt.setData).toHaveBeenCalledWith('text/plain', 'a.yml')

    e.handleDragAction({ action: 'end' })
    expect(e.draggedNode.value).toBeNull()
    expect(e.dropTargetPath.value).toBeNull()
  })

  it('handleDragAction `drop` stages a rename to the new parent', async () => {
    stubStore()
    seedTreeFiles([
      { name: 'a.yml', isDirectory: false },
      { name: 'subdir', isDirectory: true },
    ])
    const e = useTemplateEditor('paper')
    await e.loadRoot()
    const file = e.tree.value.find(n => n.name === 'a.yml')!
    e.draggedNode.value = file
    const ev = { preventDefault: vi.fn() } as unknown as DragEvent
    e.handleDragAction({ action: 'drop', path: 'subdir', e: ev })
    const staged = [...e.stagedChanges.values()].find(c => c.path === 'a.yml')
    expect(staged?.type).toBe('renamed')
    expect(staged?.newPath).toBe('subdir/a.yml')
  })

  it('onRootDragOver only previews dropTarget when the dragged node is nested', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    const dt = { dropEffect: '' } as unknown as DataTransfer
    const preventDefault = vi.fn()

    // Top-level node — root drop is a no-op
    e.draggedNode.value = { name: 'a.yml', path: 'a.yml', isDirectory: false, size: 0, expanded: false, loaded: true }
    e.onRootDragOver({ preventDefault, dataTransfer: dt } as unknown as DragEvent)
    expect(e.dropTargetPath.value).toBeNull()

    // Nested node — root drop is allowed
    e.draggedNode.value = { name: 'a.yml', path: 'subdir/a.yml', isDirectory: false, size: 0, expanded: false, loaded: true }
    e.onRootDragOver({ preventDefault, dataTransfer: dt } as unknown as DragEvent)
    expect(e.dropTargetPath.value).toBe('__root__')
  })

  it('onRootDrop stages a rename to root for a nested node', () => {
    stubStore()
    const e = useTemplateEditor('paper')
    e.draggedNode.value = { name: 'a.yml', path: 'subdir/a.yml', isDirectory: false, size: 0, expanded: false, loaded: true }
    e.onRootDrop({ preventDefault: vi.fn() } as unknown as DragEvent)
    const staged = [...e.stagedChanges.values()].find(c => c.path === 'subdir/a.yml')
    expect(staged?.newPath).toBe('a.yml')
  })
})
