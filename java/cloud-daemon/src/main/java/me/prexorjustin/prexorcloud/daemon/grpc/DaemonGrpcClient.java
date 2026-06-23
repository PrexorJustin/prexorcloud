package me.prexorjustin.prexorcloud.daemon.grpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import me.prexorjustin.prexorcloud.common.util.VersionInfo;
import me.prexorjustin.prexorcloud.daemon.config.ControllerEndpoint;
import me.prexorjustin.prexorcloud.daemon.resource.HostInfoCollector;
import me.prexorjustin.prexorcloud.daemon.resource.ResourceMonitor;
import me.prexorjustin.prexorcloud.protocol.*;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC client that connects to the controller, performs handshake, sends
 * periodic NodeStatus updates, and processes controller commands.
 * <p>
 * Connection lifecycle: IDLE → CONNECTING → CONNECTED → DISCONNECTED →
 * CONNECTING → ...
 * <p>
 * Reconnection is event-driven: onError/onCompleted trigger the
 * {@link ReconnectManager} rather than a polling loop.
 */
public final class DaemonGrpcClient {

    private static final Logger logger = LoggerFactory.getLogger(DaemonGrpcClient.class);

    enum State {
        IDLE,
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    // The seed list of controllers, shuffled once at construction so a fleet restart doesn't herd
    // onto candidates[0] before the leader redirect kicks in. Never empty. Volatile + mutated only
    // under connectLock: the controller advertises its live members on every handshake, which the
    // daemon merges in (mergeAdvertisedControllers) so the list self-heals as membership changes.
    private volatile List<ControllerEndpoint> candidates;
    // The current dial target. A single immutable value (not a separate host+port pair) so connect()
    // reads one atomic snapshot — no torn read of a host from one candidate with a port from another.
    // Mutated by candidate rotation (inside connect(), under connectLock) and by a leader redirect.
    private volatile ControllerEndpoint target;
    // Position in {@code candidates}; guarded by connectLock (only read/written inside connect()).
    private int candidateIndex;
    // Set by handleDisconnect() when an attempt never reached a HandshakeAck; read+cleared by the next
    // connect() under connectLock, where it advances to the next seed. Volatile to cross the
    // gRPC-executor → reconnect-scheduler thread hop.
    private volatile boolean advanceOnNextConnect;
    private final String nodeId;
    private final String advertiseAddress;
    private final long totalMemoryMb;
    private final Map<String, String> labels;
    private final SslContext sslContext;
    private final ResourceMonitor resourceMonitor;
    private final MessageDispatcher dispatcher;

    private final ReentrantLock connectLock = new ReentrantLock();
    private volatile State state = State.IDLE;
    private ManagedChannel channel;
    // Monotonic per-connection id. Each connect() bumps it and stamps the new stream's observer; when a
    // later connect() tears down a still-open channel, that stale observer's onError/onCompleted is
    // ignored so our own teardown does not schedule a phantom reconnect (which otherwise kills the
    // freshly-connected channel in a ~1/s loop after a leader redirect).
    private volatile long connectGeneration;
    private volatile StreamObserver<DaemonMessage> requestStream;
    private ScheduledExecutorService statusScheduler;
    private volatile String controllerApiUrl = "";
    private volatile int controllerApiPort = 0;

    // Crash reports are buffered when undeliverable and replayed at-least-once on reconnect, so a
    // crash during a disconnect / leader-redirect window is still recorded (#12 guarantee).
    private static final int MAX_BUFFERED_CRASH_REPORTS = 64;
    private final CrashReportBuffer crashBuffer = new CrashReportBuffer(MAX_BUFFERED_CRASH_REPORTS);

    // The controller's last-advertised live member set (guarded by connectLock). When it changes the
    // learnedControllersListener is fired so the set can be persisted — a fresh, bounded view, not the
    // monotonic in-memory union below.
    private java.util.Set<ControllerEndpoint> lastAdvertised = java.util.Set.of();
    private volatile java.util.function.Consumer<List<ControllerEndpoint>> learnedControllersListener;

    private ReconnectManager reconnectManager;
    private IntSupplier instanceCountSupplier = () -> 0;
    private Supplier<List<Integer>> usedPortsSupplier = List::of;
    private Supplier<List<RunningInstance>> runningInstancesSupplier = List::of;

