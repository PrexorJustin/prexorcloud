import { describe, it, expect, vi, beforeEach } from "vitest"
import { setActivePinia, createPinia } from "pinia"

import { toast } from "vue-sonner"
import { useClusterStore } from "../cluster"

const mockGET = vi.fn()
const mockPOST = vi.fn()
const mockDELETE = vi.fn()
vi.mock("~/composables/useApiClient", () => ({
  useApiClient: () => ({ GET: mockGET, POST: mockPOST, DELETE: mockDELETE, PUT: vi.fn(), PATCH: vi.fn() }),
}))

vi.mock("vue-sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}))

beforeEach(() => {
  setActivePinia(createPinia())
  mockGET.mockReset()
  mockPOST.mockReset()
  mockDELETE.mockReset()
  vi.mocked(toast.success).mockReset()
  vi.mocked(toast.error).mockReset()
})

describe("useClusterStore", () => {
  it("starts empty and idle", () => {
    const store = useClusterStore()
    expect(store.status).toBeNull()
    expect(store.members).toEqual([])
    expect(store.joinTokens).toEqual([])
    expect(store.leases).toEqual([])
  })

  it("fetchStatus populates the status snapshot", async () => {
    mockGET.mockResolvedValue({ data: { clusterId: "cid-1", memberCount: 3, activeConfigVersion: 7 } })
    const store = useClusterStore()
    await store.fetchStatus()
    expect(store.status?.clusterId).toBe("cid-1")
    expect(store.status?.memberCount).toBe(3)
  })

  it("fetchMembers unwraps the {members:[]} response shape", async () => {
    mockGET.mockResolvedValue({ data: { members: [{ nodeId: "controller-1", raftAddr: "10.0.0.1:9190" }] } })
    const store = useClusterStore()
    await store.fetchMembers()
    expect(store.members).toHaveLength(1)
    expect(store.members[0]!.nodeId).toBe("controller-1")
  })

  it("fetchLeases degrades to empty on failure (route may not be wired yet)", async () => {
    mockGET.mockRejectedValue(new Error("404"))
    const store = useClusterStore()
    await store.fetchLeases()
    expect(store.leases).toEqual([])
    // /leases isn't a guaranteed-shipped route — we don't want a toast on every page load.
    expect(toast.error).not.toHaveBeenCalled()
  })

  it("ejectMember calls DELETE with the reason in the query string", async () => {
    mockDELETE.mockResolvedValue(undefined)
    mockGET.mockResolvedValue({ data: { members: [] } })
    const store = useClusterStore()
    await store.ejectMember("controller-3", "host destroyed")
    expect(mockDELETE).toHaveBeenCalledWith("/api/v1/cluster/members/controller-3?reason=host%20destroyed")
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it("issueJoinToken returns the wire token and refetches the list", async () => {
    mockPOST.mockResolvedValue({ data: { token: "prexor-jt:v1:abc", jti: "j-1", expiresAt: "2030-01-01" } })
    mockGET.mockResolvedValue({ data: { tokens: [] } })
    const store = useClusterStore()
    const issued = await store.issueJoinToken({ ttlSeconds: 3600, joinAddrs: ["a:9090"] })
    expect(issued.token).toBe("prexor-jt:v1:abc")
    expect(mockPOST).toHaveBeenCalledWith("/api/v1/cluster/join-tokens", {
      body: { ttlSeconds: 3600, joinAddrs: ["a:9090"] },
    })
  })

  it("rotateSeed toasts success on a 200 response", async () => {
    mockPOST.mockResolvedValue({ data: { clusterId: "cid-1", rotatedBy: "alice", rotatedAt: "2030" } })
    const store = useClusterStore()
    await store.rotateSeed()
    expect(mockPOST).toHaveBeenCalledWith("/api/v1/cluster/seed/rotate", { body: {} })
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it("rotateSeed surfaces failures with a typed error", async () => {
    mockPOST.mockRejectedValue(new Error("boom"))
    const store = useClusterStore()
    await expect(store.rotateSeed()).rejects.toThrow("cluster-seed-rotate")
  })

  it("revokeJoinToken DELETEs the jti and refetches", async () => {
    mockDELETE.mockResolvedValue(undefined)
    mockGET.mockResolvedValue({ data: { tokens: [] } })
    const store = useClusterStore()
    await store.revokeJoinToken("jti-1")
    expect(mockDELETE).toHaveBeenCalledWith("/api/v1/cluster/join-tokens/jti-1")
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it("fetchConfigVersions unwraps and sorts versions newest-first", async () => {
    mockGET.mockResolvedValue({
      data: {
        activeVersion: 2,
        versions: [
          { version: 1, parentVersion: 0, mutator: "seed", mutatedAt: "2030", reason: null, isActive: false },
          { version: 3, parentVersion: 2, mutator: "bob", mutatedAt: "2030", reason: "x", isActive: false },
          { version: 2, parentVersion: 1, mutator: "alice", mutatedAt: "2030", reason: null, isActive: true },
        ],
      },
    })
    const store = useClusterStore()
    await store.fetchConfigVersions()
    expect(store.configActiveVersion).toBe(2)
    expect(store.configVersions.map(v => v.version)).toEqual([3, 2, 1])
  })

  it("fetchConfigVersions degrades to empty + error toast on failure", async () => {
    mockGET.mockRejectedValue(new Error("boom"))
    const store = useClusterStore()
    await store.fetchConfigVersions()
    expect(store.configVersions).toEqual([])
    expect(toast.error).toHaveBeenCalledTimes(1)
  })

  it("fetchConfigVersion returns the version detail with its patch", async () => {
    mockGET.mockResolvedValue({ data: { version: 5, parentVersion: 4, patch: { security: { jwtSecret: "***" } } } })
    const store = useClusterStore()
    const detail = await store.fetchConfigVersion(5)
    expect(mockGET).toHaveBeenCalledWith("/api/v1/cluster/config/versions/5")
    expect(detail?.patch).toEqual({ security: { jwtSecret: "***" } })
  })

  it("fetchConfigVersion returns null (not throw) on failure", async () => {
    mockGET.mockRejectedValue(new Error("404"))
    const store = useClusterStore()
    const detail = await store.fetchConfigVersion(9)
    expect(detail).toBeNull()
  })

  it("rollbackConfig POSTs the target version with reason and refetches", async () => {
    mockPOST.mockResolvedValue({ data: { activeVersion: 2 } })
    mockGET.mockResolvedValue({ data: { activeVersion: 2, versions: [] } })
    const store = useClusterStore()
    await store.rollbackConfig(2, "bad patch")
    expect(mockPOST).toHaveBeenCalledWith("/api/v1/cluster/config/rollback", {
      body: { targetVersion: 2, reason: "bad patch" },
    })
    expect(toast.success).toHaveBeenCalledTimes(1)
  })

  it("rollbackConfig omits reason from the body when not provided", async () => {
    mockPOST.mockResolvedValue({ data: { activeVersion: 1 } })
    mockGET.mockResolvedValue({ data: { activeVersion: 1, versions: [] } })
    const store = useClusterStore()
    await store.rollbackConfig(1)
    expect(mockPOST).toHaveBeenCalledWith("/api/v1/cluster/config/rollback", {
      body: { targetVersion: 1 },
    })
  })

  it("rollbackConfig surfaces failures with a typed error", async () => {
    mockPOST.mockRejectedValue(new Error("409"))
    const store = useClusterStore()
    await expect(store.rollbackConfig(2)).rejects.toThrow("cluster-config-rollback")
  })
})
