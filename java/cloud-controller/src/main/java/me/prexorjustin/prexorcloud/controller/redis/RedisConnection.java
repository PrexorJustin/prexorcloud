package me.prexorjustin.prexorcloud.controller.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.tracing.Tracing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RedisConnection {

    private static final Logger logger = LoggerFactory.getLogger(RedisConnection.class);

    private final String uri;
    private final Tracing tracing; // null = untraced (telemetry off)
    private RedisClient client;
    private ClientResources clientResources; // non-null only when tracing is installed
    private StatefulRedisConnection<String, String> connection;

    public RedisConnection(String uri) {
        this(uri, null);
    }

    public RedisConnection(String uri, Tracing tracing) {
        this.uri = uri;
        this.tracing = tracing;
    }

    public void initialize() {
        if (tracing != null) {
            clientResources = ClientResources.builder().tracing(tracing).build();
            client = RedisClient.create(clientResources, RedisURI.create(uri));
        } else {
            client = RedisClient.create(RedisURI.create(uri));
        }
        connection = client.connect();
        logger.info("Redis connected: {}{}", sanitizeUri(uri), tracing != null ? " (traced)" : "");
    }

    public RedisCommands<String, String> sync() {
        return connection.sync();
    }

    public StatefulRedisPubSubConnection<String, String> pubSubConnection() {
        return client.connectPubSub();
    }

    public void close() {
        if (connection != null) connection.close();
        if (client != null) client.close();
        if (clientResources != null) clientResources.shutdown();
        logger.info("Redis disconnected");
    }

    private static String sanitizeUri(String uri) {
        // Mask password in redis://:password@host:port URIs
        return uri.replaceAll("://:[^@]+@", "://:***@");
    }
}
