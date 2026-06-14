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

    private final String host;
    private final int port;
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
    private volatile StreamObserver<DaemonMessage> requestStream;
    private ScheduledExecutorService statusScheduler;
    private volatile String controllerApiUrl = "";
    private volatile int controllerApiPort = 0;

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
                    logger.warn("Connection error: {}", t.getMessage());
                    handleDisconnect();
                }

                @Override
                public void onCompleted() {
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
        logger.info("Connected to controller (handshake acknowledged)");
    }

    public void sendMessage(DaemonMessage message) {
        var stream = requestStream;
        if (stream != null && state == State.CONNECTED) {
            try {
                stream.onNext(message);
            } catch (StatusRuntimeException e) {
                logger.warn("Failed to send message ({}), marking disconnected", e.getStatus());
                handleDisconnect();
            }
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
        sendMessage(DaemonMessage.newBuilder().setCrashReport(report).build());
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

    /** Returns the controller host the daemon is connected to. */
    public String controllerHost() {
        return host;
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
