package me.prexorjustin.prexorcloud.controller.cluster.raft;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterConfigVersion;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.JoinToken;
import me.prexorjustin.prexorcloud.controller.cluster.state.Lease;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.SnapshotInfo;
import org.apache.ratis.statemachine.StateMachineStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State machine for the cluster control plane. Holds typed projections of
 * cluster identity, versioned config, members, join tokens, and leader leases.
 *
 * <p>Reads do not go through Raft — read methods on this class return immutable
 * snapshots of the local projection. {@link ClusterControlPlane} exposes typed
 * accessors that route writes through Raft and reads through these snapshots.
 *
 * <p>Snapshots: full state is serialised to a single JSON file under the Ratis
 * snapshot directory; restoring drops the in-memory state and reloads from the
 * file. Log replay handles the catchup delta from the snapshot's last applied
 * index to the live log tail.
 */
public final class ClusterControlStateMachine extends BaseStateMachine {

    private static final Logger logger = LoggerFactory.getLogger(ClusterControlStateMachine.class);

    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();

    // --- In-memory state. Single-writer (applyTransaction is serialised by Ratis) plus
    //     concurrent readers via the public snapshot methods. ---
    private final AtomicReference<ClusterMeta> meta = new AtomicReference<>();
    private final NavigableMap<Integer, ClusterConfigVersion> configVersions = new TreeMap<>();
    private final AtomicInteger activeConfigVersion = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Member> members = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JoinToken> joinTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClusterFile> clusterFiles = new ConcurrentHashMap<>();

    /**
     * Hook fired after each successful apply, on every controller. Set lazily by
     * {@code ClusterControlService} once the controller's EventBus exists (apply
     * happens before EventBus is wired during early bootstrap, those entries are
     * intentionally not surfaced — there is no listener yet).
     */
    private volatile java.util.function.Consumer<ClusterEntry> commitListener;

    public void setCommitListener(java.util.function.Consumer<ClusterEntry> listener) {
        this.commitListener = listener;
    }

    /**
     * Tracer for the {@code raft.apply} span (Track D.2). No-op until {@code ClusterControlService}
     * injects the real one once telemetry exists — early-bootstrap catch-up applies (which run
     * before telemetry is built) are intentionally untraced.
     */
    private volatile io.opentelemetry.api.trace.Tracer tracer =
            io.opentelemetry.api.OpenTelemetry.noop().getTracer("prexorcloud-controller");

    public void setTracer(io.opentelemetry.api.trace.Tracer tracer) {
        this.tracer = tracer != null
                ? tracer
                : io.opentelemetry.api.OpenTelemetry.noop().getTracer("prexorcloud-controller");
    }

