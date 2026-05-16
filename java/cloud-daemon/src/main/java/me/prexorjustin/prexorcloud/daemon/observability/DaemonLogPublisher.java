package me.prexorjustin.prexorcloud.daemon.observability;

import me.prexorjustin.prexorcloud.daemon.grpc.DaemonGrpcClient;
import me.prexorjustin.prexorcloud.protocol.DaemonLogRecord;
import me.prexorjustin.prexorcloud.protocol.DaemonMessage;

/**
 * Sink that forwards daemon Logback events to the controller over the existing
 * bidirectional gRPC stream.
 *
 * <p>
 * The Logback appender (started by the bootstrap config before {@code main()})
 * writes through this singleton because it is constructed before any DI graph
 * exists. {@link #bind(DaemonGrpcClient)} is called from
 * {@code PrexorDaemon.start()} once the gRPC client is built; before that point,
 * records are silently dropped (the rolling FILE appender on the daemon keeps
 * disk-side history for forensics).
 * </p>
 *
 * <p>
 * A thread-local recursion guard prevents an infinite loop if the gRPC sender
 * itself logs while inside {@link #publish}.
 * </p>
 */
public final class DaemonLogPublisher {

    private static final DaemonLogPublisher INSTANCE = new DaemonLogPublisher();

    private static final ThreadLocal<Boolean> SENDING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private volatile DaemonGrpcClient client;

    private DaemonLogPublisher() {}

    public static DaemonLogPublisher get() {
        return INSTANCE;
    }

    public void bind(DaemonGrpcClient client) {
        this.client = client;
    }

    public void unbind() {
        this.client = null;
    }

    public void publish(DaemonLogRecord record) {
        DaemonGrpcClient current = client;
        if (current == null || !current.isConnected()) {
            return;
        }
        if (SENDING.get()) {
            return;
        }
        SENDING.set(Boolean.TRUE);
        try {
            current.sendMessage(
                    DaemonMessage.newBuilder().setDaemonLogRecord(record).build());
        } catch (Throwable _) {
            // Never let a logging failure crash the JVM.
        } finally {
            SENDING.set(Boolean.FALSE);
        }
    }
}
