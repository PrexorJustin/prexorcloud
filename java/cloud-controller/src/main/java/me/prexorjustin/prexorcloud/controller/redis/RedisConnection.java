package me.prexorjustin.prexorcloud.controller.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RedisConnection {

    private static final Logger logger = LoggerFactory.getLogger(RedisConnection.class);

    private final String uri;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;

    public RedisConnection(String uri) {
        this.uri = uri;
    }

    public void initialize() {
        client = RedisClient.create(RedisURI.create(uri));
        connection = client.connect();
        logger.info("Redis connected: {}", sanitizeUri(uri));
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
        logger.info("Redis disconnected");
    }

    private static String sanitizeUri(String uri) {
        // Mask password in redis://:password@host:port URIs
        return uri.replaceAll("://:[^@]+@", "://:***@");
    }
}
