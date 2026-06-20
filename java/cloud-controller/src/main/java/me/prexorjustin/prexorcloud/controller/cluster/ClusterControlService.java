package me.prexorjustin.prexorcloud.controller.cluster;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterPlane;
import me.prexorjustin.prexorcloud.controller.cluster.mongo.MongoClusterStore;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterFile;
import me.prexorjustin.prexorcloud.controller.cluster.state.ClusterMeta;
import me.prexorjustin.prexorcloud.controller.cluster.state.Member;
import me.prexorjustin.prexorcloud.controller.config.ClusterConfig;
import me.prexorjustin.prexorcloud.controller.config.ClusterJoinTemplate;
import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;
import me.prexorjustin.prexorcloud.security.ca.CertificateAuthority;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the cluster control-plane lifecycle on top of the Mongo cluster store: first-boot identity +
 * CA stamping, restart reconciliation against {@code controller.yml}, the Day-0 config seed from the
 * operator-supplied YAML, self-member registration, and the Day-N join (token redeem + CA adoption).
 *
 * <p>Cluster state is Mongo-authoritative. There is exactly one writer — the controller that holds the
 * {@link MongoLeaderElector} lease — and every read and write here goes through the Mongo
 * {@link ClusterPlane}. Controllers do not talk to each other directly; they coordinate purely through
 * the replica set (the leadership lease + change streams). Joining is a registration in Mongo, not a
 * peer-group handshake, so there is no consensus engine and no peer mTLS to bootstrap.
 *
 * <p>This is the integration seam {@code PrexorCloudBootstrap} calls into: construct it with the Mongo
 * store, start it, ask for the effective config, proceed.
 */
