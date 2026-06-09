import * as vscode from 'vscode'
import type { ControllerClient } from './client'
import type { Session } from './session'
import { errorMessage } from './errors'

class GroupNode {
  readonly kind = 'group' as const
  constructor(
    readonly name: string,
    readonly instances: InstanceNode[],
  ) {}
}

export class InstanceNode {
  readonly kind = 'instance' as const
  constructor(
    readonly id: string,
    readonly group: string,
    readonly node: string,
    readonly state: string,
    readonly playerCount: number,
  ) {}
}

class MessageNode {
  readonly kind = 'message' as const
  constructor(readonly text: string) {}
}

type Node = GroupNode | InstanceNode | MessageNode

/** Tree of groups → instances, sourced from `GET /api/v1/services`. */
export class InstancesProvider implements vscode.TreeDataProvider<Node> {
  private readonly _onDidChangeTreeData = new vscode.EventEmitter<void>()
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event

  constructor(
    private readonly client: ControllerClient,
    private readonly session: Session,
  ) {}

  refresh(): void {
    this._onDidChangeTreeData.fire()
  }

  getTreeItem(node: Node): vscode.TreeItem {
    if (node.kind === 'message') {
      return new vscode.TreeItem(node.text, vscode.TreeItemCollapsibleState.None)
    }
    if (node.kind === 'group') {
      const item = new vscode.TreeItem(node.name, vscode.TreeItemCollapsibleState.Expanded)
      item.iconPath = new vscode.ThemeIcon('server-environment')
      item.description = `${node.instances.length} instance${node.instances.length === 1 ? '' : 's'}`
      item.contextValue = 'prexorcloud.group'
      return item
    }
    const item = new vscode.TreeItem(node.id, vscode.TreeItemCollapsibleState.None)
    item.description = `${node.state} · ${node.node}`
    item.tooltip = new vscode.MarkdownString(
      [
        `**${node.id}**`,
        '',
        `- Group: \`${node.group}\``,
        `- Node: \`${node.node}\``,
        `- State: \`${node.state}\``,
        `- Players: \`${node.playerCount}\``,
      ].join('\n'),
    )
    item.iconPath = new vscode.ThemeIcon(stateIcon(node.state))
    item.contextValue = 'prexorcloud.instance'
    item.command = { command: 'prexorcloud.tailLogs', title: 'Tail Instance Logs', arguments: [node] }
    return item
  }

  async getChildren(node?: Node): Promise<Node[]> {
    if (node) {
      return node.kind === 'group' ? node.instances : []
    }
    if (!this.session.isConnected) {
      return [new MessageNode('Not connected — run "PrexorCloud: Connect to Controller"')]
    }
    try {
      const { data, response } = await this.client.api.GET('/api/v1/services')
      if (!data) {
        return [new MessageNode(`Failed to load instances (HTTP ${response.status})`)]
      }
      const groups = new Map<string, InstanceNode[]>()
      for (const i of data.data ?? []) {
        const instance = new InstanceNode(i.id, i.group, i.node, i.state, i.playerCount ?? 0)
        const bucket = groups.get(i.group) ?? []
        bucket.push(instance)
        groups.set(i.group, bucket)
      }
      if (groups.size === 0) return [new MessageNode('No instances running')]
      return [...groups.entries()]
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([name, list]) => new GroupNode(name, list.sort((a, b) => a.id.localeCompare(b.id))))
    } catch (err) {
      return [new MessageNode(`Failed to load instances: ${errorMessage(err)}`)]
    }
  }
}

function stateIcon(state: string): string {
  switch (state.toUpperCase()) {
    case 'RUNNING':
      return 'vm-running'
    case 'STARTING':
    case 'SCHEDULED':
      return 'loading~spin'
    case 'STOPPING':
      return 'vm-outline'
    case 'CRASHED':
      return 'error'
    default:
      return 'vm'
  }
}
