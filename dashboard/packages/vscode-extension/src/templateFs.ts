import * as vscode from 'vscode'
import type { ControllerClient } from './client'

export const TEMPLATE_SCHEME = 'prexorcloud-template'

/** Builds a `prexorcloud-template:` URI for a file inside a template. */
export function templateUri(template: string, filePath: string): vscode.Uri {
  const path = filePath ? `/${template}/${filePath}` : `/${template}`
  return vscode.Uri.from({ scheme: TEMPLATE_SCHEME, path })
}

function parse(uri: vscode.Uri): { template: string; path: string } {
  const parts = uri.path.split('/').filter(Boolean)
  return { template: parts[0] ?? '', path: parts.slice(1).join('/') }
}

/**
 * Backs the editor for template files so VS Code's native open/save works
 * inline. Directory metadata is cached as the Templates tree lists folders,
 * so `stat` rarely needs a round-trip; unknown paths default to a file.
 */
export class TemplateFileSystemProvider implements vscode.FileSystemProvider {
  private readonly _onDidChangeFile = new vscode.EventEmitter<vscode.FileChangeEvent[]>()
  readonly onDidChangeFile = this._onDidChangeFile.event

  private readonly typeCache = new Map<string, { type: vscode.FileType; size: number }>()

  constructor(private readonly client: ControllerClient) {}

  /** Called by the Templates tree as it lists a directory, to seed `stat`. */
  cache(uri: vscode.Uri, type: vscode.FileType, size: number): void {
    this.typeCache.set(uri.toString(), { type, size })
  }

  watch(): vscode.Disposable {
    return new vscode.Disposable(() => {})
  }

  stat(uri: vscode.Uri): vscode.FileStat {
    const { template, path } = parse(uri)
    const cached = this.typeCache.get(uri.toString())
    let type = cached?.type
    if (type === undefined) {
      // Scheme root and bare template names are always directories; any
      // deeper path we haven't seen is assumed to be an editable file.
      type = path === '' ? vscode.FileType.Directory : vscode.FileType.File
      if (template === '' && path === '') type = vscode.FileType.Directory
    }
    return { type, ctime: 0, mtime: Date.now(), size: cached?.size ?? 0 }
  }

  async readDirectory(uri: vscode.Uri): Promise<[string, vscode.FileType][]> {
    const { template, path } = parse(uri)
    if (!template) {
      const { data } = await this.client.api.GET('/api/v1/templates')
      const entries: [string, vscode.FileType][] = []
      for (const t of data?.data ?? []) {
        this.cache(templateUri(t.name, ''), vscode.FileType.Directory, 0)
        entries.push([t.name, vscode.FileType.Directory])
      }
      return entries
    }
    const { data, error } = await this.client.api.GET('/api/v1/templates/{name}/files', {
      params: { path: { name: template }, query: { path: path || undefined } },
    })
    if (error || !data) throw vscode.FileSystemError.FileNotFound(uri)
    const entries: [string, vscode.FileType][] = []
    for (const f of data.data ?? []) {
      const type = f.isDirectory ? vscode.FileType.Directory : vscode.FileType.File
      const childPath = path ? `${path}/${f.name}` : f.name
      this.cache(templateUri(template, childPath), type, f.size ?? 0)
      entries.push([f.name, type])
    }
    return entries
  }

  async readFile(uri: vscode.Uri): Promise<Uint8Array> {
    const { template, path } = parse(uri)
    const { data, error, response } = await this.client.api.GET(
      '/api/v1/templates/{name}/files/content',
      { params: { path: { name: template }, query: { path } }, parseAs: 'text' },
    )
    if (error || data === undefined) {
      if (response.status === 415) {
        throw vscode.FileSystemError.NoPermissions('File is binary and cannot be edited as text.')
      }
      throw vscode.FileSystemError.FileNotFound(uri)
    }
    return new TextEncoder().encode(data as string)
  }

  async writeFile(uri: vscode.Uri, content: Uint8Array): Promise<void> {
    const { template, path } = parse(uri)
    const { error, response } = await this.client.api.PUT('/api/v1/templates/{name}/files/content', {
      params: { path: { name: template }, query: { path } },
      body: { content: new TextDecoder().decode(content) },
    })
    if (error || !response.ok) {
      throw vscode.FileSystemError.NoPermissions(`Failed to save (HTTP ${response.status}).`)
    }
    this.cache(uri, vscode.FileType.File, content.byteLength)
    this._onDidChangeFile.fire([{ type: vscode.FileChangeType.Changed, uri }])
  }

  createDirectory(): void {
    throw vscode.FileSystemError.NoPermissions('Creating directories is not supported from the extension yet.')
  }

  delete(): void {
    throw vscode.FileSystemError.NoPermissions('Deleting template files is not supported from the extension yet.')
  }

  rename(): void {
    throw vscode.FileSystemError.NoPermissions('Renaming template files is not supported from the extension yet.')
  }
}
