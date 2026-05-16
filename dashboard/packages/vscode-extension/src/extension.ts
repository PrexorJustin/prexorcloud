import * as vscode from 'vscode'
import { Session } from './session'
import { ControllerClient } from './client'
import type { InstanceNode } from './instancesProvider';
import { InstancesProvider } from './instancesProvider'
import { TemplatesProvider } from './templatesProvider'
import { TemplateFileSystemProvider, TEMPLATE_SCHEME } from './templateFs'
import { LogStreamManager } from './logStream'
import { errorMessage, describeApiError } from './errors'

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const session = new Session(context.secrets)
  const client = new ControllerClient(session)
  const fs = new TemplateFileSystemProvider(client)
  const instances = new InstancesProvider(client, session)
  const templates = new TemplatesProvider(client, session, fs)
  const logs = new LogStreamManager(client, session)

  await session.restore()

  context.subscriptions.push(
    session,
    logs,
    vscode.workspace.registerFileSystemProvider(TEMPLATE_SCHEME, fs, { isCaseSensitive: true }),
    vscode.window.registerTreeDataProvider('prexorcloud.instances', instances),
    vscode.window.registerTreeDataProvider('prexorcloud.templates', templates),
    session.onDidChange(() => {
      instances.refresh()
      templates.refresh()
    }),
    vscode.commands.registerCommand('prexorcloud.refresh', () => {
      instances.refresh()
      templates.refresh()
    }),
    vscode.commands.registerCommand('prexorcloud.connect', () => connect(session, client)),
    vscode.commands.registerCommand('prexorcloud.disconnect', async () => {
      await session.signOut()
      vscode.window.showInformationMessage('Disconnected from the PrexorCloud controller.')
    }),
    vscode.commands.registerCommand('prexorcloud.tailLogs', async (node?: InstanceNode) => {
      const id = node?.id
      if (!id) {
        vscode.window.showInformationMessage('Select an instance in the PrexorCloud view to tail its logs.')
        return
      }
      try {
        await logs.tail(id)
      } catch (err) {
        vscode.window.showErrorMessage(`Could not tail logs: ${errorMessage(err)}`)
      }
    }),
  )
}

export function deactivate(): void {
  // Subscriptions registered above are disposed by VS Code.
}

async function connect(session: Session, client: ControllerClient): Promise<void> {
  const url = await vscode.window.showInputBox({
    title: 'PrexorCloud — Controller URL',
    prompt: 'Base URL of the controller',
    value: session.controllerUrl || 'http://localhost:8080',
    ignoreFocusOut: true,
  })
  if (!url) return
  await session.setControllerUrl(url)

  const username = await vscode.window.showInputBox({
    title: 'PrexorCloud — Sign In',
    prompt: 'Username',
    ignoreFocusOut: true,
  })
  if (!username) return

  const password = await vscode.window.showInputBox({
    title: 'PrexorCloud — Sign In',
    prompt: 'Password',
    password: true,
    ignoreFocusOut: true,
  })
  if (password === undefined) return

  try {
    const { data, error } = await client.api.POST('/api/v1/auth/login', {
      body: { username, password },
    })
    const token = data?.token
    if (!token) {
      vscode.window.showErrorMessage(`Sign in failed: ${error ? describeApiError(error) : 'invalid credentials'}`)
      return
    }
    await session.signIn(token)
    vscode.window.showInformationMessage(`Connected to ${session.controllerUrl}`)
  } catch (err) {
    vscode.window.showErrorMessage(`Could not reach the controller: ${errorMessage(err)}`)
  }
}
