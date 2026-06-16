package me.prexorjustin.prexorcloud.controller.cluster.raft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.prexorjustin.prexorcloud.controller.config.RaftConfig;

import org.apache.ratis.RaftConfigKeys;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.grpc.GrpcTlsConfig;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.exceptions.RaftException;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.NetUtils;
import org.apache.ratis.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R1 spike: stands up an embedded Apache Ratis Raft server with a single-member
 * group, exposes a thin write/read API, and supports restart-recovery via Ratis
 * log replay. Real cluster control plane wiring (multi-member groups, join over
 * gRPC, leader leases) lands in R3+. See
 * {@code docs/engineering/cluster-join-plan.md}.
 *
 * <p>Single-node Raft means the server is its own leader and every write commits
 * to its own log. Replaying that log on restart restores the state machine — the
 * R1 success criterion.
 */
public final class RaftBootstrap implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RaftBootstrap.class);

    private final RaftConfig config;
    private final RaftGroupId groupId;
    private final RaftPeer selfPeer;
    private final StateMachine stateMachine;
    private final ClientId clientId = ClientId.randomId();

    /** Mutable so {@link #rebuildClient(RaftGroup)} can swap it as membership grows. */
    private volatile RaftGroup currentGroup;

    private RaftServer server;
    private RaftClient client;

    public RaftBootstrap(RaftConfig config, UUID groupUuid, String selfNodeId, StateMachine stateMachine) {
        this.config = config;
        this.stateMachine = stateMachine;
        String address = NetUtils.createSocketAddr(config.host(), config.port()).getHostString() + ":" + config.port();
        this.selfPeer =
                RaftPeer.newBuilder().setId(selfNodeId).setAddress(address).build();
        this.groupId = RaftGroupId.valueOf(groupUuid);
        this.currentGroup = RaftGroup.valueOf(groupId, List.of(selfPeer));
    }

    public void start() throws IOException {
        start(null);
    }

    /**
     * Start in bootstrap-or-restart mode (Day-0 / restart of an existing member). With
     * {@code tls != null} the server presents the supplied cluster cert and trusts the
     * cluster CA; with {@code tls == null} the server runs without TLS — only safe for
     * single-node test setups.
     */
    public void start(GrpcTlsConfig tls) throws IOException {
        Path storage = Path.of(config.dataDir());
        Files.createDirectories(storage);

        RaftProperties props = baseProperties(storage);
        Parameters params = tlsParameters(tls);

        // Detect "already formatted" by checking for the group's persisted dir. On bootstrap,
        // setGroup() formats the storage; on restart, Ratis loads the persisted group from
        // disk and rejects setGroup() because the dir is already formatted.
        Path groupDir = storage.resolve(groupId.getUuid().toString());
        boolean isRestart = Files.isDirectory(groupDir);

        RaftServer.Builder builder = RaftServer.newBuilder()
                .setServerId(selfPeer.getId())
                .setProperties(props)
                .setStateMachine(stateMachine);
        if (params != null) {
            builder.setParameters(params);
        }
        if (!isRestart) {
            builder.setGroup(currentGroup);
            logger.info("Bootstrapping fresh Raft group {} at {}", groupId, storage.toAbsolutePath());
        } else {
            logger.info("Restarting existing Raft group from {}", storage.toAbsolutePath());
        }
        server = builder.build();
        server.start();
        logger.info(
                "Raft server started (group={}, peer={}@{}, dataDir={}, tls={})",
                groupId,
                selfPeer.getId(),
                selfPeer.getAddress(),
                storage.toAbsolutePath(),
                tls != null);

        client = newClient(props, params, currentGroup);
    }

    /**
     * Start in join mode (Day-N joiner). The server comes up with no initial group; the
     * caller is responsible for calling {@link #joinExistingGroup(RaftGroup)} with the
     * current cluster membership to actually enter the group. {@code tls} is required —
     * a joiner without cluster-CA-signed mTLS material won't pass the existing peers'
     * mTLS guard.
     */
    public void startInJoinMode(GrpcTlsConfig tls, RaftGroup initialKnownGroup) throws IOException {
        Path storage = Path.of(config.dataDir());
        Files.createDirectories(storage);

        RaftProperties props = baseProperties(storage);
        Parameters params = tlsParameters(tls);

        // No setGroup — the new peer has nothing in its storage and learns its group via
        // GroupManagementApi.add() in joinExistingGroup() below. This is the same pattern
        // the multi-peer spike test uses; see docs/engineering/ratis-spike.md.
        RaftServer.Builder builder = RaftServer.newBuilder()
                .setServerId(selfPeer.getId())
                .setProperties(props)
                .setStateMachine(stateMachine);
        if (params != null) {
            builder.setParameters(params);
        }
        server = builder.build();
        server.start();
        logger.info(
                "Raft server started in join mode (peer={}@{}, dataDir={}, tls={})",
                selfPeer.getId(),
                selfPeer.getAddress(),
                storage.toAbsolutePath(),
                tls != null);

        // Build the management client against the known peers so we can route the add() call
        // to ourselves and subsequent reads/writes to the rest of the group.
        this.currentGroup = initialKnownGroup;
        client = newClient(props, params, initialKnownGroup);
    }

    /**
     * Inform this peer's server "you now belong to {@code group}". Triggers Ratis storage
     * format on the joiner and primes it to receive log entries. The leader must then
     * call {@link #setConfiguration(java.util.List)} to actually expand the Raft group's
     * membership via joint consensus.
     */
    public void joinExistingGroup(RaftGroup group) throws IOException {
        var reply = client.getGroupManagementApi(selfPeer.getId()).add(group, true);
        if (!reply.isSuccess()) {
            RaftException ex = reply.getException();
            throw new IOException("GroupManagementApi.add failed: " + (ex == null ? "unknown" : ex.getMessage()));
        }
        this.currentGroup = group;
        logger.info(
                "Joined existing group {} ({} peers)",
                group.getGroupId(),
                group.getPeers().size());
    }

    /** Rebuild the management client to know about the current peer list. */
    public void rebuildClient(RaftGroup newGroup) throws IOException {
        if (client != null) {
            client.close();
        }
        Path storage = Path.of(config.dataDir());
        client = newClient(baseProperties(storage), null, newGroup);
        this.currentGroup = newGroup;
    }

    /**
     * Drive a joint-consensus membership change on the leader. Idempotent: Ratis treats
     * a setConfiguration with the same peer list as a no-op. Surfaces the underlying
     * exception (NotLeaderException is the common "I'm not the right peer for this" reply).
     */
    public RaftClientReply setConfiguration(List<RaftPeer> newPeers) throws IOException {
        return client.admin().setConfiguration(newPeers);
    }

    /** Whether this peer's server currently believes it is the group leader. */
    public boolean isLeader() throws IOException {
        var info = client.getGroupManagementApi(selfPeer.getId()).info(groupId);
        return info.getRoleInfoProto().getRole() == org.apache.ratis.proto.RaftProtos.RaftPeerRole.LEADER;
    }

    /**
     * Whether this peer's group currently has a leader it can route to — either this
     * peer is the leader, or it is a follower that knows who the leader is. Distinct
     * from {@link #isLeader()}: a restarting follower in a multi-node cluster never
     * becomes leader, so waiting on self-leadership would hang it forever; what it
     * actually needs before serving is a reachable leader for its Raft writes.
     */
    public boolean hasKnownLeader() throws IOException {
        var role = client.getGroupManagementApi(selfPeer.getId()).info(groupId).getRoleInfoProto();
        return switch (role.getRole()) {
            case LEADER -> true;
            case FOLLOWER ->
                !role.getFollowerInfo().getLeaderInfo().getId().getId().isEmpty();
            default -> false; // CANDIDATE / not-yet-initialised: no leader to route to
        };
    }

    private RaftProperties baseProperties(Path storage) {
        RaftProperties props = new RaftProperties();
        RaftConfigKeys.Rpc.setType(props, SupportedRpcType.GRPC);
        RaftServerConfigKeys.setStorageDir(props, List.of(storage.toFile()));
        GrpcConfigKeys.Server.setPort(props, config.port());
        // Faster election in tests and single-node clusters; the default 150-300ms is fine
        // but a single node has nothing to wait for. Multi-peer tuning happens later.
        RaftServerConfigKeys.Rpc.setTimeoutMin(props, TimeDuration.valueOf(150, TimeUnit.MILLISECONDS));
        RaftServerConfigKeys.Rpc.setTimeoutMax(props, TimeDuration.valueOf(300, TimeUnit.MILLISECONDS));
        return props;
    }

    private static Parameters tlsParameters(GrpcTlsConfig tls) {
        if (tls == null) {
            return null;
        }
        Parameters params = new Parameters();
        GrpcConfigKeys.Server.setTlsConf(params, tls);
        GrpcConfigKeys.Client.setTlsConf(params, tls);
        GrpcConfigKeys.Admin.setTlsConf(params, tls);
        return params;
    }

    private RaftClient newClient(RaftProperties props, Parameters params, RaftGroup group) {
        // Bound control-plane writes. The default Ratis client retry policy retries
        // effectively forever, so a write submitted to a controller that has lost quorum
        // (e.g. 2 of 3 controllers down) blocks indefinitely — the request just queues
        // waiting for a commit that can never happen, and the REST layer never reaches its
        // IOException -> 503 RAFT_UNAVAILABLE mapping (the HTTP client times out instead).
        // A per-request timeout plus a bounded retry count makes such a write fail fast:
        // the budget (~3 retries x (3s timeout + 1s sleep) ~= 12s) is generous enough to
        // ride through a sub-5s leader re-election but short enough to surface a sustained
        // quorum loss as a clean 503 instead of a hang.
        RaftClientConfigKeys.Rpc.setRequestTimeout(props, TimeDuration.valueOf(3, TimeUnit.SECONDS));
        var b = RaftClient.newBuilder()
                .setClientId(clientId)
                .setRaftGroup(group)
                .setProperties(props)
                .setRetryPolicy(RetryPolicies.retryUpToMaximumCountWithFixedSleep(
                        3, TimeDuration.valueOf(1, TimeUnit.SECONDS)));
        if (params != null) {
            b.setParameters(params);
        }
        return b.build();
    }

    /** Submit a write to the Raft log; blocks until committed. Returns the state machine's reply bytes. */
    public org.apache.ratis.thirdparty.com.google.protobuf.ByteString submitRaw(
            org.apache.ratis.thirdparty.com.google.protobuf.ByteString payload) throws IOException {
        var reply = client.io().send(Message.valueOf(payload));
        if (!reply.isSuccess()) {
            RaftException exception = reply.getException();
            throw new IOException("Raft write failed: " + (exception == null ? "unknown" : exception.getMessage()));
        }
        return reply.getMessage().getContent();
    }

    /** Read through the state machine query path (sequentially consistent). */
    public org.apache.ratis.thirdparty.com.google.protobuf.ByteString queryRaw(
            org.apache.ratis.thirdparty.com.google.protobuf.ByteString payload) throws IOException {
        var reply = client.io().sendReadOnly(Message.valueOf(payload));
        if (!reply.isSuccess()) {
            RaftException exception = reply.getException();
            throw new IOException("Raft query failed: " + (exception == null ? "unknown" : exception.getMessage()));
        }
        return reply.getMessage().getContent();
    }

    /** Trigger a state-machine snapshot via the Ratis admin API. */
    public long takeSnapshot() throws IOException {
        var reply = client.getSnapshotManagementApi(selfPeer.getId()).create(60_000L);
        if (!reply.isSuccess()) {
            RaftException ex = reply.getException();
            throw new IOException("snapshot failed: " + (ex == null ? "unknown" : ex.getMessage()));
        }
        return reply.getLogIndex();
    }

    /**
     * Wait until this peer has stabilised as the group leader. Single-node groups always
     * elect self, but the election still takes the configured min timeout.
     *
     * <p>Use {@link #awaitKnownLeader(long)} on the restart/bootstrap path instead — a
     * follower restart never re-takes leadership and would time out here.
     */
    public void awaitLeader(long timeoutMs) throws IOException, TimeoutException {
        awaitCondition(timeoutMs, this::isLeader, "become leader", "leader elected");
    }

    /**
     * Wait until this peer's group has a leader it can route to (self or remote). This is
     * the correct gate for bring-up: Day-0 / single-node elects self; a follower restart in
     * a multi-node cluster waits for the established leader rather than for itself.
     */
    public void awaitKnownLeader(long timeoutMs) throws IOException, TimeoutException {
        awaitCondition(timeoutMs, this::hasKnownLeader, "see a leader", "leader available");
    }

    /** Shared poll loop for the await* gates. {@code condition} may throw while the server is still coming up. */
    private interface RaftCondition {
        boolean test() throws IOException;
    }

    private void awaitCondition(long timeoutMs, RaftCondition condition, String failVerb, String okMessage)
            throws IOException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long start = System.nanoTime();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.test()) {
                    // Ratis' own election logs are silenced (WARN); surface the outcome ourselves.
                    logger.info(
                            "Raft control plane {}: peer {} (group {}) after {}ms",
                            okMessage,
                            selfPeer.getId(),
                            groupId,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    return;
                }
            } catch (IOException ignored) {
                // retry until deadline — server may not yet be accepting RPCs
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted awaiting leader", e);
            }
        }
        throw new TimeoutException("peer " + selfPeer.getId() + " did not " + failVerb + " within " + timeoutMs + "ms");
    }

    public RaftGroup group() {
        return currentGroup;
    }

    public RaftPeer selfPeer() {
        return selfPeer;
    }

    @Override
    public void close() throws IOException {
        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                logger.warn("RaftClient.close: {}", e.getMessage());
            }
        }
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                logger.warn("RaftServer.close: {}", e.getMessage());
            }
        }
    }
}
