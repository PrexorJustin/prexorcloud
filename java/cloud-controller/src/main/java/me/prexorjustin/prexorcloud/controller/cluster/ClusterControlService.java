package me.prexorjustin.prexorcloud.controller.cluster;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlPlane;
import me.prexorjustin.prexorcloud.controller.cluster.raft.ClusterControlStateMachine;
import me.prexorjustin.prexorcloud.controller.cluster.raft.RaftBootstrap;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.config.ClusterJoinTemplate;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;

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
    private String resolvedClusterId;

    /** Effective config after first-boot seeding or v1.0 migration. May equal the input on restart. */
    private ControllerConfig effectiveConfig;

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

    public void start() throws IOException, TimeoutException {
        stateMachine = new ClusterControlStateMachine();
        raft = new RaftBootstrap(config.raft(), GROUP_ID, nodeId, stateMachine);
        raft.start();
        raft.awaitLeader(15_000);
        controlPlane = new ClusterControlPlane(raft, stateMachine);

        reconcileClusterIdentity();
        runV10MigrationIfNeeded();
    }

    /**
     * Either stamp a fresh {@link ClusterMeta} (first-ever boot) or verify
     * the existing one against {@code controller.yml}'s mirror. Mismatch refuses
     * to boot — same shape as the Phase 1 check, just sourced from Raft instead
     * of Mongo.
     */
    private void reconcileClusterIdentity() throws IOException {
        Optional<ClusterMeta> existing = controlPlane.getClusterMeta();
        String yamlClusterId = config.cluster() == null ? null : config.cluster().id();

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
        String clusterId = yamlClusterId != null ? yamlClusterId : UUID.randomUUID().toString();
        byte[] seed = new byte[32];
        random.nextBytes(seed);
        String seedB64 = Base64.getEncoder().encodeToString(seed);
        ClusterMeta seeded = new ClusterMeta(
                clusterId, seedB64, Instant.now(clock), ClusterMeta.CURRENT_SCHEMA_VERSION);
        controlPlane.setClusterMeta(seeded);
        resolvedClusterId = clusterId;
        logger.info(
                "Stamped fresh cluster.id={} into Raft state (yamlSource={})",
                clusterId,
                yamlClusterId != null ? "yes" : "no");
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
            logger.warn(
                    "No cluster-shared config detected in controller.yml — leaving cluster_config empty until"
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
        if (raft != null) {
            raft.close();
        }
    }
}
