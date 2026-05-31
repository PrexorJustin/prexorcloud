package me.prexorjustin.prexorcloud.controller.cluster.raft;

import java.io.File;
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
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.exceptions.RaftException;
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
    private final RaftGroup group;
    private final RaftPeer selfPeer;
    private final StateMachine stateMachine;
    private final ClientId clientId = ClientId.randomId();

    private RaftServer server;
    private RaftClient client;

    public RaftBootstrap(RaftConfig config, UUID groupUuid, String selfNodeId, StateMachine stateMachine) {
        this.config = config;
        this.stateMachine = stateMachine;
        String address = NetUtils.createSocketAddr(config.host(), config.port()).getHostString() + ":" + config.port();
        this.selfPeer = RaftPeer.newBuilder()
                .setId(selfNodeId)
                .setAddress(address)
                .build();
        this.group = RaftGroup.valueOf(RaftGroupId.valueOf(groupUuid), List.of(selfPeer));
    }

    public void start() throws IOException {
        Path storage = Path.of(config.dataDir());
        Files.createDirectories(storage);

        RaftProperties props = new RaftProperties();
        RaftConfigKeys.Rpc.setType(props, SupportedRpcType.GRPC);
        RaftServerConfigKeys.setStorageDir(props, List.of(storage.toFile()));
        GrpcConfigKeys.Server.setPort(props, config.port());
        // Faster election in tests and single-node clusters; the default 150-300ms is fine but a
        // single node has nothing to wait for. R3 will tune these for multi-member groups.
        RaftServerConfigKeys.Rpc.setTimeoutMin(props, TimeDuration.valueOf(150, TimeUnit.MILLISECONDS));
        RaftServerConfigKeys.Rpc.setTimeoutMax(props, TimeDuration.valueOf(300, TimeUnit.MILLISECONDS));

        // Detect "already formatted" by checking for the group's persisted dir. On bootstrap,
        // setGroup() formats the storage; on restart, Ratis loads the persisted group from
        // disk and rejects setGroup() because the dir is already formatted.
        Path groupDir = storage.resolve(group.getGroupId().getUuid().toString());
        boolean isRestart = Files.isDirectory(groupDir);

        RaftServer.Builder builder = RaftServer.newBuilder()
                .setServerId(selfPeer.getId())
                .setProperties(props)
                .setStateMachine(stateMachine);
        if (!isRestart) {
            builder.setGroup(group);
            logger.info("Bootstrapping fresh Raft group {} at {}", group.getGroupId(), storage.toAbsolutePath());
        } else {
            logger.info("Restarting existing Raft group from {}", storage.toAbsolutePath());
        }
        server = builder.build();
        server.start();
        logger.info(
                "Raft server started (group={}, peer={}@{}, dataDir={})",
                group.getGroupId(),
                selfPeer.getId(),
                selfPeer.getAddress(),
                storage.toAbsolutePath());

        client = RaftClient.newBuilder()
                .setClientId(clientId)
                .setRaftGroup(group)
                .setProperties(props)
                .build();
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
     */
    public void awaitLeader(long timeoutMs) throws IOException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            try {
                var groupInfo =
                        client.getGroupManagementApi(selfPeer.getId()).info(group.getGroupId());
                if (groupInfo.getRoleInfoProto().getRole()
                        == org.apache.ratis.proto.RaftProtos.RaftPeerRole.LEADER) {
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
        throw new TimeoutException("peer " + selfPeer.getId() + " did not become leader within " + timeoutMs + "ms");
    }

    public RaftGroup group() {
        return group;
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
