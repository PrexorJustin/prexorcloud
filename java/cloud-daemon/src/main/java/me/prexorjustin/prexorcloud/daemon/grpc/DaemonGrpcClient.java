package me.prexorjustin.prexorcloud.daemon.grpc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import me.prexorjustin.prexorcloud.common.util.VersionInfo;
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

    // host/port are mutable: a HandshakeAck from a follower redirects the daemon to the leader
    // (Phase 3), swapping the dial target before the reconnect path re-dials. Volatile so the
    // reconnect-scheduler thread sees the new target.
    private volatile String host;
    private volatile int port;
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

    private ReconnectManager reconnectManager;
    private IntSupplier instanceCountSupplier = () -> 0;
    private Supplier<List<Integer>> usedPortsSupplier = List::of;
    private Supplier<List<RunningInstance>> runningInstancesSupplier = List::of;

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
        this.host = host;
        this.port = port;
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

    public void connect() {
        connectLock.lock();
        try {
            if (state == State.CONNECTING) {
                logger.debug("Already connecting, skipping duplicate connect()");
                return;
            }
            state = State.CONNECTING;
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

            logger.info("Connecting to controller at {}:{}", host, port);

            // Connect via an explicit InetSocketAddress so gRPC uses its direct-address path and
            // never consults the NameResolver registry. In the shaded jar the only registered
            // NameResolverProvider is the unix-domain-socket one, so the default scheme resolves
            // to "unix:///host:port" and Netty's TCP transport rejects it. A direct address skips
            // name resolution entirely. Authority stays "host:port", identical to forAddress(host, port).
            var builder = NettyChannelBuilder.forAddress(new java.net.InetSocketAddress(host, port))
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

    private void handleDisconnect() {
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
            stream.onNext(message);
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
        this.controllerApiUrl = "http://" + host + ":" + apiPort;
        logger.debug("Controller API URL resolved: {}", controllerApiUrl);
    }

    /** Returns the controller REST API base URL (e.g. "http://10.0.0.1:8080"). */
    public String controllerApiUrl() {
        return controllerApiUrl;
    }

    /** Returns the controller host the daemon is connected to (or being redirected to). */
    public String controllerHost() {
        return host;
    }

    /** Returns the controller gRPC port the daemon is connected to (or being redirected to). */
    public int controllerPort() {
        return port;
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
        if (newHost.equals(host) && newPort == port) {
            return false; // already targeting this controller — settle normally
        }
        logger.info("Redirecting daemon from {}:{} to leader at {}:{}", host, port, newHost, newPort);
        this.host = newHost;
        this.port = newPort;
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
}
