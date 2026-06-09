import { defineStore } from "pinia"
import { ref } from "vue"
import { toast } from "vue-sonner"
import { t } from "~/lib/translate"

// Wire mirrors of the /api/v1/cluster* JSON responses. Not generated from the
// SDK because the cluster endpoints were added after the last SDK snapshot —
// the shapes below match the routes under
// java/cloud-controller/.../controller/rest/route/Cluster*Routes.java verbatim.

export interface ClusterStatus {
  clusterId?: string
  createdAt?: string
  schemaVersion?: number
  memberCount: number
  activeConfigVersion: number
}

export interface ClusterMember {
  nodeId: string
  raftAddr: string
  restAddr: string
  gRPCAddr: string
  label: string
  joinedAt: string
  lastSeen: string
}

export interface ClusterJoinToken {
  jti: string
  label: string
  status: string
  createdBy?: string
  createdAt: string
  expiresAt: string
}

export interface ClusterLease {
  name: string
  holder: string
  grantedAt: string
  ttlMillis: number
  renewedAt: string
}

export interface ClusterConfigVersionMeta {
  version: number
  parentVersion: number
  mutator: string | null
  mutatedAt: string
  reason: string | null
  isActive: boolean
}

export interface ClusterConfigVersionDetail extends ClusterConfigVersionMeta {
  patch: Record<string, unknown>
}

type LooseClient = {
  GET: (path: string) => Promise<{ data: unknown }>
  POST: (path: string, init?: unknown) => Promise<{ data: unknown }>
  DELETE: (path: string) => Promise<{ data: unknown }>
}

/**
 * Cluster control-plane store. Wraps the Raft-backed
 * {@code /api/v1/cluster*} surface — status, members, join tokens,
 * leases — and exposes the mutating actions (eject, leave, issue,
 * revoke, seed-rotate) that the Cluster page surfaces.
 *
 * <p>All mutations are Raft commits via the controller; failures
 * surface as toast errors and re-fetch the affected list so the UI
 * never displays optimistic state that the cluster didn't accept.
 */
