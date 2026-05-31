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
})
