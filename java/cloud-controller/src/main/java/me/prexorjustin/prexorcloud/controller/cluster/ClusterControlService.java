package me.prexorjustin.prexorcloud.controller.cluster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import me.prexorjustin.prexorcloud.api.event.CloudEvent;
import me.prexorjustin.prexorcloud.api.event.events.ClusterConfigChangedEvent;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlStateMachine;
import me.prexorjustin.prexorcloud.controller.cluster.raft.MembershipReconciler;
import me.prexorjustin.prexorcloud.controller.cluster.raft.RaftBootstrap;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterEntry;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;
import me.prexorjustin.prexorcloud.controller.config.ClusterConfig;
import me.prexorjustin.prexorcloud.controller.config.ClusterJoinTemplate;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.protocol.ClusterMembershipGrpc;
import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;

import io.grpc.ManagedChannel;
import org.apache.ratis.grpc.GrpcTlsConfig;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the cluster control plane lifecycle: Raft server + state machine +
 * {@link ClusterControlPlane} façade, plus first-boot stamping, restart
 * reconciliation against {@code controller.yml}, and the one-shot v1.0 → v1.1
 * migration that seeds {@code clusterConfig} from the legacy YAML using
 * {@link ClusterJoinTemplate}.
 *
 * <p>This is the integration seam {@code PrexorCloudBootstrap} calls into. It
 * deliberately keeps the Raft and yaml-mirror coupling in one place so the
 * bootstrap orchestration stays linear: start the service, ask it for the
 * effective config, proceed.
 *
 * <p>Why a fixed Raft group UUID: a single PrexorCloud install is a single
 * Raft group; the group identifier on the wire is just Ratis bookkeeping, not
 * the cluster's semantic identity. The cluster's identity ({@code clusterId})
 * lives in the state machine's {@link ClusterMeta}. The group UUID is a
 * well-known constant so peers can discover each other without out-of-band
 * configuration.
 */
