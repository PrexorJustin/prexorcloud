import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

import { toast } from 'vue-sonner'
import { useTemplatesStore } from '../templates'

const { mockGetAuthToken, mockBusOn, mockBusOff, mockBusConnect, fetchMock } = vi.hoisted(() => ({
  mockGetAuthToken: vi.fn(),
  mockBusOn: vi.fn(),
  mockBusOff: vi.fn(),
  mockBusConnect: vi.fn(),
  fetchMock: vi.fn(),
}))

vi.mock('~/lib/auth-storage', () => ({
  AUTH_TOKEN_KEY: 'auth_token',
  getAuthToken: mockGetAuthToken,
  setAuthToken: vi.fn(),
  clearAuthToken: vi.fn(),
}))

vi.mock('~/composables/useSseEventBus', () => ({
  useSseEventBus: () => ({ on: mockBusOn, off: mockBusOff, connect: mockBusConnect }),
}))

const mockGET = vi.fn()
const mockPOST = vi.fn()
const mockPUT = vi.fn()
const mockPATCH = vi.fn()
const mockDELETE = vi.fn()
vi.mock('~/composables/useApiClient', () => ({
  useApiClient: () => ({ GET: mockGET, POST: mockPOST, PUT: mockPUT, PATCH: mockPATCH, DELETE: mockDELETE }),
}))

