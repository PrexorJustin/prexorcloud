import * as vscode from 'vscode'
import type { ControllerClient } from './client'
import type { Session } from './session'
import type { TemplateFileSystemProvider } from './templateFs'
import { templateUri } from './templateFs'
import { errorMessage } from './errors'

class TemplateNode {
  readonly kind = 'template' as const
  constructor(readonly name: string) {}
}

class DirNode {
  readonly kind = 'dir' as const
  constructor(
    readonly template: string,
    readonly path: string,
    readonly name: string,
  ) {}
}

class FileNode {
  readonly kind = 'file' as const
  constructor(
    readonly template: string,
    readonly path: string,
    readonly name: string,
    readonly size: number,
  ) {}
}

class MessageNode {
  readonly kind = 'message' as const
  constructor(readonly text: string) {}
}

type Node = TemplateNode | DirNode | FileNode | MessageNode

/** Tree of templates → directories → files; files open via the `prexorcloud-template:` file system. */
export class TemplatesProvider implements vscode.TreeDataProvider<Node> {
  private readonly _onDidChangeTreeData = new vscode.EventEmitter<void>()
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event

  constructor(
    private readonly client: ControllerClient,
    private readonly session: Session,
    private readonly fs: TemplateFileSystemProvider,
  ) {}

  refresh(): void {
    this._onDidChangeTreeData.fire()
  }

  getTreeItem(node: Node): vscode.TreeItem {
    if (node.kind === 'message') {
      return new vscode.TreeItem(node.text, vscode.TreeItemCollapsibleState.None)
    }
    if (node.kind === 'template') {
      const item = new vscode.TreeItem(node.name, vscode.TreeItemCollapsibleState.Collapsed)
      item.iconPath = new vscode.ThemeIcon('package')
      item.contextValue = 'prexorcloud.template'
      return item
    }
    if (node.kind === 'dir') {
      const item = new vscode.TreeItem(node.name, vscode.TreeItemCollapsibleState.Collapsed)
      item.iconPath = vscode.ThemeIcon.Folder
      item.contextValue = 'prexorcloud.templateDir'
      return item
    }
    const item = new vscode.TreeItem(node.name, vscode.TreeItemCollapsibleState.None)
    item.iconPath = vscode.ThemeIcon.File
    item.resourceUri = templateUri(node.template, node.path)
    item.contextValue = 'prexorcloud.templateFile'
    item.command = {
      command: 'vscode.open',
      title: 'Open Template File',
      arguments: [item.resourceUri],
    }
    return item
  }

  async getChildren(node?: Node): Promise<Node[]> {
    if (node?.kind === 'message' || node?.kind === 'file') return []
    if (!this.session.isConnected) {
      return [new MessageNode('Not connected — run "PrexorCloud: Connect to Controller"')]
    }
    try {
      if (!node) {
        const { data, response } = await this.client.api.GET('/api/v1/templates')
        if (!data) return [new MessageNode(`Failed to load templates (HTTP ${response.status})`)]
        const templates = (data.data ?? []).map((t) => new TemplateNode(t.name))
        return templates.length ? templates.sort(byName) : [new MessageNode('No templates')]
      }
      const template = node.kind === 'template' ? node.name : node.template
      const dir = node.kind === 'dir' ? node.path : ''
      const { data, response } = await this.client.api.GET('/api/v1/templates/{name}/files', {
        params: { path: { name: template }, query: { path: dir || undefined } },
      })
      if (!data) return [new MessageNode(`Failed to load files (HTTP ${response.status})`)]
      const children: Node[] = []
      for (const f of data.data ?? []) {
        const path = dir ? `${dir}/${f.name}` : f.name
        if (f.isDirectory) {
          this.fs.cache(templateUri(template, path), vscode.FileType.Directory, 0)
          children.push(new DirNode(template, path, f.name))
        } else {
          this.fs.cache(templateUri(template, path), vscode.FileType.File, f.size ?? 0)
          children.push(new FileNode(template, path, f.name, f.size ?? 0))
        }
      }
      if (children.length === 0) return [new MessageNode('Empty')]
      return children.sort(byKindThenName)
    } catch (err) {
      return [new MessageNode(`Failed to load templates: ${errorMessage(err)}`)]
    }
  }
}

function byName(a: { name: string }, b: { name: string }): number {
  return a.name.localeCompare(b.name)
}

function byKindThenName(a: Node, b: Node): number {
  const rank = (n: Node) => (n.kind === 'dir' ? 0 : 1)
  const diff = rank(a) - rank(b)
  if (diff !== 0) return diff
  const an = 'name' in a ? a.name : ''
  const bn = 'name' in b ? b.name : ''
  return an.localeCompare(bn)
}
