import { defineStore } from "pinia"
import { ref } from "vue"
import type { Deployment } from "~/types/api"

type LooseClient = {
  GET: (path: string, init?: unknown) => Promise<{ data: unknown }>
}

export interface AggregateDeployment extends Deployment {
  groupName: string
}

/**
 * Cross-group deployment view. Aggregates `/groups/{name}/deployments` for
 * every known group into a single timeline. Used by /workloads/deployments.
 *
 * Per-group store stays the source of truth on group detail pages; this is a
 * convenience layer for cluster-wide review.
 */
export const useDeploymentsAggregateStore = defineStore("deploymentsAggregate", () => {
  const deployments = ref<AggregateDeployment[]>([])
  const loading = ref(false)

  function loose(): LooseClient {
    return useApiClient() as unknown as LooseClient
  }

  async function fetchAll() {
    const groupsStore = useGroupsStore()
    if (groupsStore.groups.length === 0) await groupsStore.fetchGroups()

    loading.value = true
    try {
      const results = await Promise.allSettled(
        groupsStore.groups.map(async g => {
          const { data } = await loose().GET(`/api/v1/groups/${encodeURIComponent(g.name)}/deployments`)
          const list = ((data as { data?: Deployment[] })?.data ?? []) as Deployment[]
          return list.map(d => ({ ...d, groupName: g.name }))
        }),
      )
      const all: AggregateDeployment[] = []
      for (const r of results) if (r.status === 'fulfilled') all.push(...r.value)
      // Sort newest first
      all.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      deployments.value = all
    } finally {
      loading.value = false
    }
  }

  return { deployments, loading, fetchAll }
})