public final class ClusterControlService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ClusterControlService.class);

    private final ControllerConfig config;
    private final String nodeId;
    private final Clock clock;
    private final SecureRandom random;

    private final ClusterPlane plane;

    private String resolvedClusterId;
    // Set when this boot stamped a fresh cluster identity (Day-0 branch) while controller.yml already
    // carried a cluster.id — i.e. the Mongo cluster state was empty but the configured identity was
    // retained. Because Mongo is durable + replicated, that is the signature of a catastrophic data-loss
    // event (the cluster store was wiped under an existing install), not a virgin first-ever boot.
    private volatile boolean unsafeResetDetected;

    /** CA minted on the Day-0 path, reused by {@link #ensureClusterCa} so the on-disk + Mongo bytes match. */
    private CertificateAuthority day0InMemoryCa;

    /** Effective config after first-boot seeding. May equal the input on restart. */
    private ControllerConfig effectiveConfig;

    public ClusterControlService(ControllerConfig config, String nodeId, MongoClusterStore store) {
        this(config, nodeId, store, Clock.systemUTC(), new SecureRandom());
    }

    /** Test seam — fixed clock and seeded random for deterministic assertions. */
    public ClusterControlService(
            ControllerConfig config, String nodeId, MongoClusterStore store, Clock clock, SecureRandom random) {
        this.config = config;
        this.nodeId = nodeId;
        this.clock = clock;
        this.random = random;
        this.plane = new MongoClusterPlane(store);
        this.effectiveConfig = config;
    }

    /**
     * The routable host this controller advertises to peers + daemons. A service binds to {@code 0.0.0.0}
     * (all interfaces), but a member document must carry an address other nodes can actually dial — most
     * importantly the gRPC address a daemon redirects to on failover. When a bind host is the wildcard we
     * fall back to the configured cluster coordinate host ({@code raft.host}, the operator's routable IP).
     */
    private String routableHost(String bindHost) {
        if (bindHost == null || bindHost.isBlank() || "0.0.0.0".equals(bindHost) || "::".equals(bindHost)) {
            return config.raft().host();
        }
        return bindHost;
    }

    /** Address advertised as this member's primary bind coordinate in the cluster member list. */
    private String selfPrimaryAddress() {
        return config.raft().host() + ":" + config.raft().port();
    }

    private String selfRestAddress() {
        return routableHost(config.http().host()) + ":" + config.http().port();
    }

    private String selfGrpcAddress() {
        return routableHost(config.grpc().host()) + ":" + config.grpc().port();
    }

    /**
     * Day-0 bootstrap or restart of an existing member. The split is on whether Mongo already holds the
     * cluster identity ({@link ClusterMeta}):
     *
     * <ul>
     *   <li><b>Day-0 (no identity in Mongo, no on-disk material):</b> mint a fresh cluster CA + leaf cert
     *       in memory, persist them through {@code materials}, stamp the identity + CA + config seed into
     *       Mongo, and register self in {@code cluster_members}.</li>
     *   <li><b>Restart (identity present):</b> verify it against {@code controller.yml}; the CA is already
     *       in Mongo and on disk. Realign the on-disk material to the Mongo CA if they have drifted.</li>
     * </ul>
     */
    public void start(LocalClusterMaterials materials) throws IOException {
        boolean day0 = plane.getClusterMeta().isEmpty();
        if (day0 && materials != null && !materials.exists()) {
            day0InMemoryCa = mintAndPersistDay0Materials(materials);
        }
        reconcileClusterIdentity();
        ensureClusterCa();
        reconcileSelfClusterTls(materials);
        seedInitialClusterConfig();
        ensureSelfMember();
    }

    /** Tests-only entry: no on-disk TLS material. Identity / CA / config seed still run against Mongo. */
    public void start() throws IOException {
        start(null);
    }

    /**
     * Day-N join via Mongo registration. The joiner shares the cluster's replica set, so it validates +
     * single-use-redeems the join token directly against the Mongo cluster store, adopts the cluster CA
     * from Mongo and mints its own leaf cert, then registers itself in {@code cluster_members}. There is
     * no peer-group handshake and no consensus join — leadership is the Mongo lease, and reads + writes
     * go through the Mongo {@link ClusterPlane}.
     *
     * <p>Idempotency: the caller deletes the on-disk token only after this returns. A retry re-purges the
     * local material and re-runs; the single-use redeem makes a second pass fail fast with
     * {@code TOKEN_NOT_REDEEMABLE}.
     */
    public void startInJoinMode(String token, LocalClusterMaterials materials, JoinIdentity selfIdentity)
            throws IOException {
        materials.purge();

        // 1. Validate the token against the Mongo cluster identity.
        ClusterMeta meta = plane.getClusterMeta()
                .orElseThrow(
                        () -> new IOException("cluster identity not present in Mongo — cannot Mongo-register join"));
        JoinTokenCodec.Parsed parsed;
        try {
            parsed = JoinTokenCodec.parse(token);
        } catch (JoinTokenCodec.InvalidJoinToken e) {
            throw new IOException("malformed join token: " + e.getMessage(), e);
        }
        if (!JoinTokenCodec.verifyHmac(parsed, JoinTokenCodec.decodeSeed(meta.seedSecretBase64()))) {
            throw new IOException("join token HMAC does not match the cluster seed");
        }
        JoinTokenCodec.Payload payload = parsed.payload();
        if (!meta.clusterId().equals(payload.clusterId())) {
            throw new IOException(
                    "join token is for clusterId=" + payload.clusterId() + " but this cluster is " + meta.clusterId());
        }
        Instant now = Instant.now(clock);
        if (payload.expiresAt() != null && !payload.expiresAt().isAfter(now)) {
            throw new IOException("join token expired at " + payload.expiresAt());
        }

        // 2. Atomic single-use redeem (throws ClusterWriteConflict on replay/revoked/expired).
        plane.redeemJoinToken(payload.jti(), now, selfIdentity.raftAddr(), nodeId);

        // 3. Adopt the cluster CA from Mongo + mint our own leaf cert; persist locally.
        try {
            ClusterFile caCert = plane.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT)
                    .orElseThrow(() -> new IOException("cluster CA cert missing from the Mongo cluster store"));
            ClusterFile caKey = plane.getClusterFile(ClusterFile.KEY_CLUSTER_CA_KEY)
                    .orElseThrow(() -> new IOException("cluster CA key missing from the Mongo cluster store"));
            CertificateAuthority ca = CertificateAuthority.loadFromDer(caCert.bytes(), caKey.bytes());
            var leaf = ca.issueClusterPeerCertificate(nodeId, peerSans(), 365);
            materials.persist(
                    ca.certificate(), leaf.certificate(), leaf.keyPair().getPrivate());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("failed to mint a leaf cert from the cluster CA", e);
        }

        // 4. Adopt the cluster identity locally + register self in cluster_members. Advertise routable
        // REST + gRPC addresses (not the 0.0.0.0 bind host) so a daemon can redirect here on failover.
        resolvedClusterId = meta.clusterId();
        effectiveConfig = withClusterId(effectiveConfig, meta.clusterId());
        plane.addMember(
                new Member(nodeId, selfIdentity.raftAddr(), selfRestAddress(), selfGrpcAddress(), nodeId, now, now));

        logger.info(
                "Joined cluster {} as {} via Mongo registration — cluster state lives in Mongo, leadership via the"
                        + " Mongo lease",
                meta.clusterId(),
                nodeId);
    }

    private List<String> peerSans() {
        return Stream.of(nodeId, config.raft().host(), "127.0.0.1", "localhost")
                .distinct()
                .toList();
    }

    private CertificateAuthority mintAndPersistDay0Materials(LocalClusterMaterials materials) throws IOException {
        try {
            CertificateAuthority ca = CertificateAuthority.createInMemory("PrexorCloud Cluster CA", 3650);
            var leaf = ca.issueClusterPeerCertificate(nodeId, peerSans(), 365);
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

    /**
     * Ensure this node's {@link Member} record carries its current advertised addresses. Day-0 stamps a
     * fresh self member; a restart with unchanged addresses is a no-op; a restart whose primary, REST, or
     * advertised gRPC address changed (operator moved the bind host, or an older record still carries a
     * non-routable {@code 0.0.0.0} gRPC address) self-heals the record. The gRPC address matters most: it
     * is the target a daemon redirects to when this controller is the leader.
     */
    private void ensureSelfMember() {
        Optional<Member> existing = plane.listMembers().stream()
                .filter(m -> nodeId.equals(m.nodeId()))
                .findFirst();
        String wantPrimary = selfPrimaryAddress();
        String wantRest = selfRestAddress();
        String wantGrpc = selfGrpcAddress();
        if (existing.isPresent()) {
            Member current = existing.get();
            if (wantPrimary.equals(current.raftAddr())
                    && wantRest.equals(current.restAddr())
                    && wantGrpc.equals(current.gRPCAddr())) {
                return; // addresses unchanged — nothing to reconcile
            }
            try {
                Member healed = new Member(
                        current.nodeId(),
                        wantPrimary,
                        wantRest,
                        wantGrpc,
                        current.label(),
                        current.joinedAt(),
                        Instant.now(clock));
                plane.addMember(healed);
                logger.info(
                        "Self-healed member {} addresses (primary={}, rest={}, grpc={})",
                        nodeId,
                        wantPrimary,
                        wantRest,
                        wantGrpc);
            } catch (IOException e) {
                logger.warn("could not reconcile self member addresses: {}", e.getMessage());
            }
            return;
        }
        try {
            Member self =
                    new Member(nodeId, wantPrimary, wantRest, wantGrpc, nodeId, Instant.now(clock), Instant.now(clock));
            plane.addMember(self);
            logger.info("Stamped Day-0 self member {} at {}", nodeId, wantPrimary);
        } catch (IOException e) {
            logger.warn("could not add self to cluster members: {}", e.getMessage());
        }
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

    /** Identity advertised by the joiner: primary + REST + gRPC bind addresses for the cluster member list. */
    public record JoinIdentity(String raftAddr, String restAddr, String grpcAddr) {}

    /**
     * Either stamp a fresh {@link ClusterMeta} (first-ever boot) or verify the existing one against
     * {@code controller.yml}'s mirror. A mismatch refuses to boot. Because Mongo is linearizable, an
     * absent identity means a genuine Day-0 (or a catastrophic store wipe) — there is no replication lag
     * to wait out.
     */
    private void reconcileClusterIdentity() throws IOException {
        Optional<ClusterMeta> existing = plane.getClusterMeta();
        String yamlClusterId =
                config.cluster() == null ? null : config.cluster().id();

        if (existing.isPresent()) {
            ClusterMeta meta = existing.get();
            if (yamlClusterId != null && !yamlClusterId.equals(meta.clusterId())) {
                throw new IllegalStateException("Configured cluster.id=" + yamlClusterId
                        + " but the Mongo cluster store holds cluster.id=" + meta.clusterId()
                        + ". Either point this controller at the right cluster, or remove cluster.id from"
                        + " controller.yml to adopt the store's existing id.");
            }
            resolvedClusterId = meta.clusterId();
            logger.info("Cluster identity verified (cluster.id={})", resolvedClusterId);
            return;
        }

        // No cluster identity in Mongo: first-ever boot (or a catastrophic store wipe). Generate a fresh
        // identity AND seed secret.
        String clusterId =
                yamlClusterId != null ? yamlClusterId : UUID.randomUUID().toString();
        byte[] seed = new byte[32];
        random.nextBytes(seed);
        String seedB64 = Base64.getEncoder().encodeToString(seed);
        ClusterMeta seeded =
                new ClusterMeta(clusterId, seedB64, Instant.now(clock), ClusterMeta.CURRENT_SCHEMA_VERSION);
        plane.setClusterMeta(seeded);
        resolvedClusterId = clusterId;
        logger.info(
                "Stamped fresh cluster.id={} into the Mongo cluster store (yamlSource={})",
                clusterId,
                yamlClusterId != null ? "yes" : "no");
        if (yamlClusterId != null) {
            // Empty Mongo cluster state but a retained configured cluster.id => the store was wiped under
            // an existing install. Flag it so bootstrap records the cluster.recovery.unsafe-reset audit
            // event. The clusterId is preserved; the cluster CA, seed secret, and config history are
            // regenerated (operators must re-issue join tokens — see the runbook).
            unsafeResetDetected = true;
            logger.warn(
                    "Catastrophic reset detected: the Mongo cluster store was empty but cluster.id={} is"
                            + " configured. Re-formed cluster identity; CA + seed + config history were"
                            + " regenerated. Rotate the seed and re-issue join tokens"
                            + " (docs/runbooks/recover-cluster.md).",
                    clusterId);
        }
    }

    /**
     * True when this boot took the Day-0 stamping path but {@code controller.yml} already carried a
     * {@code cluster.id} — the signature of a catastrophic store wipe under an existing install.
     * Bootstrap reads this to write the {@code cluster.recovery.unsafe-reset} audit event.
     */
    public boolean unsafeResetDetected() {
        return unsafeResetDetected;
    }

    /**
     * Day-0: mint the cluster CA in-process and stamp its cert + private key into the Mongo cluster store
     * as cluster files. Joiners adopt it back from there; there is no on-disk CA keystore beyond the
     * leaf material. Idempotent: a CA cert already in Mongo means we did this and just log the fingerprint.
     */
    private void ensureClusterCa() throws IOException {
        if (plane.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT).isPresent()) {
            return;
        }
        try {
            CertificateAuthority ca = day0InMemoryCa != null
                    ? day0InMemoryCa
                    : CertificateAuthority.createInMemory("PrexorCloud Cluster CA", 3650);
            plane.writeClusterFile(
                    ClusterFile.KEY_CLUSTER_CA_CERT, ca.certificate().getEncoded());
            plane.writeClusterFile(
                    ClusterFile.KEY_CLUSTER_CA_KEY, ca.keyPair().getPrivate().getEncoded());
            logger.info("Stamped cluster CA (fingerprint={}) into the Mongo cluster store", ca.fingerprint());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to generate cluster CA", e);
        }
    }

    /**
     * Realign this controller's on-disk leaf + trust anchor to the authoritative cluster CA in Mongo. A
     * catastrophic reset regenerates the CA in Mongo but leaves this controller's stale on-disk material;
     * re-issue the leaf from the Mongo CA so it presents consistent material. Best-effort: on any failure
     * log and continue on the existing on-disk material.
     */
    private void reconcileSelfClusterTls(LocalClusterMaterials materials) {
        if (materials == null || !materials.exists()) {
            return; // no on-disk material to realign (no-TLS test path / Day-0 mints consistent material)
        }
        try {
            Optional<ClusterFile> caCert = plane.getClusterFile(ClusterFile.KEY_CLUSTER_CA_CERT);
            Optional<ClusterFile> caKey = plane.getClusterFile(ClusterFile.KEY_CLUSTER_CA_KEY);
            if (caCert.isEmpty() || caKey.isEmpty()) {
                return; // no authoritative CA in Mongo to realign against
            }
            byte[] mongoCaDer = caCert.get().bytes();
            LocalClusterMaterials.Loaded onDisk = materials.load();
            if (Arrays.equals(onDisk.caCert().getEncoded(), mongoCaDer)) {
                return; // on-disk trust already matches the Mongo CA — nothing to do
            }
            logger.warn("Cluster CA split detected: this controller's on-disk CA does not match the authoritative"
                    + " Mongo cluster CA. Re-issuing the local leaf from the Mongo CA.");
            CertificateAuthority ca =
                    CertificateAuthority.loadFromDer(mongoCaDer, caKey.get().bytes());
            var leaf = ca.issueClusterPeerCertificate(nodeId, peerSans(), 365);
            materials.persist(
                    ca.certificate(), leaf.certificate(), leaf.keyPair().getPrivate());
            logger.info("Cluster CA realigned to the Mongo CA (fingerprint={}).", ca.fingerprint());
        } catch (Exception e) {
            logger.warn(
                    "Self cluster-TLS reconcile failed ({}) — continuing on the existing on-disk material.",
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Day-0 cluster has no {@code cluster_config} yet. Build the initial version from the cluster-shared
     * subset of the operator-supplied {@code controller.yml} and write it as version 1. Idempotent on
     * restart: if {@code activeConfigVersion > 0} this does nothing.
     */
    private void seedInitialClusterConfig() throws IOException {
        if (plane.getActiveConfigVersion() > 0) {
            return;
        }
        Map<String, Object> initial = ClusterJoinTemplate.buildSharedMap(config);
        if (initial.isEmpty()) {
            logger.warn("No cluster-shared config detected in controller.yml — leaving cluster_config empty until"
                    + " the wizard or first PATCH writes one.");
            return;
        }
        int newVersion = plane.proposeConfigPatch(0, "day0-seed", initial, "Seeded from controller.yml on first boot");
        logger.info(
                "Seeded cluster-shared config from controller.yml into Mongo as version {} ({} top-level keys)",
                newVersion,
                initial.size());
    }

    /** The cluster control-plane store (reads + writes), backed by Mongo. */
    public ClusterPlane clusterPlane() {
        return plane;
    }

    /** The read-only projection of {@link #clusterPlane()} — for leader resolution + the cluster REST reads. */
    public ClusterReadView clusterReadView() {
        return plane;
    }

    public String clusterId() {
        return resolvedClusterId;
    }

    /**
     * The {@link ControllerConfig} as seen post-startup. Equal to the input on a founder restart; on a
     * join it carries the adopted {@code cluster.id}.
     */
    public ControllerConfig effectiveConfig() {
        return effectiveConfig;
    }

    @Override
    public void close() {
        // Cluster state lives in Mongo, owned by the shared client — nothing controller-local to close.
    }
}
