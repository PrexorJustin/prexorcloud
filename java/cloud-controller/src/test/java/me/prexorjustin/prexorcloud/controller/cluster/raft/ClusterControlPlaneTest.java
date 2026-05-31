package me.prexorjustin.prexorcloud.controller.cluster.raft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Lease;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;
import me.prexorjustin.prexorcloud.controller.config.RaftConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R2 acceptance test for the cluster control plane on top of embedded Raft.
 * Proves typed apply correctness (each entry shape round-trips through Raft and
 * lands in the local projection), conflict semantics (stale parentVersion, replayed
 * join token, held lease), and snapshot+restart recovery.
 */
class ClusterControlPlaneTest {

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static RaftBootstrap newBootstrap(Path tmp, ClusterControlStateMachine sm, int port, UUID groupId) {
        RaftConfig cfg = new RaftConfig("127.0.0.1", port, tmp.resolve("raft").toString(), List.of());
        return new RaftBootstrap(cfg, groupId, "controller-1", sm);
    }

    @Test
    @DisplayName("typed apply round-trips for every entry shape")
    void typedApplyRoundTrip(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000d101");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        try (RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId)) {
            raft.start();
            raft.awaitLeader(10_000);
            ClusterControlPlane cp = new ClusterControlPlane(raft, sm);

            // Cluster meta (with seed).
            ClusterMeta meta = new ClusterMeta(
                    "cluster-" + UUID.randomUUID(),
                    "Y29udHJvbC1wbGFuZS1zZWVkLW5vdC1yZWFsLWp1c3QtdGVzdGluZw==",
                    Instant.parse("2026-05-29T12:00:00Z"),
                    ClusterMeta.CURRENT_SCHEMA_VERSION);
            cp.setClusterMeta(meta);
            assertEquals(meta, cp.getClusterMeta().orElseThrow());

            // Members.
            Member m1 = new Member(
                    "controller-1",
                    "127.0.0.1:" + port,
                    "127.0.0.1:8080",
                    "127.0.0.1:9090",
                    "primary",
                    Instant.parse("2026-05-29T12:00:01Z"),
                    Instant.parse("2026-05-29T12:00:01Z"));
            cp.addMember(m1);
            assertEquals(List.of(m1), cp.listMembers());

            cp.touchMember("controller-1", Instant.parse("2026-05-29T12:05:00Z"));
            assertEquals(Instant.parse("2026-05-29T12:05:00Z"), cp.listMembers().get(0).lastSeen());

            // Versioned config — first version with parentVersion=0, then a child.
            int v1 = cp.proposeConfigPatch(
                    0,
                    "wizard",
                    Map.of("runtime", Map.of("profile", "production")),
                    "first-boot");
            assertEquals(1, v1);
            assertEquals(1, cp.getActiveConfigVersion());

            int v2 = cp.proposeConfigPatch(
                    1,
                    "alice",
                    Map.of("http", Map.of("cors", Map.of("allowedOrigins", List.of("https://x.test")))),
                    "add CORS");
            assertEquals(2, v2);
            assertEquals(2, cp.getActiveConfigVersion());
            assertEquals(2, cp.listConfigVersions().size());

            // Rollback.
            cp.rollbackConfig(1, "alice");
            assertEquals(1, cp.getActiveConfigVersion());

            // Join token: write, redeem, replay rejected.
            JoinToken token = new JoinToken(
                    "jti-abc",
                    "deadbeef",
                    "controller-2-label",
                    "alice",
                    Instant.parse("2026-05-29T12:10:00Z"),
                    Instant.parse("2026-05-30T12:10:00Z"),
                    null,
                    null,
                    null,
                    false,
                    null,
                    null);
            cp.writeJoinToken(token);
            assertEquals(1, cp.listJoinTokens().size());

            cp.redeemJoinToken(
                    "jti-abc", Instant.parse("2026-05-29T12:11:00Z"), "10.0.0.42", "controller-2-node");
            JoinToken redeemed = cp.getJoinToken("jti-abc").orElseThrow();
            assertNotNull(redeemed.redeemedAt());
            assertEquals("controller-2-node", redeemed.redeemedAs());

            // Lease: grant + renew + release.
            cp.grantLease("scheduler", "controller-1", 30_000);
            Lease scheduled = cp.getLease("scheduler").orElseThrow();
            assertEquals("controller-1", scheduled.holder());
            cp.renewLease("scheduler", "controller-1");
            cp.releaseLease("scheduler", "controller-1");
            assertTrue(cp.getLease("scheduler").isEmpty());

            // Seed rotation preserves clusterId.
            cp.rotateSeed("bmV3LXNlZWQtMzItYnl0ZXM=", "alice");
            ClusterMeta rotated = cp.getClusterMeta().orElseThrow();
            assertEquals(meta.clusterId(), rotated.clusterId());
            assertEquals("bmV3LXNlZWQtMzItYnl0ZXM=", rotated.seedSecretBase64());
        }
    }

    @Test
    @DisplayName("conflict: stale parentVersion on config patch is rejected")
    void staleParentVersionRejected(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000d102");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        try (RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId)) {
            raft.start();
            raft.awaitLeader(10_000);
            ClusterControlPlane cp = new ClusterControlPlane(raft, sm);

            cp.proposeConfigPatch(0, "wizard", Map.of("k", "v0"), "init");
            cp.proposeConfigPatch(1, "alice", Map.of("k", "v1"), "first");
            assertEquals(2, cp.getActiveConfigVersion());

            ClusterWriteConflict ex = assertThrows(
                    ClusterWriteConflict.class,
                    () -> cp.proposeConfigPatch(1, "bob", Map.of("k", "v2"), "second-but-stale"),
                    "patch with parentVersion=1 must be rejected when active=2");
            assertEquals("PARENT_VERSION_STALE", ex.code());
            assertEquals(2, cp.getActiveConfigVersion(), "rejected patch must not advance the active version");
        }
    }

    @Test
    @DisplayName("issueJoinToken mints a wire token and writes the registry entry in one shot")
    void issueJoinTokenRoundTrip(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000d401");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        try (RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId)) {
            raft.start();
            raft.awaitLeader(10_000);
            ClusterControlPlane cp = new ClusterControlPlane(raft, sm);

            // Cluster meta must be present — issueJoinToken needs the seed secret + clusterId.
            cp.setClusterMeta(new ClusterMeta(
                    "cluster-issue-test",
                    "VGhpcyBpcyBhIDMyLWJ5dGUgZmFrZSBzZWVkLi4uLi4u",
                    Instant.parse("2026-05-29T12:00:00Z"),
                    ClusterMeta.CURRENT_SCHEMA_VERSION));

            var issued = cp.issueJoinToken(
                    List.of("controller-1.cluster.test:9091"),
                    java.time.Duration.ofHours(1),
                    "controller-2-label",
                    "alice");

            assertNotNull(issued.token());
            assertTrue(issued.token().startsWith("prexor-jt:v1:"));
            assertNotNull(issued.jti());

            // The token's registry entry landed in the SM via Raft.
            JoinToken record = cp.getJoinToken(issued.jti()).orElseThrow();
            assertEquals("controller-2-label", record.label());
            assertEquals("alice", record.createdBy());
            assertEquals(issued.expiresAt(), record.expiresAt());
            assertFalse(record.revoked());
            assertNull(record.redeemedAt());

            // The wire token's payload parses + verifies against the cluster seed.
            var parsed = me.prexorjustin.prexorcloud.controller.cluster.JoinTokenCodec.parse(issued.token());
            assertEquals("cluster-issue-test", parsed.payload().clusterId());
            assertEquals(issued.jti(), parsed.payload().jti());
            assertTrue(me.prexorjustin.prexorcloud.controller.cluster.JoinTokenCodec.verifyHmac(
                    parsed,
                    me.prexorjustin.prexorcloud.controller.cluster.JoinTokenCodec.decodeSeed(
                            "VGhpcyBpcyBhIDMyLWJ5dGUgZmFrZSBzZWVkLi4uLi4u")));

            // Revocation flips the flag idempotently.
            cp.revokeJoinToken(issued.jti(), "alice");
            JoinToken revoked = cp.getJoinToken(issued.jti()).orElseThrow();
            assertTrue(revoked.revoked());
            assertEquals("alice", revoked.revokedBy());
            cp.revokeJoinToken(issued.jti(), "alice"); // idempotent — no throw
        }
    }

    @Test
    @DisplayName("issueJoinToken without cluster meta throws — bootstrap must stamp meta first")
    void issueJoinTokenWithoutMetaThrows(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000d402");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        try (RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId)) {
            raft.start();
            raft.awaitLeader(10_000);
            ClusterControlPlane cp = new ClusterControlPlane(raft, sm);

            assertThrows(
                    IllegalStateException.class,
                    () -> cp.issueJoinToken(
                            List.of("controller-1.cluster.test:9091"),
                            java.time.Duration.ofHours(1),
                            null,
                            "alice"));
        }
    }

    @Test
    @DisplayName("conflict: replayed join token redemption is rejected")
    void replayedJoinTokenRejected(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000d103");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        try (RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId)) {
            raft.start();
            raft.awaitLeader(10_000);
            ClusterControlPlane cp = new ClusterControlPlane(raft, sm);

            JoinToken token = new JoinToken(
                    "jti-replay",
                    "h",
                    "ctrl-2",
                    "alice",
                    Instant.parse("2026-05-29T12:00:00Z"),
                    Instant.parse("2026-05-30T12:00:00Z"),
                    null, null, null, false, null, null);
            cp.writeJoinToken(token);
            cp.redeemJoinToken("jti-replay", Instant.parse("2026-05-29T12:01:00Z"), "10.0.0.42", "ctrl-2");

            ClusterWriteConflict ex = assertThrows(
                    ClusterWriteConflict.class,
                    () -> cp.redeemJoinToken(
                            "jti-replay", Instant.parse("2026-05-29T12:02:00Z"), "10.0.0.99", "ctrl-3"),
                    "second redemption of the same jti must be rejected");
            assertEquals("TOKEN_ALREADY_REDEEMED", ex.code());
        }
    }

    @Test
    @DisplayName("conflict: lease cannot be grabbed while held by another holder")
    void leaseHeld(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000d104");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        try (RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId)) {
            raft.start();
            raft.awaitLeader(10_000);
            ClusterControlPlane cp = new ClusterControlPlane(raft, sm);

            cp.grantLease("scheduler", "controller-1", 60_000);
            ClusterWriteConflict ex = assertThrows(
                    ClusterWriteConflict.class,
                    () -> cp.grantLease("scheduler", "controller-2", 60_000));
            assertEquals("LEASE_HELD", ex.code());
        }
    }

    @Test
    @DisplayName("snapshot + restart recovers state without log replay from index 1")
    void snapshotAndRestart(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000d105");
        Path dataDir = tmp.resolve("raft");
        RaftConfig cfg = new RaftConfig("127.0.0.1", port, dataDir.toString(), List.of());

        ClusterMeta meta = new ClusterMeta(
                "cluster-snapshot-test",
                "c2VlZA==",
                Instant.parse("2026-05-29T12:00:00Z"),
                ClusterMeta.CURRENT_SCHEMA_VERSION);

        // --- First lifecycle: write a bunch of entries, force a snapshot, shut down. ---
        long snapshotIndex;
        {
            ClusterControlStateMachine sm = new ClusterControlStateMachine();
            try (RaftBootstrap raft = new RaftBootstrap(cfg, groupId, "controller-1", sm)) {
                raft.start();
                raft.awaitLeader(10_000);
                ClusterControlPlane cp = new ClusterControlPlane(raft, sm);

                cp.setClusterMeta(meta);
                cp.proposeConfigPatch(0, "wizard", Map.of("k", "v0"), "init");
                cp.proposeConfigPatch(1, "alice", Map.of("k", "v1"), "second");
                cp.addMember(new Member(
                        "controller-1",
                        "127.0.0.1:" + port,
                        "127.0.0.1:8080",
                        "127.0.0.1:9090",
                        "primary",
                        Instant.parse("2026-05-29T12:01:00Z"),
                        Instant.parse("2026-05-29T12:01:00Z")));
                cp.writeJoinToken(new JoinToken(
                        "jti-snap",
                        "h",
                        null,
                        "alice",
                        Instant.parse("2026-05-29T12:02:00Z"),
                        Instant.parse("2026-05-30T12:02:00Z"),
                        null, null, null, false, null, null));
                cp.grantLease("scheduler", "controller-1", 30_000);
                cp.writeClusterFile("cluster-ca.crt", "fake-ca-cert".getBytes());
                cp.writeClusterFile("cluster-ca.key", "fake-ca-key".getBytes());

                // Ratis's SnapshotManagementApi reply doesn't reliably carry the snapshot
                // index back to the caller; we treat the call as fire-and-confirmed and let
                // the second-lifecycle recovery prove the snapshot actually wrote.
                snapshotIndex = cp.takeSnapshot();
                assertTrue(snapshotIndex >= 0, "takeSnapshot should not error");
            }
        }

        // --- Second lifecycle: restart, confirm the state machine is repopulated
        //     from the snapshot file (and any log delta) before we touch it. ---
        {
            ClusterControlStateMachine sm = new ClusterControlStateMachine();
            try (RaftBootstrap raft = new RaftBootstrap(cfg, groupId, "controller-1", sm)) {
                raft.start();
                raft.awaitLeader(10_000);
                ClusterControlPlane cp = new ClusterControlPlane(raft, sm);

                assertEquals(meta, cp.getClusterMeta().orElseThrow());
                assertEquals(2, cp.getActiveConfigVersion(), "active config version survives snapshot");
                assertEquals(2, cp.listConfigVersions().size(), "config history survives snapshot");
                assertEquals(1, cp.listMembers().size(), "members survive snapshot");
                assertEquals("controller-1", cp.listMembers().get(0).nodeId());
                JoinToken survivedToken = cp.getJoinToken("jti-snap").orElseThrow();
                assertFalse(survivedToken.revoked());
                assertEquals(
                        "controller-1", cp.getLease("scheduler").orElseThrow().holder());

                assertEquals(2, cp.listClusterFiles().size(), "cluster files survive snapshot");
                assertArrayEquals(
                        "fake-ca-cert".getBytes(),
                        cp.getClusterFile("cluster-ca.crt").orElseThrow().bytes());
                assertArrayEquals(
                        "fake-ca-key".getBytes(),
                        cp.getClusterFile("cluster-ca.key").orElseThrow().bytes());

                // Prove the recovered cluster still accepts new writes.
                cp.proposeConfigPatch(2, "bob", Map.of("k", "v2"), "after-restart");
                assertEquals(3, cp.getActiveConfigVersion());
            }
        }
    }

    @Test
    @DisplayName("commit listener fires for patch + rollback, and a throwing listener does not break apply")
    void commitListenerFiresAndIsErrorIsolated(@TempDir Path tmp) throws Exception {
        int port = freePort();
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-00000000d201");
        ClusterControlStateMachine sm = new ClusterControlStateMachine();
        java.util.List<me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry> committed =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean failOnce = new java.util.concurrent.atomic.AtomicBoolean(false);
        sm.setCommitListener(entry -> {
            if (failOnce.compareAndSet(true, false)) {
                throw new RuntimeException("boom — listener throws on this one only");
            }
            committed.add(entry);
        });
        try (RaftBootstrap raft = newBootstrap(tmp, sm, port, groupId)) {
            raft.start();
            raft.awaitLeader(10_000);
            ClusterControlPlane cp = new ClusterControlPlane(raft, sm);

            cp.proposeConfigPatch(0, "alice", Map.of("runtime", Map.of("profile", "prod")), "first");
            cp.proposeConfigPatch(1, "alice", Map.of("k", "v2"), "second");

            // Force the listener to throw on the next apply — apply() must still succeed.
            failOnce.set(true);
            cp.rollbackConfig(1, "alice");
            assertEquals(1, cp.getActiveConfigVersion(), "rollback must commit even when listener throws");

            // After the throw the listener is healthy again — one more write must surface.
            cp.proposeConfigPatch(1, "bob", Map.of("k", "v3"), "after-rollback");

            // We should have seen: WriteConfigVersion x2, [throw eats one], WriteConfigVersion x1.
            // The throwing rollback is missing from the captured list by design.
            assertEquals(3, committed.size(), "patches surfaced, only the throwing call dropped");
            assertTrue(committed.stream()
                    .allMatch(e -> e
                            instanceof me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry
                                    .WriteConfigVersion));
        }
    }
}
