import { defineStore } from "pinia"
import { ref } from "vue"
import type { Deployment } from "~/types/api"

type LooseClient = {
  GET: (path: string, init?: unknown) => Promise<{ data: unknown }>
  POST: (path: string, init?: unknown) => Promise<{ data: unknown }>
}

export type DeploymentAction = "pause" | "resume" | "rollback"

/** Deployment-state → the lifecycle actions available on it (kept pure for the UI + tests). */
export function availableActions(state: string): DeploymentAction[] {
  if (state === "IN_PROGRESS" || state === "PENDING") return ["pause"]
  if (state === "PAUSED") return ["resume", "rollback"]
  if (state === "COMPLETED" || state === "FAILED") return ["rollback"]
  return []
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

  /**
   * Run a lifecycle action (pause / resume / rollback) on a deployment, then refresh the timeline.
   * Throws on failure (the caller shows the error toast); rollback both relabels the deployment and
   * re-deploys the previous good config controller-side.
   */
  async function runAction(groupName: string, revision: number, action: DeploymentAction) {
    const path = `/api/v1/groups/${encodeURIComponent(groupName)}/deployments/${revision}/${action}`
    await loose().POST(path, {})
    await fetchAll()
  }

  return { deployments, loading, fetchAll, runAction }
})
