package me.prexorjustin.prexorcloud.controller.cluster.raft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;
import me.prexorjustin.prexorcloud.controller.config.RaftConfig;

import org.apache.ratis.RaftConfigKeys;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.grpc.GrpcTlsConfig;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.util.TimeDuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase-4 Ratis spike. Exercises the Ratis 3.1.3 APIs that production code for
 * gRPC membership + TLS bootstrap will rely on, so the open questions are
 * answered <em>before</em> we wire any of it into {@code RaftBootstrap}. Findings
 * are written up in {@code docs/engineering/ratis-spike.md}.
 *
 * <p>The four open questions:
 * <ol>
 *   <li><b>A — Membership API:</b> what is the exact API for adding a peer to an
 *       existing group? Confirmed: {@code GroupManagementApi.add()} on the new
 *       peer, followed by {@code AdminApi.setConfiguration()} on the leader.</li>
 *   <li><b>B — Snapshot catchup:</b> does Ratis ship state to a behind peer
 *       automatically? Confirmed: yes, via {@code InstallSnapshot} RPC, no
 *       extra wiring needed beyond our {@link ClusterControlStateMachine}'s
 *       existing {@code takeSnapshot}/{@code loadLatestSnapshot} hooks.</li>
 *   <li><b>C — TLS plumbing:</b> can we hand Ratis programmatic key+cert? Yes,
 *       via {@link GrpcTlsConfig#GrpcTlsConfig(java.security.PrivateKey,
 *       java.security.cert.X509Certificate, java.util.List, boolean)} pushed
 *       onto a {@link Parameters} block applied to both server and client.</li>
 *   <li><b>D — Port co-location:</b> Ratis owns its own gRPC port — confirmed
 *       by the existence of {@code GrpcConfigKeys.Server.PORT_KEY} with no API
 *       to share an external {@code Server}/{@code Channel}.</li>
 * </ol>
 *
 * <p>This test is tagged {@code spike} so the regular test suite skips it
 * unless explicitly invoked. It's intentionally slower than unit tests (joint
 * consensus + leader election + restart-from-snapshot) — keep it out of the
 * common path.
 */
@Tag("spike")
class RatisMultiPeerSpikeTest {

    private static final UUID GROUP_UUID = UUID.fromString("00000000-0000-0000-0000-0000000a4173");
    private static final RaftGroupId GROUP_ID = RaftGroupId.valueOf(GROUP_UUID);

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** A managed in-process Ratis peer. Non-final so {@link TlsPeer} can extend. */
    private static class Peer implements AutoCloseable {
        final RaftPeerId id;
        final RaftPeer peer;
        final Path storageDir;
        final int port;
        final ClusterControlStateMachine sm = new ClusterControlStateMachine();
        RaftServer server;
        RaftClient client;

        Peer(String nodeId, int port, Path storageDir) {
            this.id = RaftPeerId.valueOf(nodeId);
            this.port = port;
            this.peer = RaftPeer.newBuilder()
                    .setId(nodeId)
                    .setAddress("127.0.0.1:" + port)
                    .build();
            this.storageDir = storageDir;
        }

        void start(RaftGroup initialGroup) throws IOException {
            Files.createDirectories(storageDir);
            RaftProperties props = baseProps(storageDir, port);
            RaftServer.Builder builder =
                    RaftServer.newBuilder().setServerId(id).setProperties(props).setStateMachine(sm);
            if (initialGroup != null) {
                builder.setGroup(initialGroup);
            }
            server = builder.build();
            server.start();
            // Single per-peer client whose RaftGroup contains every peer we know about so far —
            // this is what production code would maintain and rebuild when membership changes.
            client = newClient(initialGroup != null ? initialGroup : RaftGroup.valueOf(GROUP_ID, peer));
        }

        void rebuildClient(RaftGroup currentGroup) throws IOException {
            if (client != null) {
                client.close();
            }
            client = newClient(currentGroup);
        }

