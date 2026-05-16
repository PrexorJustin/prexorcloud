package me.prexorjustin.prexorcloud.protocol;

public final class ProtocolConstants {

    private ProtocolConstants() {}

    public static final String PROTOCOL_VERSION = "1.0";
    public static final int DEFAULT_GRPC_PORT = 9090;
    public static final int DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000;
    public static final int DEFAULT_NODE_TIMEOUT_MS = 90_000;
    public static final int MAX_MESSAGE_SIZE = 100 * 1024 * 1024; // 100 MB (for template transfers)
}