    @Override
    public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage raftStorage) throws IOException {
        super.initialize(server, groupId, raftStorage);
        storage.init(raftStorage);
        loadLatestSnapshot();
        // BaseStateMachine.initialize() leaves the lifecycle in NEW. That works fine for normal
        // apply traffic, but the InstallSnapshot path on a freshly-joining follower goes
        // NEW -> PAUSING (illegal) and the SnapshotInstallationHandler aborts. Drive the
        // transition ourselves so we're ready to be paused-and-reinitialized at any time.
        getLifeCycle().transition(org.apache.ratis.util.LifeCycle.State.STARTING);
        getLifeCycle().transition(org.apache.ratis.util.LifeCycle.State.RUNNING);
    }

    @Override
    public StateMachineStorage getStateMachineStorage() {
        return storage;
    }

    // --- Snapshot install on a follower ---
    // Ratis requires the SM to be PAUSED before it calls reinitialize() on InstallSnapshot.
    // BaseStateMachine.pause()/reinitialize() are no-ops, so we transition the lifecycle and
    // reload the just-installed snapshot ourselves. Without this, a fresh peer joining behind
    // the leader's snapshot crashes its StateMachineUpdater with IllegalStateException.

    @Override
    public void pause() {
        getLifeCycle().transition(org.apache.ratis.util.LifeCycle.State.PAUSING);
        getLifeCycle().transition(org.apache.ratis.util.LifeCycle.State.PAUSED);
    }

    @Override
    public void reinitialize() throws IOException {
        loadLatestSnapshot();
        getLifeCycle().transition(org.apache.ratis.util.LifeCycle.State.STARTING);
        getLifeCycle().transition(org.apache.ratis.util.LifeCycle.State.RUNNING);
    }

    // --- Apply (writes through Raft) ---

    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        try {
            ClusterEntry entry =
                    ClusterEntry.decode(trx.getStateMachineLogEntry().getLogData());
            ClusterEntry.Reply reply = applyTraced(entry);
            if (reply.ok()) {
                notifyCommit(entry);
            }
            updateLastAppliedTermIndex(
                    trx.getLogEntry().getTerm(), trx.getLogEntry().getIndex());
            return CompletableFuture.completedFuture(Message.valueOf(reply.encode()));
        } catch (RuntimeException e) {
            logger.error("applyTransaction failed", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private void notifyCommit(ClusterEntry entry) {
        java.util.function.Consumer<ClusterEntry> listener = commitListener;
        if (listener == null) {
            return;
        }
        try {
            listener.accept(entry);
        } catch (RuntimeException ex) {
            // A faulty subscriber must not break the Raft apply loop — log and continue.
            logger.warn("commit listener threw on {}: {}", entry.getClass().getSimpleName(), ex.getMessage(), ex);
        }
    }

    /** Wrap {@link #apply} in a {@code raft.apply} span tagged with the entry type (Track D.2). */
    private ClusterEntry.Reply applyTraced(ClusterEntry entry) {
        io.opentelemetry.api.trace.Span span = tracer.spanBuilder("raft.apply").startSpan();
        span.setAttribute("raft.entry_type", entry.getClass().getSimpleName());
        try (io.opentelemetry.context.Scope ignored = span.makeCurrent()) {
            ClusterEntry.Reply reply = apply(entry);
            if (!reply.ok()) {
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            }
            return reply;
        } catch (RuntimeException e) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private ClusterEntry.Reply apply(ClusterEntry entry) {
        return switch (entry) {
            case ClusterEntry.SetClusterMeta e -> applySetClusterMeta(e);
            case ClusterEntry.RotateSeed e -> applyRotateSeed(e);
            case ClusterEntry.WriteConfigVersion e -> applyWriteConfigVersion(e);
            case ClusterEntry.SetActiveConfigVersion e -> applySetActiveConfigVersion(e);
            case ClusterEntry.AddMember e -> applyAddMember(e);
            case ClusterEntry.RemoveMember e -> applyRemoveMember(e);
            case ClusterEntry.TouchMember e -> applyTouchMember(e);
            case ClusterEntry.WriteJoinToken e -> applyWriteJoinToken(e);
            case ClusterEntry.RedeemJoinToken e -> applyRedeemJoinToken(e);
            case ClusterEntry.RevokeJoinToken e -> applyRevokeJoinToken(e);
            case ClusterEntry.GrantLease e -> applyGrantLease(e);
            case ClusterEntry.RenewLease e -> applyRenewLease(e);
            case ClusterEntry.ReleaseLease e -> applyReleaseLease(e);
            case ClusterEntry.WriteClusterFile e -> applyWriteClusterFile(e);
            case ClusterEntry.DeleteClusterFile e -> applyDeleteClusterFile(e);
        };
    }

    private ClusterEntry.Reply applySetClusterMeta(ClusterEntry.SetClusterMeta e) {
        ClusterMeta prior = meta.get();
        if (prior != null && !prior.clusterId().equals(e.meta().clusterId())) {
            return ClusterEntry.Reply.rejected(
                    "CLUSTER_ID_MISMATCH",
                    "Refusing to overwrite clusterId=" + prior.clusterId() + " with "
                            + e.meta().clusterId());
        }
        meta.set(e.meta());
        return ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyRotateSeed(ClusterEntry.RotateSeed e) {
        ClusterMeta prior = meta.get();
        if (prior == null) {
            return ClusterEntry.Reply.rejected("NO_CLUSTER_META", "RotateSeed before SetClusterMeta");
        }
        meta.set(new ClusterMeta(prior.clusterId(), e.newSeedSecretBase64(), prior.createdAt(), prior.schemaVersion()));
        return ClusterEntry.Reply.success();
    }

    private synchronized ClusterEntry.Reply applyWriteConfigVersion(ClusterEntry.WriteConfigVersion e) {
        ClusterConfigVersion cv = e.version();
        int maxExisting = configVersions.isEmpty() ? 0 : configVersions.lastKey();
        if (cv.version() != maxExisting + 1) {
            return ClusterEntry.Reply.rejected(
                    "VERSION_NOT_NEXT",
                    "WriteConfigVersion expected version=" + (maxExisting + 1) + " but got " + cv.version());
        }
        int active = activeConfigVersion.get();
        if (active != 0 && cv.parentVersion() != active) {
            return ClusterEntry.Reply.rejected(
                    "PARENT_VERSION_STALE",
                    "patch.parentVersion=" + cv.parentVersion() + " but cluster active=" + active);
        }
        if (active == 0 && cv.parentVersion() != 0) {
            return ClusterEntry.Reply.rejected(
                    "PARENT_VERSION_INVALID", "first version must declare parentVersion=0, got " + cv.parentVersion());
        }
        configVersions.put(cv.version(), cv);
        activeConfigVersion.set(cv.version());
        return ClusterEntry.Reply.success(Map.of("version", cv.version()));
    }

    private synchronized ClusterEntry.Reply applySetActiveConfigVersion(ClusterEntry.SetActiveConfigVersion e) {
        if (!configVersions.containsKey(e.version())) {
            return ClusterEntry.Reply.rejected(
                    "VERSION_UNKNOWN", "SetActiveConfigVersion to unknown version " + e.version());
        }
        activeConfigVersion.set(e.version());
        return ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyAddMember(ClusterEntry.AddMember e) {
        members.put(e.member().nodeId(), e.member());
        return ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyRemoveMember(ClusterEntry.RemoveMember e) {
        Member removed = members.remove(e.nodeId());
        return removed == null
                ? ClusterEntry.Reply.rejected("MEMBER_UNKNOWN", "no member " + e.nodeId())
                : ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyTouchMember(ClusterEntry.TouchMember e) {
        Member existing = members.get(e.nodeId());
        if (existing == null) {
            return ClusterEntry.Reply.rejected("MEMBER_UNKNOWN", "touch on unknown member " + e.nodeId());
        }
        members.put(
                e.nodeId(),
                new Member(
                        existing.nodeId(),
                        existing.raftAddr(),
                        existing.restAddr(),
                        existing.gRPCAddr(),
                        existing.label(),
                        existing.joinedAt(),
                        e.lastSeen()));
        return ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyWriteJoinToken(ClusterEntry.WriteJoinToken e) {
        // Idempotent: writing the same jti twice (e.g. retry) is a no-op so long as the
        // existing token is identical. Different payload for the same jti is rejected.
        JoinToken existing = joinTokens.get(e.token().jti());
        if (existing != null && !existing.equals(e.token())) {
            return ClusterEntry.Reply.rejected(
                    "JTI_COLLISION", "jti " + e.token().jti() + " already exists");
        }
        joinTokens.put(e.token().jti(), e.token());
        return ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyRedeemJoinToken(ClusterEntry.RedeemJoinToken e) {
        JoinToken token = joinTokens.get(e.jti());
        if (token == null) {
            return ClusterEntry.Reply.rejected("TOKEN_UNKNOWN", "no token " + e.jti());
        }
        if (token.revoked()) {
            return ClusterEntry.Reply.rejected("TOKEN_REVOKED", "token " + e.jti() + " was revoked");
        }
        if (token.redeemedAt() != null) {
            return ClusterEntry.Reply.rejected(
                    "TOKEN_ALREADY_REDEEMED",
                    "token " + e.jti() + " was already redeemed at " + token.redeemedAt() + " by "
                            + token.redeemedAs());
        }
        if (token.isExpired(e.redeemedAt())) {
            return ClusterEntry.Reply.rejected(
                    "TOKEN_EXPIRED", "token " + e.jti() + " expired at " + token.expiresAt());
        }
        joinTokens.put(
                e.jti(),
                new JoinToken(
                        token.jti(),
                        token.hmac(),
                        token.label(),
                        token.createdBy(),
                        token.createdAt(),
                        token.expiresAt(),
                        e.redeemedAt(),
                        e.redeemedFrom(),
                        e.redeemedAs(),
                        false,
                        null,
                        null));
        return ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyRevokeJoinToken(ClusterEntry.RevokeJoinToken e) {
        JoinToken token = joinTokens.get(e.jti());
        if (token == null) {
            return ClusterEntry.Reply.rejected("TOKEN_UNKNOWN", "no token " + e.jti());
        }
        if (token.revoked()) {
            return ClusterEntry.Reply.success(); // idempotent
        }
        joinTokens.put(
                e.jti(),
                new JoinToken(
                        token.jti(),
                        token.hmac(),
                        token.label(),
                        token.createdBy(),
                        token.createdAt(),
                        token.expiresAt(),
                        token.redeemedAt(),
                        token.redeemedFrom(),
                        token.redeemedAs(),
                        true,
                        e.revokedBy(),
                        e.revokedAt()));
        return ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyGrantLease(ClusterEntry.GrantLease e) {
        Lease existing = leases.get(e.name());
        if (existing != null
                && existing.isValid(e.grantedAt())
                && !existing.holder().equals(e.holder())) {
            return ClusterEntry.Reply.rejected(
                    "LEASE_HELD",
                    "lease '" + e.name() + "' is held by " + existing.holder() + " until "
                            + existing.renewedAt().plusMillis(existing.ttlMillis()));
        }
        leases.put(e.name(), new Lease(e.name(), e.holder(), e.grantedAt(), e.ttlMillis(), e.grantedAt()));
        return ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyRenewLease(ClusterEntry.RenewLease e) {
        Lease existing = leases.get(e.name());
        if (existing == null) {
            return ClusterEntry.Reply.rejected("LEASE_UNKNOWN", "no lease '" + e.name() + "'");
        }
        if (!existing.holder().equals(e.holder())) {
            return ClusterEntry.Reply.rejected(
                    "LEASE_NOT_HELD",
                    "lease '" + e.name() + "' is held by " + existing.holder() + ", not " + e.holder());
        }
        leases.put(
                e.name(), new Lease(e.name(), e.holder(), existing.grantedAt(), existing.ttlMillis(), e.renewedAt()));
        return ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyReleaseLease(ClusterEntry.ReleaseLease e) {
        Lease existing = leases.get(e.name());
        if (existing == null) {
            return ClusterEntry.Reply.success(); // idempotent
        }
        if (!existing.holder().equals(e.holder())) {
            return ClusterEntry.Reply.rejected(
                    "LEASE_NOT_HELD",
                    "lease '" + e.name() + "' is held by " + existing.holder() + ", not " + e.holder());
        }
        leases.remove(e.name());
        return ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyWriteClusterFile(ClusterEntry.WriteClusterFile e) {
        ClusterFile file = e.file();
        if (file == null || file.key() == null || file.key().isBlank()) {
            return ClusterEntry.Reply.rejected("FILE_KEY_INVALID", "cluster file key required");
        }
        if (file.bytes() == null) {
            return ClusterEntry.Reply.rejected("FILE_BYTES_NULL", "cluster file bytes required");
        }
        clusterFiles.put(file.key(), file);
        return ClusterEntry.Reply.success();
    }

    private ClusterEntry.Reply applyDeleteClusterFile(ClusterEntry.DeleteClusterFile e) {
        clusterFiles.remove(e.key()); // idempotent
        return ClusterEntry.Reply.success();
    }

    // --- Read API (direct projection, no Raft round-trip) ---

    public Optional<ClusterMeta> getClusterMeta() {
        return Optional.ofNullable(meta.get());
    }

    public int getActiveConfigVersion() {
        return activeConfigVersion.get();
    }

    public Optional<ClusterConfigVersion> getActiveConfigPatch() {
        int v = activeConfigVersion.get();
        return v == 0 ? Optional.empty() : Optional.ofNullable(configVersions.get(v));
    }

    public List<ClusterConfigVersion> listConfigVersions() {
        return List.copyOf(configVersions.values());
    }

    public List<Member> listMembers() {
        return members.values().stream()
                .sorted(Comparator.comparing(Member::nodeId))
                .toList();
    }

    public Optional<JoinToken> getJoinToken(String jti) {
        return Optional.ofNullable(joinTokens.get(jti));
    }

    public List<JoinToken> listJoinTokens() {
        return List.copyOf(joinTokens.values());
    }

    public Optional<Lease> getLease(String name) {
        return Optional.ofNullable(leases.get(name));
    }

    public List<Lease> getLeases() {
        return List.copyOf(leases.values());
    }

    public Optional<ClusterFile> getClusterFile(String key) {
        return Optional.ofNullable(clusterFiles.get(key));
    }

    public List<ClusterFile> listClusterFiles() {
        return clusterFiles.values().stream()
                .sorted(Comparator.comparing(ClusterFile::key))
                .toList();
    }

    // --- Snapshots ---

    @Override
    public long takeSnapshot() throws IOException {
        TermIndex applied = getLastAppliedTermIndex();
        if (applied == null || applied.getIndex() <= 0) {
            return -1L;
        }
        Path snapshotFile =
                storage.getSnapshotFile(applied.getTerm(), applied.getIndex()).toPath();
        Files.createDirectories(snapshotFile.getParent());
        try (OutputStream out = Files.newOutputStream(snapshotFile)) {
            SNAPSHOT_MAPPER.writeValue(out, captureState());
        }
        FileInfo fi = new FileInfo(snapshotFile, null);
        storage.updateLatestSnapshot(new SingleFileSnapshotInfo(fi, applied));
        logger.info("Took snapshot at term={}, index={}, file={}", applied.getTerm(), applied.getIndex(), snapshotFile);
        return applied.getIndex();
    }

    private void loadLatestSnapshot() throws IOException {
        SnapshotInfo latest = storage.getLatestSnapshot();
        if (latest == null) {
            return;
        }
        // Both SingleFileSnapshotInfo and FileListSnapshotInfo expose getFiles(); we expect
        // exactly one for our single-file layout.
        List<FileInfo> files = latest.getFiles();
        if (files == null || files.isEmpty()) {
            return;
        }
        Path path = files.get(0).getPath();
        if (!Files.exists(path)) {
            return;
        }
        SnapshotState state = SNAPSHOT_MAPPER.readValue(path.toFile(), SnapshotState.class);
        restoreState(state);
        setLastAppliedTermIndex(latest.getTermIndex());
        logger.info("Restored from snapshot term={}, index={}, file={}", latest.getTerm(), latest.getIndex(), path);
    }

    private SnapshotState captureState() {
        return new SnapshotState(
                meta.get(),
                List.copyOf(configVersions.values()),
                activeConfigVersion.get(),
                List.copyOf(members.values()),
                List.copyOf(joinTokens.values()),
                List.copyOf(leases.values()),
                List.copyOf(clusterFiles.values()));
    }

    private void restoreState(SnapshotState s) {
        meta.set(s.meta);
        configVersions.clear();
        if (s.configVersions != null) {
            for (ClusterConfigVersion v : s.configVersions) {
                configVersions.put(v.version(), v);
            }
        }
        activeConfigVersion.set(s.activeConfigVersion);
        members.clear();
        if (s.members != null) {
            for (Member m : s.members) {
                members.put(m.nodeId(), m);
            }
        }
        joinTokens.clear();
        if (s.joinTokens != null) {
            for (JoinToken t : s.joinTokens) {
                joinTokens.put(t.jti(), t);
            }
        }
        leases.clear();
        if (s.leases != null) {
            for (Lease l : s.leases) {
                leases.put(l.name(), l);
            }
        }
        clusterFiles.clear();
        if (s.clusterFiles != null) {
            for (ClusterFile f : s.clusterFiles) {
                clusterFiles.put(f.key(), f);
            }
        }
    }

    /** On-disk snapshot envelope. Same shape across versions until the schema changes. */
    public record SnapshotState(
            @JsonProperty("meta") ClusterMeta meta,
            @JsonProperty("configVersions") List<ClusterConfigVersion> configVersions,
            @JsonProperty("activeConfigVersion") int activeConfigVersion,
            @JsonProperty("members") List<Member> members,
            @JsonProperty("joinTokens") List<JoinToken> joinTokens,
            @JsonProperty("leases") List<Lease> leases,
            @JsonProperty("clusterFiles") List<ClusterFile> clusterFiles) {
        // Default ctor keeps lists non-null on deserialise of an older snapshot.
        public SnapshotState {
            if (configVersions == null) configVersions = new ArrayList<>();
            if (members == null) members = new ArrayList<>();
            if (joinTokens == null) joinTokens = new ArrayList<>();
            if (leases == null) leases = new ArrayList<>();
            if (clusterFiles == null) clusterFiles = new ArrayList<>();
        }
    }
}
