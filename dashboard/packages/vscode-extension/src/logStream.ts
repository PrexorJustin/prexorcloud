import * as vscode from 'vscode'
import type { ControllerClient } from './client'
import type { Session } from './session'

/** Owns one Output channel per tailed instance; reuses an existing tail on repeat requests. */
export class LogStreamManager {
  private readonly streams = new Map<string, ConsoleStream>()

  constructor(
    private readonly client: ControllerClient,
    private readonly session: Session,
  ) {}

  async tail(instanceId: string): Promise<void> {
    let stream = this.streams.get(instanceId)
    if (!stream) {
      stream = new ConsoleStream(instanceId, this.client, this.session)
      this.streams.set(instanceId, stream)
    }
    stream.channel.show(true)
    await stream.start()
  }

  dispose(): void {
    for (const stream of this.streams.values()) stream.dispose()
    this.streams.clear()
  }
}

/**
 * Replays console scrollback, then tails the live SSE stream into an Output
 * channel. The stream is reached with a short-lived ticket — the same flow
 * the dashboard uses — falling back to a token query param.
 */
class ConsoleStream {
  readonly channel: vscode.OutputChannel
  private abort: AbortController | null = null
  private running = false

  constructor(
    private readonly instanceId: string,
    private readonly client: ControllerClient,
    private readonly session: Session,
  ) {
    this.channel = vscode.window.createOutputChannel(`PrexorCloud: ${instanceId}`)
  }

  async start(): Promise<void> {
    if (this.running) return
    this.running = true
    await this.loadHistory()
    void this.streamLive().finally(() => {
      this.running = false
    })
  }

  private async loadHistory(): Promise<void> {
    try {
      const { data } = await this.client.api.GET('/api/v1/services/{id}/console/history', {
        params: { path: { id: this.instanceId }, query: { limit: 2000 } },
      })
      for (const entry of data?.lines ?? []) {
        if (entry.line) this.channel.appendLine(entry.line)
      }
      this.channel.appendLine('── live stream connected ──')
    } catch {
      // Scrollback is best-effort — fall through to the live stream.
    }
  }

  private async streamLive(): Promise<void> {
    const token = this.session.getToken()
    if (!token) return

    let url: string
    try {
      const res = await fetch(this.client.url('/api/v1/events/ticket'), {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
      })
      const { ticket } = (await res.json()) as { ticket: string }
      url = this.client.url(`/api/v1/services/${this.instanceId}/console?ticket=${ticket}`)
    } catch {
      url = this.client.url(`/api/v1/services/${this.instanceId}/console?token=${token}`)
    }

    this.abort = new AbortController()
    let res: Response
    try {
      res = await fetch(url, { headers: { Accept: 'text/event-stream' }, signal: this.abort.signal })
    } catch {
      this.channel.appendLine('── could not open the console stream ──')
      return
    }
    if (!res.ok || !res.body) {
      this.channel.appendLine(`── console stream unavailable (HTTP ${res.status}) ──`)
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let nl: number
        while ((nl = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, nl).replace(/\r$/, '')
          buffer = buffer.slice(nl + 1)
          if (line.startsWith('data:')) {
            this.channel.appendLine(line.slice(5).replace(/^ /, ''))
          }
        }
      }
    } catch {
      // Aborted on dispose, or a network drop — reported below.
    } finally {
      this.channel.appendLine('── stream disconnected ──')
    }
  }

  dispose(): void {
    this.abort?.abort()
    this.channel.dispose()
  }
}
