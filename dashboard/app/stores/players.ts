import { defineStore } from "pinia"
import { ref } from "vue"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

export interface Player {
  id: string
  uuid: string
  username: string
  currentInstance?: string | null
  group?: string
  ping?: number
  connectedAt?: string
}

export interface PlayerJourneyEntry {
  ts: string
  type: "connected" | "transferred" | "disconnected"
  fromInstance?: string | null
  toInstance?: string | null
}

type LooseClient = {
  GET: (path: string, init?: unknown) => Promise<{ data: unknown }>
  POST: (path: string, init?: unknown) => Promise<{ data: unknown }>
}

type PlayerPage = { data?: Player[]; page?: number; pageSize?: number; total?: number }

const PAGE_SIZE = 500
const MAX_PAGES = 20  // safety cap → 10 000 players. Larger clusters need server-side search.

export const usePlayersStore = defineStore("players", () => {
  const players = ref<Player[]>([])
  const loading = ref(false)
  const total = ref(0)
  const truncated = ref(false)

  function loose(): LooseClient {
    return useApiClient() as unknown as LooseClient
  }

  async function fetchPage(page: number): Promise<PlayerPage | null> {
    const { data } = await loose().GET(`/api/v1/players?page=${page}&pageSize=${PAGE_SIZE}`)
    return (data ?? null) as PlayerPage | null
  }

  async function fetchPlayers() {
    loading.value = true
    truncated.value = false
    try {
      const first = await fetchPage(1)
      const collected: Player[] = [...((first?.data ?? []) as Player[])]
      const totalCount = first?.total ?? collected.length
      total.value = totalCount

      const pagesNeeded = Math.ceil(totalCount / PAGE_SIZE)
      const lastPage = Math.min(pagesNeeded, MAX_PAGES)
      for (let p = 2; p <= lastPage; p++) {
        const next = await fetchPage(p)
        collected.push(...((next?.data ?? []) as Player[]))
      }
      if (pagesNeeded > MAX_PAGES) truncated.value = true

      players.value = collected
    } catch {
      toast.error(t("store.players.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loading.value = false
    }
  }

  async function fetchJourney(id: string): Promise<PlayerJourneyEntry[]> {
    try {
      const { data } = await loose().GET(`/api/v1/players/${encodeURIComponent(id)}/journey`)
      return ((data as { data?: PlayerJourneyEntry[] })?.data ?? []) as PlayerJourneyEntry[]
    } catch {
      toast.error(t("store.players.journeyLoadFailed"), { description: t("store.players.journeyLoadFailedDesc") })
      return []
    }
  }

  async function transfer(id: string, instanceId: string) {
    try {
      await loose().POST(`/api/v1/players/${encodeURIComponent(id)}/transfer`, { body: { instanceId } })
      toast.success(t("store.players.transferred", { instanceId }))
      await fetchPlayers()
    } catch {
      toast.error(t("store.players.transferFailed"), { description: t("store.players.transferFailedDesc") })
      throw new Error("transfer-player")
    }
  }

  return { players, loading, total, truncated, fetchPlayers, fetchJourney, transfer }
})
