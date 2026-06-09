import * as vscode from 'vscode'

const TOKEN_KEY = 'prexorcloud.token'

/**
 * The active controller connection: URL persisted in workspace settings,
 * bearer token held in SecretStorage and mirrored in memory so the API
 * SDK's synchronous `getToken` callback can read it.
 */
export class Session {
  private token: string | null = null
  private readonly _onDidChange = new vscode.EventEmitter<void>()
  readonly onDidChange = this._onDidChange.event

  constructor(private readonly secrets: vscode.SecretStorage) {}

  /** Loads any persisted token. Call once on activation. */
  async restore(): Promise<void> {
    this.token = (await this.secrets.get(TOKEN_KEY)) ?? null
    this.publishState()
  }

  get controllerUrl(): string {
    const raw = vscode.workspace.getConfiguration('prexorcloud').get<string>('controllerUrl') ?? ''
    return raw.trim().replace(/\/+$/, '')
  }

  get isConnected(): boolean {
    return this.token !== null && this.controllerUrl !== ''
  }

  /** Synchronous accessor handed to the API SDK as its `getToken` callback. */
  getToken = (): string | null => this.token

  async setControllerUrl(url: string): Promise<void> {
    await vscode.workspace
      .getConfiguration('prexorcloud')
      .update('controllerUrl', url.trim().replace(/\/+$/, ''), vscode.ConfigurationTarget.Global)
  }

  async signIn(token: string): Promise<void> {
    this.token = token
    await this.secrets.store(TOKEN_KEY, token)
    this.publishState()
  }

  async signOut(): Promise<void> {
    this.token = null
    await this.secrets.delete(TOKEN_KEY)
    this.publishState()
  }

  private publishState(): void {
    void vscode.commands.executeCommand('setContext', 'prexorcloud.connected', this.isConnected)
    this._onDidChange.fire()
  }

  dispose(): void {
    this._onDidChange.dispose()
  }
}