export const useClusterStore = defineStore("cluster", () => {
  const status = ref<ClusterStatus | null>(null)
  const members = ref<ClusterMember[]>([])
  const joinTokens = ref<ClusterJoinToken[]>([])
  const leases = ref<ClusterLease[]>([])
  const configVersions = ref<ClusterConfigVersionMeta[]>([])
  const configActiveVersion = ref<number | null>(null)

  const loadingStatus = ref(false)
  const loadingMembers = ref(false)
  const loadingTokens = ref(false)
  const loadingLeases = ref(false)
  const loadingConfigVersions = ref(false)

  function loose(): LooseClient {
    return useApiClient() as unknown as LooseClient
  }

  async function fetchStatus() {
    loadingStatus.value = true
    try {
      const { data } = await loose().GET("/api/v1/cluster")
      status.value = data as ClusterStatus
    } catch {
      toast.error(t("store.cluster.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loadingStatus.value = false
    }
  }

  async function fetchMembers() {
    loadingMembers.value = true
    try {
      const { data } = await loose().GET("/api/v1/cluster/members")
      members.value = (data as { members?: ClusterMember[] }).members ?? []
    } catch {
      toast.error(t("store.cluster.loadFailed"), { description: t("store.common.controllerUnreachable") })
    } finally {
      loadingMembers.value = false
    }
  }

  async function fetchJoinTokens() {
    loadingTokens.value = true
    try {
      const { data } = await loose().GET("/api/v1/cluster/join-tokens")
      joinTokens.value = (data as { tokens?: ClusterJoinToken[] }).tokens ?? []
    } catch {
      toast.error(t("store.cluster.tokensLoadFailed"))
    } finally {
      loadingTokens.value = false
    }
  }

  async function fetchLeases() {
    loadingLeases.value = true
    try {
      const { data } = await loose().GET("/api/v1/cluster/leases")
      leases.value = (data as { leases?: ClusterLease[] }).leases ?? []
    } catch {
      // Non-critical panel — degrade silently to an empty list rather than
      // error-toast (e.g. a follower returning a transient read error).
      leases.value = []
    } finally {
      loadingLeases.value = false
    }
  }

  async function ejectMember(nodeId: string, reason?: string) {
    try {
      const qs = reason ? `?reason=${encodeURIComponent(reason)}` : ""
      await loose().DELETE(`/api/v1/cluster/members/${encodeURIComponent(nodeId)}${qs}`)
      toast.success(t("store.cluster.ejected", { nodeId }))
      await fetchMembers()
    } catch {
      toast.error(t("store.cluster.ejectFailed"))
      throw new Error("cluster-eject")
    }
  }

  async function leaveCluster() {
    try {
      const { data } = await loose().POST("/api/v1/cluster/leave", { body: {} })
      const resp = data as { clusterId?: string; nodeId?: string }
      toast.success(t("store.cluster.leaving", { nodeId: resp.nodeId ?? "?" }), {
        description: t("store.cluster.leavingDesc"),
      })
    } catch {
      toast.error(t("store.cluster.leaveFailed"))
      throw new Error("cluster-leave")
    }
  }

  async function issueJoinToken(opts: { ttlSeconds: number; label?: string; joinAddrs: string[] }): Promise<{ token: string; jti: string; expiresAt: string }> {
    try {
      const { data } = await loose().POST("/api/v1/cluster/join-tokens", { body: opts })
      const resp = data as { token: string; jti: string; expiresAt: string }
      await fetchJoinTokens()
      return resp
    } catch {
      toast.error(t("store.cluster.tokenIssueFailed"))
      throw new Error("cluster-token-issue")
    }
  }

  async function revokeJoinToken(jti: string) {
    try {
      await loose().DELETE(`/api/v1/cluster/join-tokens/${encodeURIComponent(jti)}`)
      toast.success(t("store.cluster.tokenRevoked"))
      await fetchJoinTokens()
    } catch {
      toast.error(t("store.cluster.tokenRevokeFailed"))
      throw new Error("cluster-token-revoke")
    }
  }

  async function rotateSeed() {
    try {
      await loose().POST("/api/v1/cluster/seed/rotate", { body: {} })
      toast.success(t("store.cluster.seedRotated"))
    } catch {
      toast.error(t("store.cluster.seedRotateFailed"))
      throw new Error("cluster-seed-rotate")
    }
  }

  /** Append-only cluster_config version metadata (newest first). */
  async function fetchConfigVersions() {
    loadingConfigVersions.value = true
    try {
      const { data } = await loose().GET("/api/v1/cluster/config/versions")
      const resp = data as { activeVersion?: number; versions?: ClusterConfigVersionMeta[] }
      configActiveVersion.value = resp.activeVersion ?? null
      // Newest version first so the most recent change is at the top of the table.
      configVersions.value = (resp.versions ?? []).slice().sort((a, b) => b.version - a.version)
    } catch {
      toast.error(t("store.cluster.configVersionsLoadFailed"))
      configVersions.value = []
    } finally {
      loadingConfigVersions.value = false
    }
  }

  /**
   * Fetch one version's full content (metadata + masked patch). Returns null on
   * failure so the diff view can degrade rather than throw. Sensitive paths come
   * back masked as "***" unless the caller holds CLUSTER_MANAGE (server-side).
   */
  async function fetchConfigVersion(version: number): Promise<ClusterConfigVersionDetail | null> {
    try {
      const { data } = await loose().GET(`/api/v1/cluster/config/versions/${version}`)
      return data as ClusterConfigVersionDetail
    } catch {
      toast.error(t("store.cluster.configVersionLoadFailed"))
      return null
    }
  }

  async function rollbackConfig(targetVersion: number, reason?: string) {
    try {
      await loose().POST("/api/v1/cluster/config/rollback", {
        body: reason ? { targetVersion, reason } : { targetVersion },
      })
      toast.success(t("store.cluster.rolledBack", { version: targetVersion }))
      await Promise.all([fetchConfigVersions(), fetchStatus()])
    } catch {
      toast.error(t("store.cluster.rollbackFailed"))
      throw new Error("cluster-config-rollback")
    }
  }

  return {
    status,
    members,
    joinTokens,
    leases,
    configVersions,
    configActiveVersion,
    loadingStatus,
    loadingMembers,
    loadingTokens,
    loadingLeases,
    loadingConfigVersions,
    fetchStatus,
    fetchMembers,
    fetchJoinTokens,
    fetchLeases,
    fetchConfigVersions,
    fetchConfigVersion,
    rollbackConfig,
    ejectMember,
    leaveCluster,
    issueJoinToken,
    revokeJoinToken,
    rotateSeed,
  }
})