vi.mock('vue-sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

vi.stubGlobal('useRuntimeConfig', () => ({
  public: { apiBase: 'http://localhost:8080' },
}))
vi.stubGlobal('fetch', fetchMock)

beforeEach(() => {
  setActivePinia(createPinia())
  mockGET.mockReset()
  mockPOST.mockReset()
  mockPUT.mockReset()
  mockPATCH.mockReset()
  mockDELETE.mockReset()
  fetchMock.mockReset()
  mockGetAuthToken.mockReset()
  mockBusOn.mockReset()
  mockBusOff.mockReset()
  mockBusConnect.mockReset()
  vi.mocked(toast.success).mockReset()
  vi.mocked(toast.error).mockReset()
})

describe('useTemplatesStore', () => {
  it('starts empty', () => {
    const store = useTemplatesStore()
    expect(store.templates).toEqual([])
    expect(store.loading).toBe(false)
  })

  it('fetchTemplates loads the list and tolerates a missing data field', async () => {
    mockGET.mockResolvedValueOnce({ data: { data: [{ name: 'paper-vanilla', platform: 'paper' }] } })
    const store = useTemplatesStore()
    await store.fetchTemplates()
    expect(store.templates).toHaveLength(1)
    expect(mockGET).toHaveBeenCalledWith('/api/v1/templates')

    mockGET.mockResolvedValueOnce({ data: {} })
    await store.fetchTemplates()
    expect(store.templates).toEqual([])
  })

  it('fetchTemplates toasts on failure and clears loading', async () => {
    mockGET.mockRejectedValue(new Error('boom'))
    const store = useTemplatesStore()
    await store.fetchTemplates()
    expect(store.loading).toBe(false)
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it('createTemplate posts the body, refetches, and returns the created record', async () => {
    mockPOST.mockResolvedValue({ data: { name: 'new', platform: 'paper' } })
    mockGET.mockResolvedValue({ data: { data: [] } })
    const store = useTemplatesStore()
    const created = await store.createTemplate({ name: 'new', description: 'd', platform: 'paper' })
    expect((created as { name: string }).name).toBe('new')
    expect(mockPOST).toHaveBeenCalledWith('/api/v1/templates', { body: { name: 'new', description: 'd', platform: 'paper' } })
    expect(toast.success).toHaveBeenCalled()
  })

  it('updateTemplate patches under {name} and refetches', async () => {
    mockPATCH.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { data: [] } })
    const store = useTemplatesStore()
    await store.updateTemplate('paper-vanilla', { description: 'x' })
    expect(mockPATCH).toHaveBeenCalledWith('/api/v1/templates/{name}', {
      params: { path: { name: 'paper-vanilla' } },
      body: { description: 'x' },
    })
  })

  it('deleteTemplate calls DELETE then refetches', async () => {
    mockDELETE.mockResolvedValue(undefined)
    mockGET.mockResolvedValue({ data: { data: [] } })
    const store = useTemplatesStore()
    await store.deleteTemplate('paper-vanilla')
    expect(mockDELETE).toHaveBeenCalledWith('/api/v1/templates/{name}', { params: { path: { name: 'paper-vanilla' } } })
    expect(mockGET).toHaveBeenCalledWith('/api/v1/templates')
  })

  it('fetchVersions returns the array or []', async () => {
    mockGET.mockResolvedValueOnce({ data: [{ hash: 'h1' }] })
    const store = useTemplatesStore()
    const v = await store.fetchVersions('paper-vanilla')
    expect(v).toEqual([{ hash: 'h1' }])

    mockGET.mockResolvedValueOnce({ data: null })
    expect(await store.fetchVersions('paper-vanilla')).toEqual([])
  })

  it('fetchSnapshotFiles passes hash + path through the query', async () => {
    mockGET.mockResolvedValue({ data: [] })
    const store = useTemplatesStore()
    await store.fetchSnapshotFiles('t', 'abc123', 'subdir')
    expect(mockGET).toHaveBeenCalledWith('/api/v1/templates/{name}/files', {
      params: { path: { name: 't' }, query: { path: 'subdir', version: 'abc123' } },
    })
  })

  it('fetchSnapshotFiles maps empty path to undefined in the query', async () => {
    mockGET.mockResolvedValue({ data: [] })
    const store = useTemplatesStore()
    await store.fetchSnapshotFiles('t', 'abc123', '')
    expect(mockGET.mock.calls[0]![1]).toEqual({
      params: { path: { name: 't' }, query: { path: undefined, version: 'abc123' } },
    })
  })

  it('readTextContent (via fetchFileContent / fetchSnapshotFileContent) omits version when absent', async () => {
    mockGET.mockResolvedValue({ data: 'hello' })
    const store = useTemplatesStore()
    const s = await store.fetchFileContent('t', 'README')
    expect(s).toBe('hello')
    expect(mockGET).toHaveBeenLastCalledWith('/api/v1/templates/{name}/files/content', {
      params: { path: { name: 't' }, query: { path: 'README' } },
    })

    await store.fetchSnapshotFileContent('t', 'abc', 'README')
    expect(mockGET).toHaveBeenLastCalledWith('/api/v1/templates/{name}/files/content', {
      params: { path: { name: 't' }, query: { path: 'README', version: 'abc' } },
    })
  })

  it('uploadFile PUTs to the content endpoint', async () => {
    mockPUT.mockResolvedValue({ data: null })
    const store = useTemplatesStore()
    await store.uploadFile('t', 'config.yml', '# new')
    expect(mockPUT).toHaveBeenCalledWith('/api/v1/templates/{name}/files/content', {
      params: { path: { name: 't' }, query: { path: 'config.yml' } },
      body: { content: '# new' },
    })
  })

  it('rollbackToVersion posts the hash and refetches', async () => {
    mockPOST.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { data: [] } })
    const store = useTemplatesStore()
    await store.rollbackToVersion('t', 'h1')
    expect(mockPOST).toHaveBeenCalledWith('/api/v1/templates/{name}/rollback', {
      params: { path: { name: 't' } },
      body: { hash: 'h1' },
    })
  })

  it('deleteVersion / rehash hit the correct paths and toast', async () => {
    mockDELETE.mockResolvedValue(undefined)
    mockPOST.mockResolvedValue({ data: null })
    mockGET.mockResolvedValue({ data: { data: [] } })

    const store = useTemplatesStore()
    await store.deleteVersion('t', 'h1')
    expect(mockDELETE).toHaveBeenCalledWith('/api/v1/templates/{name}/versions/{hash}', {
      params: { path: { name: 't', hash: 'h1' } },
    })

    await store.rehash('t')
    expect(mockPOST).toHaveBeenCalledWith('/api/v1/templates/{name}/rehash', { params: { path: { name: 't' } } })
  })

  it('renameFile posts from/to', async () => {
    mockPOST.mockResolvedValue({ data: null })
    const store = useTemplatesStore()
    await store.renameFile('t', 'a.yml', 'b.yml')
    expect(mockPOST).toHaveBeenCalledWith('/api/v1/templates/{name}/files/rename', {
      params: { path: { name: 't' } },
      body: { from: 'a.yml', to: 'b.yml' },
    })
  })

  it('extractZip returns the result and toasts the file count', async () => {
    mockPOST.mockResolvedValue({ data: { files: 5 } })
    mockGET.mockResolvedValue({ data: { data: [] } })
    const store = useTemplatesStore()
    const r = await store.extractZip('t', 'thing.zip')
    expect((r as { files: number }).files).toBe(5)
    expect(toast.success).toHaveBeenCalled()
  })

  it('fetchVariables / saveVariables / scanVariables / fetchInheritance hit typed paths', async () => {
    mockGET.mockResolvedValueOnce({ data: [{ key: 'PORT' }] })
    mockPUT.mockResolvedValueOnce({ data: null })
    mockGET.mockResolvedValueOnce({ data: ['NEW_KEY'] })
    mockGET.mockResolvedValueOnce({ data: [{ name: 't', parent: null }] })

    const store = useTemplatesStore()
    expect(await store.fetchVariables('t')).toEqual([{ key: 'PORT' }])
    await store.saveVariables('t', [{ key: 'PORT', value: '25565', description: '' }])
    expect(await store.scanVariables('t')).toEqual(['NEW_KEY'])
    expect(await store.fetchInheritance('t')).toEqual([{ name: 't', parent: null }])

    expect(mockPUT).toHaveBeenCalledWith('/api/v1/templates/{name}/variables', expect.objectContaining({
      params: { path: { name: 't' } },
    }))
  })

  it('searchFiles passes through the query terms and maxResults', async () => {
    mockGET.mockResolvedValue({ data: [{ path: 'a' }] })
    const store = useTemplatesStore()
    const r = await store.searchFiles('t', 'port', 10)
    expect(r).toEqual([{ path: 'a' }])
    expect(mockGET).toHaveBeenCalledWith('/api/v1/templates/{name}/search', {
      params: { path: { name: 't' }, query: { q: 'port', maxResults: 10 } },
    })
  })

  it('searchFiles defaults maxResults to 50', async () => {
    mockGET.mockResolvedValue({ data: [] })
    const store = useTemplatesStore()
    await store.searchFiles('t', 'port')
    expect(mockGET.mock.calls[0]![1]).toMatchObject({ params: { query: { maxResults: 50 } } })
  })

  it('downloadFileUrl includes the auth token query when present', () => {
    mockGetAuthToken.mockReturnValue('tok')
    const store = useTemplatesStore()
    expect(store.downloadFileUrl('t', 'path with space')).toBe(
      'http://localhost:8080/api/v1/templates/t/files/download?path=path%20with%20space&token=tok',
    )
  })

  it('downloadFileUrl omits the token query when missing', () => {
    mockGetAuthToken.mockReturnValue(null)
    const store = useTemplatesStore()
    expect(store.downloadFileUrl('t', 'a.yml')).toBe('http://localhost:8080/api/v1/templates/t/files/download?path=a.yml')
  })

  it('exportUrl reads the token without the api base', () => {
    mockGetAuthToken.mockReturnValue('tok')
    const store = useTemplatesStore()
    expect(store.exportUrl('t')).toBe('/api/v1/templates/t/export?token=tok')
    mockGetAuthToken.mockReturnValue(null)
    expect(store.exportUrl('t')).toBe('/api/v1/templates/t/export')
  })

  it('uploadFiles POSTs multipart formdata and refetches on success', async () => {
    mockGetAuthToken.mockReturnValue('tok')
    fetchMock.mockResolvedValueOnce(new Response('{}', { status: 200 }))
    mockGET.mockResolvedValue({ data: { data: [] } })

    const file = new File(['x'], 'thing.yml')
    const list = makeFileList([file])

    const store = useTemplatesStore()
    await store.uploadFiles('t', 'subdir/here', list)

    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('http://localhost:8080/api/v1/templates/t/files/upload?path=subdir%2Fhere&rehash=true')
    expect((init as RequestInit).method).toBe('POST')
    expect(((init as RequestInit).headers as Record<string, string>).Authorization).toBe('Bearer tok')
    expect(mockGET).toHaveBeenCalledWith('/api/v1/templates')
  })

  it('uploadFiles throws on non-2xx', async () => {
    fetchMock.mockResolvedValueOnce(new Response('', { status: 500 }))
    const list = makeFileList([new File(['x'], 'thing.yml')])
    const store = useTemplatesStore()
    await expect(store.uploadFiles('t', '', list)).rejects.toThrow(/Upload failed/)
  })

  function makeFileList(files: File[]): FileList {
    const obj: Record<string | symbol | number, unknown> = {
      length: files.length,
      item: (i: number) => files[i] ?? null,
      [Symbol.iterator]: () => files[Symbol.iterator](),
    }
    files.forEach((f, i) => { obj[i] = f })
    return obj as unknown as FileList
  }

  it('importTemplate fetches /import, returns the body, and refetches', async () => {
    mockGetAuthToken.mockReturnValue('tok')
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ name: 'imported', platform: 'paper' }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    )
    mockGET.mockResolvedValue({ data: { data: [] } })

    const store = useTemplatesStore()
    const fd = new FormData()
    const r = await store.importTemplate(fd)
    expect(r.name).toBe('imported')
    const [url] = fetchMock.mock.calls[0]!
    expect(url).toBe('http://localhost:8080/api/v1/templates/import')
  })

  it('importTemplate throws on non-2xx', async () => {
    fetchMock.mockResolvedValueOnce(new Response('', { status: 400 }))
    const store = useTemplatesStore()
    await expect(store.importTemplate(new FormData())).rejects.toThrow(/Import failed/)
  })

  it('connectSse subscribes via the bus once', () => {
    const store = useTemplatesStore()
    store.connectSse()
    store.connectSse() // idempotent
    expect(mockBusOn).toHaveBeenCalledTimes(1)
    expect(mockBusOn).toHaveBeenCalledWith('TEMPLATE_UPDATED', expect.any(Function))
    expect(mockBusConnect).toHaveBeenCalledTimes(1)
  })

  it('disconnectSse unsubscribes when previously connected', () => {
    const store = useTemplatesStore()
    store.disconnectSse() // no-op when not connected
    expect(mockBusOff).not.toHaveBeenCalled()
    store.connectSse()
    store.disconnectSse()
    expect(mockBusOff).toHaveBeenCalledTimes(1)
  })
})