public final class ClusterControlService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ClusterControlService.class);

    /**
     * Well-known UUID for the controller cluster's Raft group. Constant by
     * design — see class doc.
     */
    public static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-707265786f72");

    private final ControllerConfig config;
    private final String nodeId;
    private final Clock clock;
    private final SecureRandom random;

    private RaftBootstrap raft;
    private ClusterControlStateMachine stateMachine;
    private ClusterControlPlane controlPlane;
    private MembershipReconciler reconciler;
    private String resolvedClusterId;
    // Set when this boot freshly stamped cluster identity (Day-0 branch) while controller.yml
    // already carried a cluster.id — i.e. the Raft state was wiped but the configured identity
    // was retained. That is the signature of a catastrophic single-survivor reset
    // (docs/runbooks/recover-cluster.md), as opposed to a virgin first-ever boot (no yaml id).
    private volatile boolean unsafeResetDetected;

    /** Effective config after first-boot seeding or v1.0 migration. May equal the input on restart. */
    private ControllerConfig effectiveConfig;

    /** Test seam — overrides the join-flow stub factory. Production wires the gRPC channel. */
    private JoinChannelFactory joinChannelFactory = ClusterControlService::defaultJoinChannel;

    public ClusterControlService(ControllerConfig config, String nodeId) {
        this(config, nodeId, Clock.systemUTC(), new SecureRandom());
    }

    /** Test seam — fixed clock and seeded random for deterministic assertions. */
    public ClusterControlService(ControllerConfig config, String nodeId, Clock clock, SecureRandom random) {
        this.config = config;
        this.nodeId = nodeId;
        this.clock = clock;
        this.random = random;
        this.effectiveConfig = config;
    }

    /** Test seam — inject an in-process stub for the {@code RequestJoin} gRPC call. */
    public void setJoinChannelFactory(JoinChannelFactory factory) {
        this.joinChannelFactory = factory;
    }

    /** Address the joiner's Raft transport binds to, advertised to the cluster as part of the join. */
    private String selfRaftAddress() {
        return config.raft().host() + ":" + config.raft().port();
    }

    private RaftGroupId raftGroupId() {
        return RaftGroupId.valueOf(GROUP_ID);
    }

    /**
     * Day-0 bootstrap or restart of an existing member. The split is on whether
     * {@code materials} already holds this node's cluster TLS material:
     *
     * <ul>
     *   <li><b>Day-0 (materials absent):</b> mint a fresh cluster CA + leaf cert
     *       in memory, persist them through {@code materials}, then start Raft
     *       with TLS as a single-member group. Also writes the CA into the Raft
     *       state machine so future joiners receive it via snapshot.</li>
     *   <li><b>Restart (materials present):</b> load the persisted CA + leaf,
     *       start Raft with TLS, let Ratis replay the log. The CA is already in
     *       state; {@link #ensureClusterCa} is a no-op.</li>
     * </ul>
     *
     * <p>Either way: {@link #reconcileClusterIdentity}, {@link #ensureClusterCa}
     * and {@link #runV10MigrationIfNeeded} run last so they see the live Raft
     * state regardless of branch.
     */
    public void start(LocalClusterMaterials materials) throws IOException, TimeoutException {
        if (materials == null) {
            // Tests that just want the cluster control plane without TLS material
            // wired call start() (the no-arg overload below); production always
            // passes a materials handle. Keeping both legal preserves the existing
            // ClusterControlServiceTest surface.
            startWithoutTls();
            return;
        }
        stateMachine = new ClusterControlStateMachine();
        raft = new RaftBootstrap(config.raft(), GROUP_ID, nodeId, stateMachine);

        if (materials.exists()) {
            LocalClusterMaterials.Loaded loaded = materials.load();
            GrpcTlsConfig tls = buildTls(loaded);
            raft.start(tls);
            logger.info("Restarted Raft with persisted cluster TLS material from {}", materials.directory());
            // Restart path: a leader may not be immediately available — in a multi-member quorum this
            // node may be a follower rejoining, or the group may be briefly leaderless while peers
            // restart. Wait best-effort so a follower restart doesn't hang forever waiting to lead
            // itself; the Raft server stays up and converges in the background.
            awaitLeaderBestEffort();
        } else {
            CertificateAuthority ca = mintAndPersistDay0Materials(materials);
            LocalClusterMaterials.Loaded loaded = materials.load();
            GrpcTlsConfig tls = buildTls(loaded);
            raft.start(tls);
            // Stash the CA so ensureClusterCa() doesn't have to re-mint when we get
            // there. ensureClusterCa() checks raft state first; we write the bytes in
            // a moment.
            day0InMemoryCa = ca;
            // Day-0 single-member bring-up must elect itself before we proceed — keep this strict.
            raft.awaitKnownLeader(15_000);
        }
        controlPlane = new ClusterControlPlane(raft, stateMachine);

        reconcileClusterIdentity(raft.wasFreshBootstrap());
        ensureClusterCa();
        reconcileSelfClusterTls(materials);
        runV10MigrationIfNeeded();
        ensureSelfMember();
        startMembershipReconciler();
    }

    /** Tests-only entry: no TLS, no on-disk material. Day-0 / restart still work via Ratis storage. */
    public void start() throws IOException, TimeoutException {
        startWithoutTls();
    }

    private void startWithoutTls() throws IOException, TimeoutException {
        stateMachine = new ClusterControlStateMachine();
        raft = new RaftBootstrap(config.raft(), GROUP_ID, nodeId, stateMachine);
        raft.start();
        raft.awaitKnownLeader(15_000);
        controlPlane = new ClusterControlPlane(raft, stateMachine);

        reconcileClusterIdentity(raft.wasFreshBootstrap());
        ensureClusterCa();
        runV10MigrationIfNeeded();
        // Mirror the production start(materials) tail so the self-member / reconciler
        // path has test/prod parity (the TLS branch is the only difference).
        ensureSelfMember();
        startMembershipReconciler();
    }

    /**
     * Best-effort leader wait for the restart path. Unlike {@link RaftBootstrap#awaitKnownLeader} on
     * Day-0 (which must self-elect), a restarting member rejoining an existing quorum may legitimately
     * find no leader yet (peers restarting, brief quorum loss). Timing out here must NOT propagate and
     * exit the JVM — that would kill the very Raft server the quorum needs alive to elect a leader, so a
     * controller restarting into a quorum-less multi-member group would crash-loop forever. Instead we
     * log a degraded-mode warning and continue; the cluster converges in the background.
     */
    private void awaitLeaderBestEffort() {
        try {
            raft.awaitKnownLeader(15_000);
        } catch (TimeoutException e) {
            logger.warn("No Raft leader visible within 15s on restart — continuing bring-up in degraded mode."
                    + " The Raft server stays up; the cluster elects a leader once a quorum of peers"
                    + " is present, and the membership reconciler re-runs setConfiguration. Cluster"
                    + " writes (joins, config patches, lease grants) fail until a leader is available.");
        } catch (IOException e) {
            logger.warn(
                    "Error while awaiting a Raft leader on restart ({}) — continuing bring-up;"
                            + " the cluster will converge in the background.",
                    e.getMessage());
        }
    }

    /**
     * Day-N join. Parses the wire token, dials the existing cluster, redeems the
     * token, gets a cluster-CA-signed leaf cert, persists the materials locally,
     * then brings up Raft in join mode and calls {@code GroupManagementApi.add()}
     * on itself. After this returns, the controller is a full peer — its state
     * machine will fill in by InstallSnapshot from the leader.
     *
     * <p>Idempotency model: the caller (bootstrap) deletes the on-disk token only
     * after this method returns. If we throw partway through, the operator just
     * restarts; the next attempt purges {@code materials}, the Raft data dir, and
     * retries from scratch. The token is single-use server-side, so a retry that
     * gets past redemption-of-an-already-redeemed-token surfaces a
     * {@code ClusterWriteConflict} the bootstrap reports.
     */
    public void startInJoinMode(String token, LocalClusterMaterials materials, JoinIdentity selfIdentity)
            throws IOException, TimeoutException {
        // Wipe stale state from any previous half-failed join attempt — we want a
        // clean Raft data dir and no leftover leaf cert before we re-run.
        purgeJoinState(materials);

        ClusterJoinFlow.JoinResult joinResult;
        ManagedChannel channel = null;
        try {
            String dialTarget = pickDialTarget(token);
            channel = joinChannelFactory.open(dialTarget);
            var stub = ClusterMembershipGrpc.newBlockingStub(channel);
            ClusterJoinFlow flow = new ClusterJoinFlow(stub);
            joinResult = flow.join(
                    token,
                    new ClusterJoinFlow.JoinIdentity(
                            nodeId, selfIdentity.raftAddr(), selfIdentity.restAddr(), selfIdentity.grpcAddr()));
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cluster join failed", e);
        } finally {
            if (channel != null) {
                channel.shutdownNow();
            }
        }

        // Persist materials BEFORE we start Raft — a crash between the persist and the
        // Ratis bring-up leaves the operator with a clean retry path (purge + redo).
        materials.persist(joinResult.caCert(), joinResult.signedCert(), joinResult.privateKey());

        stateMachine = new ClusterControlStateMachine();
        raft = new RaftBootstrap(config.raft(), GROUP_ID, nodeId, stateMachine);

        LocalClusterMaterials.Loaded loaded = materials.load();
        GrpcTlsConfig tls = buildTls(loaded);

        RaftPeer self = RaftPeer.newBuilder()
                .setId(nodeId)
                .setAddress(selfRaftAddress())
                .build();
        List<RaftPeer> knownPeers = Stream.concat(
                        joinResult.existingPeers().stream()
                                .map(p -> RaftPeer.newBuilder()
                                        .setId(p.nodeId())
                                        .setAddress(p.raftAddr())
                                        .build()),
                        Stream.of(self))
                .toList();
        RaftGroup knownGroup = RaftGroup.valueOf(raftGroupId(), knownPeers);

        raft.startInJoinMode(tls, knownGroup);
        raft.joinExistingGroup(knownGroup);

        controlPlane = new ClusterControlPlane(raft, stateMachine);
        resolvedClusterId = joinResult.clusterId();
        // Mirror the cluster id into our local ControllerConfig so callers reading
        // effectiveConfig() see it. The yaml mirror is written by bootstrap.
        effectiveConfig = withClusterId(effectiveConfig, joinResult.clusterId());

        startMembershipReconciler();

        logger.info(
                "Joined cluster {} as {} (non-voting listener) with {} existing peer(s); local TLS material"
                        + " persisted to {}",
                joinResult.clusterId(),
                nodeId,
                joinResult.existingPeers().size(),
                materials.directory());

        promoteToVotingMemberByRestart(materials);
    }

    /** Generous bound for the leader to promote a caught-up listener into the voting set. */
    private static final long JOIN_PROMOTION_TIMEOUT_MS = 60_000;

    /**
     * Finish the #22 listener-join: once the leader has promoted this caught-up listener into the
     * voting set (its local committed conf now lists it as a follower), restart the Raft division
     * in-process so it assumes the voting FOLLOWER role.
     *
     * <p>Why a restart is needed: a controller joins as a non-voting Ratis LISTENER so it can catch
     * up without stalling the leader's {@code setConfiguration} (a voting-follower join makes the
     * deferred reconcile {@code NOPROGRESS} on the not-yet-synced joiner — the core of #22). Ratis
     * 3.1.3 does not transition a <em>running</em> listener's role when the configuration promotes it
     * to a voter; only a (re)start re-reads the persisted conf and picks the follower role. The
     * heavy catch-up already happened as a listener, so this restart is fast and is the proven-healthy
     * restart-mode division path (see {@code RatisMultiPeerSpikeTest.followerRestartSeesLeader}).
     *
     * <p>Degrades gracefully: if the promotion does not land within the timeout we stay a listener
     * (still serving reads and forwarding writes to the leader) rather than fail the join — the node
     * becomes a voter on its next restart once the promotion has replicated. It never stamps a fresh
     * identity (the await-not-stamp invariant from commit {@code 22515f0} is untouched).
     */
    private void promoteToVotingMemberByRestart(LocalClusterMaterials materials) throws IOException, TimeoutException {
        boolean promoted = raft.awaitLocalVoter(JOIN_PROMOTION_TIMEOUT_MS);
        if (!promoted) {
            logger.warn(
                    "Join: not promoted into the voting set within {}ms — continuing as a non-voting listener."
                            + " The node serves reads and forwards writes to the leader but does not yet count toward HA"
                            + " quorum; it becomes a voting member on its next restart once the promotion replicates.",
                    JOIN_PROMOTION_TIMEOUT_MS);
            return;
        }
        logger.info("Join: promoted into the voting set; restarting Raft division in-process to assume the"
                + " voting follower role.");
        // Tear down the listener division and its (dormant — we are not the leader) reconciler.
        if (reconciler != null) {
            reconciler.close();
        }
        raft.close();
        // Rebuild from the persisted, now-voting conf. start() takes the restart path (storage is
        // already formatted), re-reads the conf and starts as a FOLLOWER; the fresh state machine
        // replays log + snapshot. Best-effort leader wait so a transient leaderless window doesn't
        // crash the bring-up (mirrors the restart branch of start(materials)).
        stateMachine = new ClusterControlStateMachine();
        raft = new RaftBootstrap(config.raft(), GROUP_ID, nodeId, stateMachine);
        LocalClusterMaterials.Loaded loaded = materials.load();
        raft.start(buildTls(loaded));
        awaitLeaderBestEffort();
        controlPlane = new ClusterControlPlane(raft, stateMachine);
        startMembershipReconciler();
        logger.info("Join: promotion complete — now a voting follower.");
    }

    private static ManagedChannel defaultJoinChannel(String hostPort) throws Exception {
        return ClusterJoinFlow.insecureChannelTo(hostPort);
    }

    private String pickDialTarget(String token) {
        // The token's joinAddrs[] is the existing controllers' join-membership endpoints.
        // We dial the first one; if it's unreachable a future iteration can fall back to the
        // rest of the list. For v1.1 single-leader / small clusters, head-of-list is enough.
        var parsed = JoinTokenCodec.parse(token);
        var addrs = parsed.payload().joinAddrs();
        if (addrs.isEmpty()) {
            throw new IllegalStateException("join token contains no joinAddrs — cannot dial cluster");
        }
        return addrs.get(0);
    }

    private void purgeJoinState(LocalClusterMaterials materials) throws IOException {
        materials.purge();
        Path raftDir = Path.of(config.raft().dataDir());
        if (Files.isDirectory(raftDir)) {
            try (Stream<Path> entries = Files.walk(raftDir)) {
                entries.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort
                    }
                });
            }
        }
    }

    private CertificateAuthority day0InMemoryCa;

    private CertificateAuthority mintAndPersistDay0Materials(LocalClusterMaterials materials) throws IOException {
        try {
            CertificateAuthority ca = CertificateAuthority.createInMemory("PrexorCloud Cluster CA", 3650);
            // Issue a self-signed leaf for this node. SANs cover both the configured raft
            // host and the loopback aliases that tests dial via.
            List<String> sans = Stream.of(nodeId, config.raft().host(), "127.0.0.1", "localhost")
                    .distinct()
                    .toList();
            var leaf = ca.issueClusterPeerCertificate(nodeId, sans, 365);
            materials.persist(
                    ca.certificate(), leaf.certificate(), leaf.keyPair().getPrivate());
            logger.info(
                    "Minted Day-0 cluster CA + self leaf cert; persisted to {} (CA fingerprint={})",
                    materials.directory(),
                    ca.fingerprint());
            return ca;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Day-0 cluster CA mint failed", e);
        }
    }

    private GrpcTlsConfig buildTls(LocalClusterMaterials.Loaded loaded) {
        return new GrpcTlsConfig(loaded.leafKey(), loaded.leafCert(), List.of(loaded.caCert()), true);
    }

    /**
     * Ensure this node's {@link Member} record carries its current advertised Raft
     * address, so the {@link MembershipReconciler} drives the Ratis group (and the
     * address handed to future joiners) to the right place.
     *
     * <ul>
     *   <li><b>Day-0:</b> no self member yet — stamp one. The reconciler then sets it
     *       as the single-member Ratis group.</li>
     *   <li><b>Restart with an unchanged address:</b> no-op.</li>
     *   <li><b>Restart with a changed address (self-heal):</b> the operator moved this
     *       controller to a routable {@code raft.host} (e.g. the Day-0 default
     *       {@code 0.0.0.0} → a private IP for HA). The Ratis group config persisted on
     *       disk still pins the old address, and {@code applyAddMember} is an upsert, so
     *       we re-stamp the member with the new address. That commit wakes the reconciler,
     *       which runs {@code setConfiguration} on the leader so the live group — and the
     *       join response sent to peers — advertises the new address.</li>
     * </ul>
     */
    private void ensureSelfMember() {
        Optional<Member> existing = controlPlane.listMembers().stream()
                .filter(m -> nodeId.equals(m.nodeId()))
                .findFirst();
        String want = selfRaftAddress();
        if (existing.isPresent()) {
            Member current = existing.get();
            if (want.equals(current.raftAddr())) {
                return; // address unchanged — nothing to reconcile
            }
            try {
                Member healed = new Member(
                        current.nodeId(),
                        want,
                        current.restAddr(),
                        current.gRPCAddr(),
                        current.label(),
                        current.joinedAt(),
                        Instant.now(clock));
                controlPlane.addMember(healed);
                logger.info("Self-healed member {} raftAddr {} -> {}", nodeId, current.raftAddr(), want);
            } catch (IOException e) {
                logger.warn("could not reconcile self member address: {}", e.getMessage());
            }
            return;
        }
        try {
            Member self = new Member(nodeId, want, "", "", nodeId, Instant.now(clock), Instant.now(clock));
            controlPlane.addMember(self);
            logger.info("Stamped Day-0 self member {} at {}", nodeId, want);
        } catch (IOException e) {
            logger.warn("could not add self to cluster members: {}", e.getMessage());
        }
    }

    private void startMembershipReconciler() {
        reconciler = new MembershipReconciler(raft, stateMachine);
        reconciler.start();
    }

    private static ControllerConfig withClusterId(ControllerConfig cfg, String clusterId) {
        ClusterConfig prior = cfg.cluster();
        ClusterConfig updated = new ClusterConfig(
                clusterId, prior == null ? null : prior.joinedFrom(), prior == null ? null : prior.joinedAt());
        return new ControllerConfig(
                cfg.uuid(),
                cfg.http(),
                cfg.grpc(),
                cfg.network(),
                cfg.database(),
                cfg.logging(),
                cfg.scheduler(),
                cfg.heartbeat(),
                cfg.runtime(),
                cfg.security(),
                cfg.crashes(),
                cfg.metrics(),
                cfg.modules(),
                cfg.maintenance(),
                cfg.dashboard(),
                cfg.backup(),
                cfg.share(),
                cfg.networks(),
                cfg.events(),
                cfg.redis(),
                updated,
                cfg.raft());
    }

    /** Identity advertised by the joiner: REST + gRPC bind addresses go into the cluster member list. */
    public record JoinIdentity(String raftAddr, String restAddr, String grpcAddr) {}

    /** Factory for the gRPC channel used during {@link #startInJoinMode}; in-process channel in tests. */
    @FunctionalInterface
    public interface JoinChannelFactory {
        ManagedChannel open(String hostPort) throws Exception;
    }

    /**
     * Either stamp a fresh {@link ClusterMeta} (first-ever boot) or verify
     * the existing one against {@code controller.yml}'s mirror. Mismatch refuses
     * to boot — same shape as the Phase 1 check, just sourced from Raft instead
     * of Mongo.
     */
    private void reconcileClusterIdentity(boolean day0Bootstrap) throws IOException {
        Optional<ClusterMeta> existing = controlPlane.getClusterMeta();
        String yamlClusterId =
                config.cluster() == null ? null : config.cluster().id();

        if (existing.isEmpty() && !day0Bootstrap) {
            // This is a restart (or a joiner's restart) of a member that already belongs to a
            // cluster — NOT a virgin Day-0 boot. An empty state machine here just means the SM
            // hasn't replayed/synced the cluster meta yet; it is replicated in from the leader via
            // Raft. Stamping a fresh identity now would FORK the cluster (a second clusterId + seed),
            // and on a stuck joiner the patient write would hang the whole boot (#22). Wait for the
            // meta to arrive instead; if it doesn't, continue degraded (the SM will adopt the
            // leader's identity once it catches up) — never stamp.
            existing = awaitClusterMeta(java.time.Duration.ofSeconds(30));
            if (existing.isEmpty()) {
                resolvedClusterId = yamlClusterId;
                logger.warn("Cluster meta not yet synced 30s into a restart — continuing in degraded mode without"
                        + " stamping a fresh identity; the state machine adopts the leader's identity once it"
                        + " catches up.");
                return;
            }
        }

        if (existing.isPresent()) {
            ClusterMeta meta = existing.get();
            if (yamlClusterId != null && !yamlClusterId.equals(meta.clusterId())) {
                throw new IllegalStateException("Configured cluster.id=" + yamlClusterId
                        + " but Raft state holds cluster.id=" + meta.clusterId()
                        + ". Either restore the original Raft data dir, or remove cluster.id from"
                        + " controller.yml to adopt this Raft state's existing id.");
            }
            resolvedClusterId = meta.clusterId();
            logger.info("Cluster identity verified (cluster.id={})", resolvedClusterId);
            return;
        }

        // No cluster meta AND a genuine fresh bootstrap (day0). First-ever boot (or first v1.1 boot
        // post-migration, or a single-survivor reset). Generate a fresh identity AND seed secret.
        String clusterId =
                yamlClusterId != null ? yamlClusterId : UUID.randomUUID().toString();
        byte[] seed = new byte[32];
        random.nextBytes(seed);
        String seedB64 = Base64.getEncoder().encodeToString(seed);
        ClusterMeta seeded =
                new ClusterMeta(clusterId, seedB64, Instant.now(clock), ClusterMeta.CURRENT_SCHEMA_VERSION);
        controlPlane.setClusterMeta(seeded);
        resolvedClusterId = clusterId;
        logger.info(
                "Stamped fresh cluster.id={} into Raft state (yamlSource={})",
                clusterId,
                yamlClusterId != null ? "yes" : "no");
        if (yamlClusterId != null) {
            // Fresh Raft state but a retained configured cluster.id => the Raft dataDir was
            // wiped under an existing install. This is a catastrophic single-survivor reset,
            // not a virgin boot. Flag it so bootstrap records the cluster.recovery.unsafe-reset
            // audit event. The clusterId is preserved; the cluster CA, seed secret, and config
            // history are regenerated (operators must re-issue join tokens — see the runbook).
            unsafeResetDetected = true;
            logger.warn(
                    "Catastrophic single-survivor reset detected: Raft state was empty but cluster.id={}"
                            + " is configured. Re-formed a single-member cluster; CA + seed + config history"
                            + " were regenerated. Rotate the seed and re-issue join tokens"
                            + " (docs/runbooks/recover-cluster.md).",
                    clusterId);
        }
    }

    /** Poll the local SM projection until the cluster meta replicates in from the leader, or timeout. */
    private Optional<ClusterMeta> awaitClusterMeta(java.time.Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Optional<ClusterMeta> meta = controlPlane.getClusterMeta();
        while (meta.isEmpty() && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            meta = controlPlane.getClusterMeta();
        }
        return meta;
    }

    /**
     * True when this boot took the Day-0 stamping path but {@code controller.yml} already
     * carried a {@code cluster.id} — the signature of a single-survivor reset (wiped Raft
     * dataDir under an existing install). Bootstrap reads this to write the
     * {@code cluster.recovery.unsafe-reset} audit event.
     */
    public boolean unsafeResetDetected() {
        return unsafeResetDetected;
    }

    /**
     * Day-0: mint the cluster CA in-process and stamp its cert + private key into
     * the Raft state machine as cluster files. Subsequent controllers (Day-N
     * joiners and restarts) load it back from there — there is no on-disk CA
     * keystore. Idempotent: a non-empty CA cert in the state means we already did
     * this and we just log the fingerprint for visibility.
     */
    private void ensureClusterCa() throws IOException {
        if (controlPlane.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT).isPresent()) {
            return;
        }
        try {
            // Reuse the CA that {@link #mintAndPersistDay0Materials} minted if we're on the
            // Day-0 path — that way the bytes in Raft state exactly match the bytes on disk
            // (and the leaf cert we just issued chains correctly).
            CertificateAuthority ca = day0InMemoryCa != null
                    ? day0InMemoryCa
                    : CertificateAuthority.createInMemory("PrexorCloud Cluster CA", 3650);
            controlPlane.writeClusterFile(
                    ClusterFile.KEY_CLUSTER_CA_CERT, ca.certificate().getEncoded());
            controlPlane.writeClusterFile(
                    ClusterFile.KEY_CLUSTER_CA_KEY, ca.keyPair().getPrivate().getEncoded());
            logger.info("Stamped cluster CA (fingerprint={}) into Raft state", ca.fingerprint());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to generate cluster CA", e);
        }
    }

    /**
     * Heal a cluster-CA split left by a single-survivor reset. The reset regenerates the cluster CA
     * in Raft state (see {@link #reconcileClusterIdentity} / {@link #ensureClusterCa}) but keeps this
     * controller's <em>stale on-disk</em> leaf + trust anchor, so its Raft server presents and trusts
     * an older CA than the authoritative one handed to joiners. A single-member cluster never does
     * peer mTLS, so it boots fine — but the moment a peer connects the Raft handshake fails
     * {@code PKIX path validation … signature check failed} and membership changes
     * {@code NOPROGRESS}. When the on-disk CA no longer matches the Raft-state CA we re-issue this
     * node's leaf from the Raft-state CA and restart the Raft server in-process so it presents the
     * consistent material. Best-effort: on any failure we log and continue on the existing material
     * rather than fail boot (a controller that can't realign still serves as a single member).
     */
    private void reconcileSelfClusterTls(LocalClusterMaterials materials) {
        if (materials == null || !materials.exists()) {
            return; // no on-disk material to realign (no-TLS test path / Day-0 mints consistent material)
        }
        try {
            Optional<ClusterFile> raftCaCert = controlPlane.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT);
            Optional<ClusterFile> raftCaKey = controlPlane.getClusterFile(ClusterFile.KEY_CLUSTER_CA_KEY);
            if (raftCaCert.isEmpty() || raftCaKey.isEmpty()) {
                return; // no authoritative CA in Raft state to realign against
            }
            byte[] raftCaDer = raftCaCert.get().bytes();
            LocalClusterMaterials.Loaded onDisk = materials.load();
            if (java.util.Arrays.equals(onDisk.caCert().getEncoded(), raftCaDer)) {
                return; // on-disk trust already matches the Raft-state CA — nothing to do
            }
            logger.warn("Cluster CA split detected: this controller's on-disk CA does not match the authoritative"
                    + " Raft-state CA. Re-issuing the local leaf from the Raft-state CA and restarting Raft so"
                    + " peers can complete mTLS (leftover of a single-survivor reset — see"
                    + " docs/engineering/issue-22-join-lifecycle.md).");
            CertificateAuthority ca = CertificateAuthority.loadFromDer(raftCaDer, raftCaKey.get().bytes());
            List<String> sans = Stream.of(nodeId, config.raft().host(), "127.0.0.1", "localhost")
                    .distinct()
                    .toList();
            var leaf = ca.issueClusterPeerCertificate(nodeId, sans, 365);
            materials.persist(
                    ca.certificate(), leaf.certificate(), leaf.keyPair().getPrivate());
            restartRaftWithCurrentMaterials(materials);
            logger.info("Cluster CA realigned to the Raft-state CA (fingerprint={}); Raft restarted with consistent"
                    + " material — peers can now mTLS.", ca.fingerprint());
        } catch (Exception e) {
            logger.warn("Self cluster-TLS reconcile failed ({}) — continuing on the existing on-disk material;"
                    + " peer mTLS may fail until the CA is realigned.", e.getMessage(), e);
        }
    }

    /**
     * Tear down and restart the embedded Raft server in-process with whatever TLS material is now on
     * disk, replaying state into a fresh state machine. Used to pick up realigned cluster TLS material
     * (a single-member survivor re-elects itself within the election timeout). The membership
     * reconciler is intentionally NOT (re)started here — the caller's bring-up tail does that.
     */
    private void restartRaftWithCurrentMaterials(LocalClusterMaterials materials)
            throws IOException, TimeoutException {
        raft.close();
        stateMachine = new ClusterControlStateMachine();
        raft = new RaftBootstrap(config.raft(), GROUP_ID, nodeId, stateMachine);
        LocalClusterMaterials.Loaded loaded = materials.load();
        raft.start(buildTls(loaded));
        awaitLeaderBestEffort();
        controlPlane = new ClusterControlPlane(raft, stateMachine);
    }

    /**
     * Day-0 cluster has no {@code cluster_config} yet. Build the initial version
     * from the cluster-shared subset of the operator-supplied {@code
     * controller.yml} (same projection that the v1 plan's
     * {@code GET /api/v1/admin/cluster/join-template} returned) and write it as
     * version 1. Idempotent on restart: if {@code activeConfigVersion > 0} this
     * does nothing.
     */
    private void runV10MigrationIfNeeded() throws IOException {
        if (controlPlane.getActiveConfigVersion() > 0) {
            return;
        }
        Map<String, Object> initial = ClusterJoinTemplate.buildSharedMap(config);
        if (initial.isEmpty()) {
            logger.warn("No cluster-shared config detected in controller.yml — leaving cluster_config empty until"
                    + " the wizard or first PATCH writes one.");
            return;
        }
        int newVersion = controlPlane.proposeConfigPatch(
                0, "v1.0-migration", initial, "Seeded from controller.yml on first v1.1 boot");
        logger.info(
                "Migrated cluster-shared config from controller.yml into Raft as version {} ({} top-level keys)",
                newVersion,
                initial.size());
    }

    public ClusterControlPlane controlPlane() {
        return controlPlane;
    }

    /**
     * Bridge the Raft state-machine commit stream onto the controller's local
     * {@link EventBus}. Called by bootstrap once {@code CoreServices} exists
     * (the EventBus is built after this service starts, so wiring is deferred).
     *
     * <p>Translates {@link ClusterEntry} types into typed {@link CloudEvent}s.
     * Today only config patches and rollbacks are surfaced; member / join-token
     * / lease entries can grow their own events as subscribers materialise
     * (phases 8, 9, 10 of cluster-join-plan.md).
     */
    /** Inject the OpenTelemetry tracer into the state machine so committed entries get a span. */
    public void attachTracer(io.opentelemetry.api.trace.Tracer tracer) {
        if (tracer == null || stateMachine == null) {
            return;
        }
        stateMachine.setTracer(tracer);
    }

    public void attachEventBus(EventBus eventBus) {
        if (eventBus == null || stateMachine == null) {
            return;
        }
        stateMachine.addCommitListener(entry -> {
            CloudEvent event = toCloudEvent(entry);
            if (event != null) {
                eventBus.publish(event);
            }
        });
    }

    private static CloudEvent toCloudEvent(ClusterEntry entry) {
        return switch (entry) {
            case ClusterEntry.WriteConfigVersion e ->
                new ClusterConfigChangedEvent(
                        e.version().version(),
                        e.version().parentVersion(),
                        e.version().mutator(),
                        ClusterConfigChangedEvent.ACTION_PATCH);
            case ClusterEntry.SetActiveConfigVersion e ->
                new ClusterConfigChangedEvent(e.version(), -1, e.setBy(), ClusterConfigChangedEvent.ACTION_ROLLBACK);
            // Other entries (SetClusterMeta, RotateSeed, AddMember, …) don't fan
            // out via EventBus today — subscribers can be added as the relevant
            // phases materialise.
            default -> null;
        };
    }

    public String clusterId() {
        return resolvedClusterId;
    }

    /**
     * This node's current Raft role. {@code FOLLOWER}/{@code LEADER} mean it is a voting member;
     * {@code LISTENER} means it joined and caught up but has not (yet) been promoted into the voting
     * set. Surfaced for bring-up diagnostics and join-lifecycle tests.
     */
    public org.apache.ratis.proto.RaftProtos.RaftPeerRole raftRole() throws IOException {
        return raft.currentRole();
    }

    /**
     * The {@link ControllerConfig} as seen post-startup. Currently equal to the
     * input — R7 (live-reload of cluster_config) will project the active Raft
     * config version on top of it.
     */
    public ControllerConfig effectiveConfig() {
        return effectiveConfig;
    }

    @Override
    public void close() throws IOException {
        if (reconciler != null) {
            reconciler.close();
        }
        if (raft != null) {
            raft.close();
        }
    }
}