    /** Single-endpoint convenience constructor (back-compat with pre-seed-list callers and tests). */
    public DaemonGrpcClient(
            String host,
            int port,
            String nodeId,
            String advertiseAddress,
            long totalMemoryMb,
            Map<String, String> labels,
            SslContext sslContext,
            ResourceMonitor resourceMonitor,
            MessageDispatcher dispatcher) {
        this(
                List.of(new ControllerEndpoint(host, port)),
                nodeId,
                advertiseAddress,
                totalMemoryMb,
                labels,
                sslContext,
                resourceMonitor,
                dispatcher);
    }

    public DaemonGrpcClient(
            List<ControllerEndpoint> candidates,
            String nodeId,
            String advertiseAddress,
            long totalMemoryMb,
            Map<String, String> labels,
            SslContext sslContext,
            ResourceMonitor resourceMonitor,
            MessageDispatcher dispatcher) {
        this(
                true,
                candidates,
                nodeId,
                advertiseAddress,
                totalMemoryMb,
                labels,
                sslContext,
                resourceMonitor,
                dispatcher);
    }

    DaemonGrpcClient(
            boolean shuffle,
            List<ControllerEndpoint> candidates,
            String nodeId,
            String advertiseAddress,
            long totalMemoryMb,
            Map<String, String> labels,
            SslContext sslContext,
            ResourceMonitor resourceMonitor,
            MessageDispatcher dispatcher) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("at least one controller endpoint is required");
        }
        if (shuffle) {
            var shuffled = new ArrayList<>(candidates);
            Collections.shuffle(shuffled);
            this.candidates = List.copyOf(shuffled);
        } else {
            this.candidates = List.copyOf(candidates);
        }
        this.candidateIndex = 0;
        this.target = this.candidates.get(0);
        this.nodeId = nodeId;
        this.advertiseAddress = advertiseAddress != null ? advertiseAddress : "";
        this.totalMemoryMb = totalMemoryMb;
        this.labels = labels != null ? labels : Map.of();
        this.sslContext = sslContext;
        this.resourceMonitor = resourceMonitor;
        this.dispatcher = dispatcher;
    }

    public void setReconnectManager(ReconnectManager reconnectManager) {
        this.reconnectManager = reconnectManager;
    }

    /**
     * Register a listener fired with the cluster's advertised controller set whenever it changes, so it
     * can be persisted for restart resilience. Receives the fresh advertised view (bounded), not the
     * in-memory union of every controller ever seen.
     */
    public void setLearnedControllersListener(java.util.function.Consumer<List<ControllerEndpoint>> listener) {
        this.learnedControllersListener = listener;
    }

    public void connect() {
        connectLock.lock();
        try {
            if (state == State.CONNECTING) {
                logger.debug("Already connecting, skipping duplicate connect()");
                return;
            }
            state = State.CONNECTING;
            final ControllerEndpoint dial = selectTarget();
            final long gen = ++connectGeneration;

            // Shut down any existing channel to avoid leaking resources
            if (channel != null) {
                try {
                    channel.shutdownNow();
                    channel.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }

            logger.info("Connecting to controller at {}", dial);

            // Connect via an explicit InetSocketAddress so gRPC uses its direct-address path and
            // never consults the NameResolver registry. In the shaded jar the only registered
            // NameResolverProvider is the unix-domain-socket one, so the default scheme resolves
            // to "unix:///host:port" and Netty's TCP transport rejects it. A direct address skips
            // name resolution entirely. Authority stays "host:port", identical to forAddress(host, port).
            var builder = NettyChannelBuilder.forAddress(new java.net.InetSocketAddress(dial.host(), dial.port()))
                    .maxInboundMessageSize(ProtocolConstants.MAX_MESSAGE_SIZE)
                    .keepAliveTime(30, TimeUnit.SECONDS)
                    .keepAliveTimeout(10, TimeUnit.SECONDS)
                    .idleTimeout(300, TimeUnit.SECONDS);
            if (sslContext != null) {
                builder.sslContext(sslContext);
            } else {
                builder.usePlaintext();
            }
            channel = builder.build();

            var stub = DaemonServiceGrpc.newStub(channel);

            requestStream = stub.connect(new StreamObserver<>() {

                @Override
                public void onNext(ControllerMessage message) {
                    if (gen != connectGeneration) {
                        // Superseded channel (e.g. after a leader redirect / rotation): drop any late
                        // message — including a stale HandshakeAck that would otherwise mark the new
                        // attempt CONNECTED and suppress candidate rotation, pinning a dead target.
                        return;
                    }
                    dispatcher.dispatch(message);
                }

                @Override
                public void onError(Throwable t) {
                    if (gen != connectGeneration) {
                        // This channel was superseded by a newer connect() (e.g. a leader redirect) that
                        // tore it down on purpose — don't treat our own teardown as a disconnect.
                        return;
                    }
                    logger.warn("Connection error: {}", t.getMessage());
                    handleDisconnect();
                }

                @Override
                public void onCompleted() {
                    if (gen != connectGeneration) {
                        return;
                    }
                    logger.info("Connection closed by controller");
                    handleDisconnect();
                }
            });

            // Send handshake with currently running instances for state reconciliation
            requestStream.onNext(DaemonMessage.newBuilder()
                    .setHandshake(Handshake.newBuilder()
                            .setNodeId(nodeId)
                            .setAdvertiseAddress(advertiseAddress)
                            .setVersion(VersionInfo.get().version())
                            .setTotalMemoryMb(totalMemoryMb)
                            .setAvailableCpus(Runtime.getRuntime().availableProcessors())
                            .putAllLabels(labels)
                            .addAllRunningInstances(runningInstancesSupplier.get())
                            .setHostInfo(HostInfoCollector.collect())
                            .setProtocolVersion(1))
                    .build());

            logger.debug("Handshake sent to controller, waiting for ACK");
        } finally {
            connectLock.unlock();
        }
    }

    /**
     * Pick the target for the next dial: advance to the next seed if the previous attempt never reached
     * a HandshakeAck. A leader redirect sets an explicit target and clears the advance flag, so a
     * redirect never rotates past the leader. Must be called holding {@link #connectLock} (connect()
     * does); rotation mutates {@code candidateIndex}/{@code target} from this single place.
     */
    ControllerEndpoint selectTarget() {
        if (advanceOnNextConnect) {
            advanceOnNextConnect = false;
            if (candidates.size() > 1) {
                candidateIndex = (candidateIndex + 1) % candidates.size();
                target = candidates.get(candidateIndex);
            }
        }
        return target;
    }

    /**
     * Merge controller addresses the cluster advertised in a HandshakeAck into the dial candidate list
     * so the seed list self-heals as membership changes. Existing candidates (config seeds + already
     * merged) keep their order; new, routable, non-duplicate addresses are appended. The list is never
     * re-shuffled; {@code candidateIndex} is re-anchored on the current target so rotation continues
     * from where it is. The current dial {@code target} is untouched.
     */
    void mergeAdvertisedControllers(List<String> addrs) {
        if (addrs == null || addrs.isEmpty()) {
            return;
        }
        var advertised = new LinkedHashSet<ControllerEndpoint>();
        for (String addr : addrs) {
            ControllerEndpoint ep = ControllerEndpoint.parse(addr, target.port());
            if (ep != null && !ep.isLoopbackOrWildcard()) {
                advertised.add(ep);
            }
        }
        if (advertised.isEmpty()) {
            return;
        }

        List<ControllerEndpoint> toPersist = null;
        connectLock.lock();
        try {
            // Union into the rotation candidates (existing order first), for this process's lifetime.
            var merged = new LinkedHashSet<>(candidates);
            if (merged.addAll(advertised)) {
                var updated = List.copyOf(merged);
                int idx = updated.indexOf(target);
                candidates = updated;
                candidateIndex = idx >= 0 ? idx : 0;
                logger.debug("Merged advertised controllers; seed list now {}", updated);
            }
            // Persist the controller's current live view (fresh + bounded) only when it changes.
            if (!advertised.equals(lastAdvertised)) {
                lastAdvertised = advertised;
                toPersist = List.copyOf(advertised);
            }
        } finally {
            connectLock.unlock();
        }
        // Notify outside the lock — the listener does disk I/O and must not block connect()/rotation.
        if (toPersist != null && learnedControllersListener != null) {
            learnedControllersListener.accept(toPersist);
        }
    }

    private void handleDisconnect() {
        // If this attempt never reached CONNECTED (no HandshakeAck) the target is unreachable or isn't
        // a controller — advance to the next seed on the next connect(). Read state BEFORE clearing it.
        // A connection that WAS established and then dropped keeps the same target for one retry (it may
        // be a transient blip / the leader); if that retry also fails to ack, this fires and rotates.
        if (state != State.CONNECTED) {
            advanceOnNextConnect = true;
        }
        requestStream = null;
        state = State.DISCONNECTED;
        stopStatusReporting();
        if (reconnectManager != null) {
            reconnectManager.scheduleReconnect();
        }
    }

    /**
     * Called by the dispatcher when the HandshakeAck arrives. Only now do we
     * consider the connection fully established.
     */
    public void onHandshakeAckReceived() {
        state = State.CONNECTED;
        if (reconnectManager != null) {
            reconnectManager.onConnected();
        }
        startStatusReporting();
        replayBufferedCrashReports();
        logger.info("Connected to controller (handshake acknowledged)");
    }

    public void sendMessage(DaemonMessage message) {
        trySend(message);
    }

    /** Send a message, returning whether it was actually written to the stream. */
    private boolean trySend(DaemonMessage message) {
        var stream = requestStream;
        if (stream == null || state != State.CONNECTED) {
            return false;
        }
        try {
            // gRPC StreamObserver is not thread-safe for concurrent onNext. Console capture (a
            // per-instance virtual thread), heartbeat/pong, instance-status, and crash reports all
            // send through this single chokepoint from different threads; concurrent onNext interleaves
            // and corrupts the outbound frame ("Failed to stream message" -> CANCELLED), tearing down
            // the control stream. A stop's shutdown-console burst reliably triggers it, which then
            // marks every co-located instance CRASHED on the controller. Serialize per stream — the
            // daemon-side mirror of the controller's NodeSession.send fix. Synchronize on the stream
            // object so a reconnect's fresh stream gets its own monitor.
            synchronized (stream) {
                stream.onNext(message);
            }
            return true;
        } catch (StatusRuntimeException e) {
            logger.warn("Failed to send message ({}), marking disconnected", e.getStatus());
            handleDisconnect();
            return false;
        }
    }

    public void sendPong(long sequence) {
        sendMessage(DaemonMessage.newBuilder()
                .setPong(Pong.newBuilder().setSequence(sequence))
                .build());
    }

    public void sendInstanceStatus(InstanceStatusUpdate status) {
        sendMessage(DaemonMessage.newBuilder().setInstanceStatus(status).build());
    }

    public void sendConsoleOutput(String instanceId, String line) {
        sendMessage(DaemonMessage.newBuilder()
                .setConsoleOutput(ConsoleOutput.newBuilder()
                        .setInstanceId(instanceId)
                        .setLine(line)
                        .setTimestampMs(System.currentTimeMillis()))
                .build());
    }

    public void sendCrashReport(CrashReport report) {
        if (trySend(DaemonMessage.newBuilder().setCrashReport(report).build())) {
            return;
        }
        // Not connected (or the send failed) — buffer for at-least-once replay on reconnect so a
        // crash during a disconnect / leader-redirect window is not lost (#12 guarantee).
        crashBuffer.add(report);
        logger.info("Buffered undeliverable crash report (will replay on reconnect; {} pending)", crashBuffer.size());
    }

    /** Replay any crash reports buffered while disconnected. Called once the handshake re-establishes. */
    private void replayBufferedCrashReports() {
        var pending = crashBuffer.drainAll();
        if (pending.isEmpty()) {
            return;
        }
        logger.info("Replaying {} buffered crash report(s) after reconnect", pending.size());
        for (CrashReport report : pending) {
            if (!trySend(DaemonMessage.newBuilder().setCrashReport(report).build())) {
                crashBuffer.add(report); // stream dropped again mid-replay — keep for the next reconnect
            }
        }
    }

    /** Number of crash reports currently buffered for replay (observability / tests). */
    public int bufferedCrashReportCount() {
        return crashBuffer.size();
    }

    public void setProcessInfo(
            IntSupplier instanceCount,
            Supplier<List<Integer>> usedPorts,
            Supplier<List<RunningInstance>> runningInstances) {
        this.instanceCountSupplier = instanceCount;
        this.usedPortsSupplier = usedPorts;
        this.runningInstancesSupplier = runningInstances;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    /** Called by the dispatcher when the HandshakeAck arrives with the API port. */
    public void setControllerApiPort(int apiPort) {
        this.controllerApiPort = apiPort;
        this.controllerApiUrl = "http://" + target.host() + ":" + apiPort;
        logger.debug("Controller API URL resolved: {}", controllerApiUrl);
    }

    /** Returns the controller REST API base URL (e.g. "http://10.0.0.1:8080"). */
    public String controllerApiUrl() {
        return controllerApiUrl;
    }

    /** Returns the controller host the daemon is connected to (or being redirected to). */
    public String controllerHost() {
        return target.host();
    }

    /** Returns the controller gRPC port the daemon is connected to (or being redirected to). */
    public int controllerPort() {
        return target.port();
    }

    /**
     * Redirect this daemon to the leader at {@code addr} ("host:port"), as instructed by a follower's
     * {@code HandshakeAck} (Phase 3). Swaps the dial target and forces a reconnect through the existing
     * reconnect path — {@link #connect()} tears down the current (follower) channel and dials the new
     * target. Returns {@code true} if a redirect was initiated, or {@code false} if the address is
     * invalid or already the current target (the caller should then settle the handshake normally).
     */
    public boolean redirectToLeader(String addr) {
        if (addr == null) {
            return false;
        }
        int colon = addr.lastIndexOf(':');
        if (colon <= 0 || colon == addr.length() - 1) {
            logger.warn("Ignoring invalid leader redirect address: '{}'", addr);
            return false;
        }
        String newHost = addr.substring(0, colon);
        int newPort;
        try {
            newPort = Integer.parseInt(addr.substring(colon + 1));
        } catch (NumberFormatException e) {
            logger.warn("Ignoring leader redirect address with non-numeric port: '{}'", addr);
            return false;
        }
        if (newPort <= 0 || newPort > 65535) {
            logger.warn("Ignoring leader redirect address with out-of-range port: '{}'", addr);
            return false;
        }
        ControllerEndpoint current = target;
        if (newHost.equals(current.host()) && newPort == current.port()) {
            return false; // already targeting this controller — settle normally
        }
        logger.info("Redirecting daemon from {} to leader at {}:{}", current, newHost, newPort);
        this.target = new ControllerEndpoint(newHost, newPort);
        // An explicit redirect target must not be rotated past on the next connect(): clear any pending
        // rotation the prior disconnect may have set.
        this.advanceOnNextConnect = false;
        // Drop out of CONNECTING (set by the connect() that opened the follower stream) so the reconnect
        // path's connect() proceeds rather than short-circuiting; it then dials the new target.
        state = State.DISCONNECTED;
        stopStatusReporting();
        if (reconnectManager != null) {
            reconnectManager.scheduleReconnect();
        }
        return true;
    }

    /** Returns the controller REST API port resolved from the handshake (0 if unknown). */
    public int controllerApiPort() {
        return controllerApiPort;
    }

    public void disconnect() {
        state = State.DISCONNECTED;
        stopStatusReporting();
        // Shut down the channel first — shutdownNow() cancels all in-flight RPCs
        // without loading new classes, which avoids NoClassDefFoundError during
        // JVM shutdown hooks when the classloader may no longer resolve classes.
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    private void startStatusReporting() {
        // Idempotent: a reconnect can re-enter onHandshakeAckReceived() without an intervening
        // disconnect, which would otherwise orphan the previous scheduler thread.
        stopStatusReporting();
        statusScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "status-reporter");
            t.setDaemon(true);
            return t;
        });
        statusScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        NodeStatus status =
                                resourceMonitor.sample(instanceCountSupplier.getAsInt(), usedPortsSupplier.get());
                        sendMessage(
                                DaemonMessage.newBuilder().setNodeStatus(status).build());
                    } catch (Exception e) {
                        logger.warn("Failed to send status: {}", e.getMessage());
                    }
                },
                5,
                30,
                TimeUnit.SECONDS);
    }

    private void stopStatusReporting() {
        if (statusScheduler != null) {
            statusScheduler.shutdownNow();
            statusScheduler = null;
        }
    }

    // --- Test seams (package-private): exercise the rotation policy without real sockets ---

    /** Build a client over {@code candidates} in deterministic (un-shuffled) order for assertions. */
    static DaemonGrpcClient unshuffledForTest(List<ControllerEndpoint> candidates) {
        return new DaemonGrpcClient(false, candidates, "node-test", "", 1024, Map.of(), null, null, null);
    }

    /** Run the disconnect bookkeeping (no reconnect manager → no reschedule) as a stream drop would. */
    void simulateDisconnectForTest() {
        handleDisconnect();
    }

    /** Simulate a successful handshake's effect on the rotation decision (CONNECTED), without I/O. */
    void markConnectedForTest() {
        state = State.CONNECTED;
    }

    boolean advancePendingForTest() {
        return advanceOnNextConnect;
    }
}
