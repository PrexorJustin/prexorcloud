import createClient, { type Middleware } from 'openapi-fetch'
import type { paths, components } from './types.d.ts'

export type { paths, components, Middleware }

/** Shorthand to extract a named schema from the OpenAPI components. */
export type Schema<T extends keyof components['schemas']> = components['schemas'][T]

export type ApiClient = ReturnType<typeof createApiClient>

export interface ApiClientOptions {
  baseUrl: string
  getToken: () => string | null
}

/**
 * Creates a typed PrexorCloud API client.
 * Auth header is injected via middleware so every request is authenticated.
 */
export function createApiClient({ baseUrl, getToken }: ApiClientOptions) {
  const client = createClient<paths>({ baseUrl })

  const authMiddleware: Middleware = {
    async onRequest({ request }) {
      const token = getToken()
      if (token) {
        request.headers.set('Authorization', `Bearer ${token}`)
      }
      return request
    },
  }

  client.use(authMiddleware)
  return client
}