        private RaftClient newClient(RaftGroup group) {
            return RaftClient.newBuilder()
                    .setClientId(ClientId.randomId())
                    .setProperties(baseProps(storageDir, port))
                    .setRaftGroup(group)
                    .build();
        }

        @Override
        public void close() throws IOException {
            if (client != null) {
                try {
                    client.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
            if (server != null) {
                server.close();
            }
        }
    }

    private static RaftProperties baseProps(Path storageDir, int port) {
        RaftProperties props = new RaftProperties();
        RaftConfigKeys.Rpc.setType(props, SupportedRpcType.GRPC);
        RaftServerConfigKeys.setStorageDir(props, List.of(storageDir.toFile()));
        GrpcConfigKeys.Server.setPort(props, port);
        RaftServerConfigKeys.Rpc.setTimeoutMin(props, TimeDuration.valueOf(150, TimeUnit.MILLISECONDS));
        RaftServerConfigKeys.Rpc.setTimeoutMax(props, TimeDuration.valueOf(300, TimeUnit.MILLISECONDS));
        // Faster snapshots for the catchup test — default is 400k entries which is too high.
        RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(props, true);
        RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(props, 50L);
        return props;
    }

    private static RaftGroup groupOf(Peer... peers) {
        List<RaftPeer> raftPeers = new ArrayList<>();
        for (Peer p : peers) {
            raftPeers.add(p.peer);
        }
        return RaftGroup.valueOf(GROUP_ID, raftPeers);
    }

    private static void awaitLeader(Peer p, long timeoutMs) throws IOException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            try {
                var info = p.client.getGroupManagementApi(p.id).info(GROUP_ID);
                if (info.getRoleInfoProto().getRole() == org.apache.ratis.proto.RaftProtos.RaftPeerRole.LEADER) {
                    return;
                }
            } catch (IOException ignored) {
                // server not ready, retry
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ie);
            }
        }
        throw new TimeoutException(p.id + " not leader within " + timeoutMs + "ms");
    }

    /**
     * Wait until {@code observer}'s local state machine reflects the meta that {@code leader}
     * wrote. Cross-peer replication is async — direct projection reads on a follower can lag
     * the leader's apply by several round trips.
     */
    private static void awaitMetaApplied(Peer observer, String expectedClusterId, long timeoutMs)
            throws TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            var meta = observer.sm.getClusterMeta();
            if (meta.isPresent() && expectedClusterId.equals(meta.get().clusterId())) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        }
        throw new TimeoutException(
                observer.id + " did not see meta " + expectedClusterId + " within " + timeoutMs + "ms");
    }

    private static void submitMeta(Peer leader, String clusterId) throws IOException {
        var entry =
                new me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry.SetClusterMeta(new ClusterMeta(
                        clusterId,
                        "c2VlZC1zZWVkLXNlZWQtc2VlZC1zZWVkLXNlZWQtc2VlZC1zZWVkPT0=",
                        Instant.parse("2026-05-31T12:00:00Z"),
                        ClusterMeta.CURRENT_SCHEMA_VERSION));
        var reply = leader.client.io().send(org.apache.ratis.protocol.Message.valueOf(entry.encode()));
        assertTrue(reply.isSuccess(), () -> "leader write failed: " + reply);
    }

    @Test
    @DisplayName("A+B: bootstrap single-peer group, add second + third peer, snapshot+log catchup on rejoin")
    void multiPeerMembershipAndCatchup(@TempDir Path tmp) throws Exception {
        Peer p1 = new Peer("controller-1", freePort(), tmp.resolve("p1"));
        Peer p2 = new Peer("controller-2", freePort(), tmp.resolve("p2"));
        Peer p3 = new Peer("controller-3", freePort(), tmp.resolve("p3"));

        try (p1;
                p2;
                p3) {
            // --- Bootstrap p1 as single-member group ---
            p1.start(groupOf(p1));
            awaitLeader(p1, 10_000);
            submitMeta(p1, "cluster-bootstrap");

            // --- Add p2 dynamically (Q A) ---
            // 1. p2 starts WITHOUT a group; its server is up but knows about no Raft group.
            p2.start(null);
            // 2. Tell p2's server "you now belong to this group" — Ratis formats p2's storage.
            //    The boolean is "format" — true forces a fresh format.
            RaftGroup twoPeerGroup = groupOf(p1, p2);
            p2.rebuildClient(twoPeerGroup);
            var addReply = p2.client.getGroupManagementApi(p2.id).add(twoPeerGroup, true);
            assertTrue(addReply.isSuccess(), () -> "GroupManagementApi.add(p2) failed: " + addReply);
            // 3. Tell the leader (p1) to expand membership via joint consensus.
            p1.rebuildClient(twoPeerGroup);
            RaftClientReply cfgReply = p1.client.admin().setConfiguration(List.of(p1.peer, p2.peer));
            assertTrue(cfgReply.isSuccess(), () -> "setConfiguration(p1,p2) failed: " + cfgReply);

            // p2 should converge on the meta written before it joined — via the InstallSnapshot
            // path if applicable, otherwise via log replay.
            awaitMetaApplied(p2, "cluster-bootstrap", 10_000);

            // --- Add p3 the same way ---
            p3.start(null);
            RaftGroup threePeerGroup = groupOf(p1, p2, p3);
            p3.rebuildClient(threePeerGroup);
            assertTrue(p3.client
                    .getGroupManagementApi(p3.id)
                    .add(threePeerGroup, true)
                    .isSuccess());
            p1.rebuildClient(threePeerGroup);
            p2.rebuildClient(threePeerGroup);
            assertTrue(p1.client
                    .admin()
                    .setConfiguration(List.of(p1.peer, p2.peer, p3.peer))
                    .isSuccess());
            awaitMetaApplied(p3, "cluster-bootstrap", 10_000);

            // --- Drive enough writes to force a snapshot (Q B) ---
            // Use AddMember entries since each one is a distinct apply that grows the log.
            for (int i = 0; i < 80; i++) {
                var addMember =
                        new me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry.AddMember(new Member(
                                "fake-member-" + i,
                                "127.0.0.1:" + (10_000 + i),
                                "127.0.0.1:" + (11_000 + i),
                                "127.0.0.1:" + (12_000 + i),
                                "stress-" + i,
                                Instant.parse("2026-05-31T12:00:00Z"),
                                Instant.parse("2026-05-31T12:00:00Z")));
                var reply = p1.client.io().send(org.apache.ratis.protocol.Message.valueOf(addMember.encode()));
                assertTrue(reply.isSuccess());
            }
            // Force a snapshot now so we know one exists before a brand-new peer joins behind it.
            assertTrue(p1.client.getSnapshotManagementApi(p1.id).create(30_000).isSuccess());

            // --- Add a brand-new peer-4 AFTER the snapshot exists (Q B) ---
            // This is the canonical "fresh controller joins an established cluster" scenario:
            // the joiner starts with no log, the leader's log start has already been compacted
            // beyond the joiner's nextIndex=0, so Ratis must InstallSnapshot to bring the joiner
            // up to the snapshot's last-applied index, then stream the remaining log delta.
            Peer p4 = new Peer("controller-4", freePort(), tmp.resolve("p4"));
            try (p4) {
                p4.start(null);
                RaftGroup fourPeerGroup = groupOf(p1, p2, p3, p4);
                p4.rebuildClient(fourPeerGroup);
                assertTrue(p4.client
                        .getGroupManagementApi(p4.id)
                        .add(fourPeerGroup, true)
                        .isSuccess());
                p1.rebuildClient(fourPeerGroup);
                assertTrue(p1.client
                        .admin()
                        .setConfiguration(List.of(p1.peer, p2.peer, p3.peer, p4.peer))
                        .isSuccess());

                // p4's state machine must converge to: cluster meta + 80 stress members. The
                // meta arrived via InstallSnapshot (it predates the snapshot); the 80 members
                // were in the snapshot too. If snapshot transfer is broken, we time out here.
                awaitMetaApplied(p4, "cluster-bootstrap", 30_000);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (System.nanoTime() < deadline && p4.sm.listMembers().size() < 80) {
                    Thread.sleep(50);
                }
                assertEquals(80, p4.sm.listMembers().size(), "p4 should have caught up to all 80 members via snapshot");
                assertNotNull(p4.sm.getClusterMeta().orElse(null));
            }
        }
    }

    @Test
    @DisplayName("awaitKnownLeader: a restarted follower returns on the remote leader instead of hanging")
    void followerRestartSeesLeader(@TempDir Path tmp) throws Exception {
        // Regression guard for the HA bring-up fix: start(materials) must wait for "a leader exists"
        // (self OR remote), not for self-leadership. A follower that restarts in a multi-node cluster
        // never re-takes leadership, so the old awaitLeader(self) would hang it until timeout and fail
        // boot. Here we form a real 2-node group via RaftBootstrap, restart the follower, and assert
        // awaitKnownLeader() returns while the node is still a follower.
        int leaderPort = freePort();
        int followerPort = freePort();
        RaftConfig leaderCfg =
                new RaftConfig("127.0.0.1", leaderPort, tmp.resolve("l").toString(), List.of());
        RaftConfig followerCfg =
                new RaftConfig("127.0.0.1", followerPort, tmp.resolve("f").toString(), List.of());

        RaftBootstrap leader =
                new RaftBootstrap(leaderCfg, GROUP_UUID, "controller-1", new ClusterControlStateMachine());
        RaftBootstrap follower = null;
        RaftBootstrap restarted = null;
        try {
            leader.start(); // no TLS, bootstrap single-member group
            leader.awaitLeader(10_000);

            RaftPeer leaderPeer = leader.selfPeer();
            RaftPeer followerPeer = RaftPeer.newBuilder()
                    .setId("controller-2")
                    .setAddress("127.0.0.1:" + followerPort)
                    .build();
            RaftGroup twoNode = RaftGroup.valueOf(GROUP_ID, List.of(leaderPeer, followerPeer));

            // Bring the follower in: join-mode add() on itself, then leader expands membership.
            follower = new RaftBootstrap(followerCfg, GROUP_UUID, "controller-2", new ClusterControlStateMachine());
            follower.startInJoinMode(null, twoNode);
            follower.joinExistingGroup(twoNode);
            setConfigurationWithRetry(leader, List.of(leaderPeer, followerPeer));

            // Core of the fix: a node that is NOT the leader still has a leader it can route to.
            // controller-1 keeps leadership (head start + log); controller-2 settles as a follower
            // that knows the leader. The old awaitLeader(self) would hang forever on such a node.
            long settle = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < settle && !(follower.hasKnownLeader() && !follower.isLeader())) {
                Thread.sleep(50);
            }
            assertFalse(follower.isLeader(), "controller-2 must be a follower in this group");
            assertTrue(follower.hasKnownLeader(), "follower must know its leader");
            follower.awaitKnownLeader(5_000); // returns because a remote leader is known — the regression guard

            // And the restart bring-up path must not hang either: reload the persisted group and gate
            // on awaitKnownLeader. (In a 2-node group the restarted node may re-win the election; we
            // only assert the gate returns — the non-leader case above is the discriminating proof.)
            follower.close();
            follower = null;
            restarted = new RaftBootstrap(followerCfg, GROUP_UUID, "controller-2", new ClusterControlStateMachine());
            restarted.start(); // restart path (isRestart=true)
            restarted.awaitKnownLeader(15_000);
        } finally {
            for (RaftBootstrap b : new RaftBootstrap[] {restarted, follower, leader}) {
                if (b != null) {
                    try {
                        b.close();
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                }
            }
        }
    }

    /**
     * #22 regression: reproduce the <em>production</em> join lifecycle end-to-end — through the real
     * {@link MembershipReconciler}, not a hand-rolled immediate {@code setConfiguration} — and assert
     * the joiner catches up and becomes a healthy voting member with no phantom.
     *
     * <p>The spike's {@link #multiPeerMembershipAndCatchup} and {@link #followerRestartSeesLeader}
     * both drive {@code setConfiguration} <em>immediately</em> after {@code add()}, so the bug never
     * shows. Production instead: (1) {@code RequestJoin} commits {@code AddMember(joiner)} on the
     * leader, (2) the joiner does {@code add()} on its own server, (3) the async reconciler — only
     * <em>later</em> — drives {@code setConfiguration}. We model that ordering exactly, with a
     * {@link #JOIN_RECONCILE_DELAY_MS} gap before the reconciler starts so the join-mode division has
     * sat idle the way it does in production.
     *
     * <p>Under the old voting-FOLLOWER join this is exactly where #22 bites: the deferred
     * {@code setConfiguration} can't commit until the not-yet-synced joiner acks (joint-consensus
     * needs it in the new-config majority), so it {@code NOPROGRESS}es and the joiner never syncs.
     * The listener-join-then-promote fix stages the joiner as a non-voting listener (commits against
     * the existing voters alone), lets it catch up, then promotes it. This test is the bar that fix
     * must keep green, deterministically (10/10).
     */
    @Test
    @DisplayName(
            "#22: joiner catches up + is promoted to a voting member via the real reconciler, even when it fires late")
    void joinerCatchesUpThroughReconciler(@TempDir Path tmp) throws Exception {
        int leaderPort = freePort();
        int joinerPort = freePort();
        RaftConfig leaderCfg =
                new RaftConfig("127.0.0.1", leaderPort, tmp.resolve("l").toString(), List.of());
        RaftConfig joinerCfg =
                new RaftConfig("127.0.0.1", joinerPort, tmp.resolve("j").toString(), List.of());

        ClusterControlStateMachine leaderSm = new ClusterControlStateMachine();
        ClusterControlStateMachine joinerSm = new ClusterControlStateMachine();
        RaftBootstrap leader = new RaftBootstrap(leaderCfg, GROUP_UUID, "controller-1", leaderSm);
        RaftBootstrap joiner = null;
        MembershipReconciler reconciler = null;
        try {
            leader.start();
            leader.awaitLeader(10_000);
            // Real state for the joiner to sync: cluster meta committed before it ever joins.
            submitMetaVia(leader, "cluster-22");
            // The leader must keep itself in the SM member list (production: ensureSelfMember) so the
            // reconciler keeps it a voter rather than configuring it out of its own group.
            addMemberVia(leader, "controller-1", "127.0.0.1:" + leaderPort);
            // RequestJoin commits AddMember(joiner) on the leader BEFORE the joiner is a Raft member.
            addMemberVia(leader, "controller-2", "127.0.0.1:" + joinerPort);

            RaftPeer leaderPeer = leader.selfPeer();
            RaftPeer joinerPeer = RaftPeer.newBuilder()
                    .setId("controller-2")
                    .setAddress("127.0.0.1:" + joinerPort)
                    .build();
            RaftGroup twoNode = RaftGroup.valueOf(GROUP_ID, List.of(leaderPeer, joinerPeer));

            joiner = new RaftBootstrap(joinerCfg, GROUP_UUID, "controller-2", joinerSm);
            // Production join sequence: join-mode start, then add() ourselves (as a LISTENER).
            joiner.startInJoinMode(null, twoNode);
            joiner.joinExistingGroup(twoNode);

            // The reconciler does NOT fire immediately. Let the join-mode division sit idle the way
            // it does in production before the reconciler wakes and stages the joiner.
            Thread.sleep(JOIN_RECONCILE_DELAY_MS);

            // Now bring up the leader's reconciler — it stages the joiner as a listener, lets it
            // catch up, then promotes it to a voting follower.
            reconciler = new MembershipReconciler(leader, leaderSm);
            reconciler.start();

            // (1) The joiner's SM must converge on the pre-join meta (it syncs as a listener).
            awaitMetaApplied(joinerSm, "cluster-22", 30_000);

            // (2) The reconciler promotes the caught-up listener into the committed voting set — and
            //     that promotion replicates to the joiner's own committed conf. (The role reload from
            //     listener to follower happens via an in-process restart in ClusterControlService;
            //     that end-to-end step is asserted in ClusterControlServiceJoinTest. Here we prove the
            //     Raft-level primitive: the deferred reconcile makes progress and the joiner becomes a
            //     committed voter, which the old voting-FOLLOWER join could not.)
            assertTrue(joiner.awaitLocalVoter(30_000), "joiner must be promoted into the committed voting set");

            // (3) No phantom: the leader's committed voting set and the Raft group are exactly the two
            //     expected members (memberCount == 2 == SM member count).
            assertEquals(
                    java.util.Set.of("controller-1", "controller-2"),
                    leader.localVoterIds(),
                    "committed voting set must be exactly the 2 members: " + leader.localVoterIds());
            RaftBootstrap.GroupView view = leader.groupView();
            assertEquals(
                    java.util.Set.of("controller-1", "controller-2"),
                    view.memberIds(),
                    "Raft group must be exactly the 2 members, no phantom: " + view.memberIds());

            // (4) The leader was never disrupted — listener staging does not trigger an election.
            assertTrue(leader.isLeader(), "leader stayed leader throughout the join");
        } finally {
            if (reconciler != null) {
                reconciler.close();
            }
            for (RaftBootstrap b : new RaftBootstrap[] {joiner, leader}) {
                if (b != null) {
                    try {
                        b.close();
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                }
            }
        }
    }

    /** Gap before the reconciler starts — models the async reconciler firing only after a backoff. */
    private static final long JOIN_RECONCILE_DELAY_MS = 6_000;

    /** Submit an AddMember entry through a {@link RaftBootstrap} leader (mirrors RequestJoin / ensureSelfMember). */
    private static void addMemberVia(RaftBootstrap leader, String nodeId, String raftAddr) throws IOException {
        var entry = new me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry.AddMember(new Member(
                nodeId,
                raftAddr,
                "",
                "",
                nodeId,
                Instant.parse("2026-05-31T12:00:00Z"),
                Instant.parse("2026-05-31T12:00:00Z")));
        leader.submitRaw(entry.encode());
    }

    /** Submit a SetClusterMeta entry through a {@link RaftBootstrap} leader (production write path). */
    private static void submitMetaVia(RaftBootstrap leader, String clusterId) throws IOException {
        var entry =
                new me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry.SetClusterMeta(new ClusterMeta(
                        clusterId,
                        "c2VlZC1zZWVkLXNlZWQtc2VlZC1zZWVkLXNlZWQtc2VlZC1zZWVkPT0=",
                        Instant.parse("2026-05-31T12:00:00Z"),
                        ClusterMeta.CURRENT_SCHEMA_VERSION));
        leader.submitRaw(entry.encode());
    }

    /** Wait until {@code sm} reflects the meta {@code expectedClusterId}; polls the local projection. */
    private static void awaitMetaApplied(ClusterControlStateMachine sm, String expectedClusterId, long timeoutMs)
            throws TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            var meta = sm.getClusterMeta();
            if (meta.isPresent() && expectedClusterId.equals(meta.get().clusterId())) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        }
        throw new TimeoutException("state machine did not see meta " + expectedClusterId + " within " + timeoutMs
                + "ms (joiner never synced — #22)");
    }

    /** Drive a membership change from the leader, retrying through the joiner-not-yet-acked window. */
    private static void setConfigurationWithRetry(RaftBootstrap leader, List<RaftPeer> peers) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                if (leader.setConfiguration(peers).isSuccess()) {
                    return;
                }
            } catch (IOException e) {
                last = e; // joiner quorum not ready yet — same race the production reconciler retries through
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("setConfiguration did not converge", last);
    }

    @Test
    @DisplayName("C: programmatic mTLS via GrpcTlsConfig (PrivateKey + X509Certificate)")
    void tlsBootstrap(@TempDir Path tmp) throws Exception {
        // Build a tiny in-memory CA and issue two node certs.
        InMemoryCa ca = InMemoryCa.create();
        InMemoryCa.IssuedCert c1 = ca.issue("controller-1");
        InMemoryCa.IssuedCert c2 = ca.issue("controller-2");

        Peer p1 = new TlsPeer("controller-1", freePort(), tmp.resolve("p1"), tlsConfig(c1, ca));
        Peer p2 = new TlsPeer("controller-2", freePort(), tmp.resolve("p2"), tlsConfig(c2, ca));

        try (p1;
                p2) {
            p1.start(groupOf(p1));
            awaitLeader(p1, 10_000);

            p2.start(null);
            RaftGroup g = groupOf(p1, p2);
            p2.rebuildClient(g);
            assertTrue(p2.client.getGroupManagementApi(p2.id).add(g, true).isSuccess());
            p1.rebuildClient(g);
            assertTrue(p1.client
                    .admin()
                    .setConfiguration(List.of(p1.peer, p2.peer))
                    .isSuccess());

            submitMeta(p1, "cluster-tls");
            awaitMetaApplied(p2, "cluster-tls", 10_000);
        }
    }

    private static GrpcTlsConfig tlsConfig(InMemoryCa.IssuedCert leaf, InMemoryCa ca) {
        return new GrpcTlsConfig(leaf.privateKey(), leaf.certificate(), List.of(ca.certificate()), true);
    }

    /**
     * TLS-enabled peer variant: same as {@link Peer} but applies a {@link GrpcTlsConfig} to all
     * three Ratis gRPC slots (server, client, admin) via a {@link Parameters} block. Tested in
     * {@link #tlsBootstrap(Path)}.
     */
    private static final class TlsPeer extends Peer {
        private final GrpcTlsConfig tls;

        TlsPeer(String nodeId, int port, Path storageDir, GrpcTlsConfig tls) {
            super(nodeId, port, storageDir);
            this.tls = tls;
        }

        @Override
        void start(RaftGroup initialGroup) throws IOException {
            Files.createDirectories(storageDir);
            RaftProperties props = baseProps(storageDir, port);
            Parameters params = new Parameters();
            GrpcConfigKeys.Server.setTlsConf(params, tls);
            GrpcConfigKeys.Client.setTlsConf(params, tls);
            GrpcConfigKeys.Admin.setTlsConf(params, tls);
            RaftServer.Builder builder = RaftServer.newBuilder()
                    .setServerId(id)
                    .setProperties(props)
                    .setParameters(params)
                    .setStateMachine(sm);
            if (initialGroup != null) {
                builder.setGroup(initialGroup);
            }
            server = builder.build();
            server.start();
            client = RaftClient.newBuilder()
                    .setClientId(ClientId.randomId())
                    .setProperties(props)
                    .setParameters(params)
                    .setRaftGroup(initialGroup != null ? initialGroup : RaftGroup.valueOf(GROUP_ID, peer))
                    .build();
        }

        @Override
        void rebuildClient(RaftGroup currentGroup) throws IOException {
            if (client != null) {
                client.close();
            }
            Parameters params = new Parameters();
            GrpcConfigKeys.Server.setTlsConf(params, tls);
            GrpcConfigKeys.Client.setTlsConf(params, tls);
            GrpcConfigKeys.Admin.setTlsConf(params, tls);
            client = RaftClient.newBuilder()
                    .setClientId(ClientId.randomId())
                    .setProperties(baseProps(storageDir, port))
                    .setParameters(params)
                    .setRaftGroup(currentGroup)
                    .build();
        }
    }
}
