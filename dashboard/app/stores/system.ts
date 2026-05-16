import { defineStore } from "pinia"
import { ref } from "vue"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

export interface SystemHealth {
  status: "UP" | "DOWN" | "DEGRADED"
  components?: Array<{ id: string; status: "UP" | "DOWN" | "DEGRADED"; message?: string }>
}

export interface SystemVersion {
  version: string
  commit?: string
  builtAt?: string
  goVersion?: string
  javaVersion?: string
}

export interface SystemDiagnosticItem {
  id: string
  severity: "info" | "warning" | "error"
  message: string
  fix?: string
}

export interface RedisKeyspace { keys: number; expires: number; avgTtl: number }
export interface RedisSchemaEntry { prefix: string; count: number; sampleKeys?: string[] }

type LooseClient = {
  GET: (path: string, init?: unknown) => Promise<{ data: unknown }>
}

export const useSystemStore = defineStore("system", () => {
  const health = ref<SystemHealth | null>(null)
  const version = ref<SystemVersion | null>(null)
  const diagnostics = ref<SystemDiagnosticItem[]>([])
  const keyspace = ref<RedisKeyspace | null>(null)
  const redisSchema = ref<RedisSchemaEntry[]>([])
  const settings = ref<Record<string, unknown>>({})
  const loading = ref(false)

  function loose(): LooseClient {
    return useApiClient() as unknown as LooseClient
  }

  async function fetchAll() {
    loading.value = true
    try {
      const [h, v, d, k, s, st] = await Promise.allSettled([
        loose().GET('/api/v1/system/health'),
        loose().GET('/api/v1/system/version'),
        loose().GET('/api/v1/system/diagnostics'),
        loose().GET('/api/v1/system/redis/keyspace'),
        loose().GET('/api/v1/system/redis/schema'),
        loose().GET('/api/v1/system/settings'),
      ])
      if (h.status === 'fulfilled') health.value = h.value.data as SystemHealth
      if (v.status === 'fulfilled') version.value = v.value.data as SystemVersion
      if (d.status === 'fulfilled') diagnostics.value = ((d.value.data as { items?: SystemDiagnosticItem[] }).items ?? []) as SystemDiagnosticItem[]
      if (k.status === 'fulfilled') keyspace.value = k.value.data as RedisKeyspace
      if (s.status === 'fulfilled') redisSchema.value = ((s.value.data as { entries?: RedisSchemaEntry[] }).entries ?? []) as RedisSchemaEntry[]
      if (st.status === 'fulfilled') settings.value = st.value.data as Record<string, unknown>
    } catch {
      toast.error(t("store.system.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loading.value = false
    }
  }

  return { health, version, diagnostics, keyspace, redisSchema, settings, loading, fetchAll }
})
