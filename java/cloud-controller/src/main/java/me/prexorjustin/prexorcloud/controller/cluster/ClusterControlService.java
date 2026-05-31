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
        } else {
            CertificateAuthority ca = mintAndPersistDay0Materials(materials);
            LocalClusterMaterials.Loaded loaded = materials.load();
            GrpcTlsConfig tls = buildTls(loaded);
            raft.start(tls);
            // Stash the CA so ensureClusterCa() doesn't have to re-mint when we get
            // there. ensureClusterCa() checks raft state first; we write the bytes in
            // a moment.
            day0InMemoryCa = ca;
        }
        raft.awaitLeader(15_000);
        controlPlane = new ClusterControlPlane(raft, stateMachine);

        reconcileClusterIdentity();
        ensureClusterCa();
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
        raft.awaitLeader(15_000);
        controlPlane = new ClusterControlPlane(raft, stateMachine);

        reconcileClusterIdentity();
        ensureClusterCa();
        runV10MigrationIfNeeded();
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
                "Joined cluster {} as {} with {} existing peer(s); local TLS material persisted to {}",
                joinResult.clusterId(),
                nodeId,
                joinResult.existingPeers().size(),
                materials.directory());
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
     * Day-0 founder: write its own {@link Member} record so the membership reconciler has
     * something to set as the Ratis group on first boot. Idempotent on restart — the SM's
     * AddMember handler rejects duplicates, which surfaces as a {@code ClusterWriteConflict}
     * we swallow.
     */
    private void ensureSelfMember() {
        boolean alreadyPresent = controlPlane.listMembers().stream().anyMatch(m -> nodeId.equals(m.nodeId()));
        if (alreadyPresent) {
            return;
        }
        try {
            Member self = new Member(nodeId, selfRaftAddress(), "", "", nodeId, Instant.now(clock), Instant.now(clock));
            controlPlane.addMember(self);
            logger.info("Stamped Day-0 self member {} at {}", nodeId, selfRaftAddress());
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
    private void reconcileClusterIdentity() throws IOException {
        Optional<ClusterMeta> existing = controlPlane.getClusterMeta();
        String yamlClusterId =
                config.cluster() == null ? null : config.cluster().id();

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

        // No cluster meta in Raft yet. First-ever boot (or first v1.1 boot post-migration).
        // Generate a fresh identity AND a fresh seed secret.
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
    public void attachEventBus(EventBus eventBus) {
        if (eventBus == null || stateMachine == null) {
            return;
        }
        stateMachine.setCommitListener(entry -> {
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
