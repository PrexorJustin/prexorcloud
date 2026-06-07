import { createApiClient, type ApiClient } from '@prexorcloud/api-sdk'
import type { Session } from './session'

/** Lazily builds — and rebuilds when the controller URL changes — a typed controller client. */
export class ControllerClient {
  private client: ApiClient | null = null
  private builtFor = ''

  constructor(private readonly session: Session) {}

  get api(): ApiClient {
    const baseUrl = this.session.controllerUrl
    if (!baseUrl) {
      throw new Error('No controller URL configured — run "PrexorCloud: Connect to Controller".')
    }
    if (!this.client || this.builtFor !== baseUrl) {
      this.client = createApiClient({ baseUrl, getToken: this.session.getToken })
      this.builtFor = baseUrl
    }
    return this.client
  }

  /** Absolute URL for endpoints the typed client can't model (the SSE console stream). */
  url(path: string): string {
    return this.session.controllerUrl + path
  }
}
