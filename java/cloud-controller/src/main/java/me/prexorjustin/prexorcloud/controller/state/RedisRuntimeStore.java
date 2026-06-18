package me.prexorjustin.prexorcloud.controller.state;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Write-through Redis persistence for ClusterState runtime maps. Hydrates
 * ClusterState on controller startup so node/instance/player state survives
 * restarts.
 */
public final class RedisRuntimeStore implements WorkloadIdentityRegistry.SequenceWindowStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisRuntimeStore.class);

    private static final String NODE_PREFIX = RedisKeys.NODE_PREFIX;
    private static final String INSTANCE_PREFIX = RedisKeys.INSTANCE_PREFIX;
    private static final String PLAYER_PREFIX = RedisKeys.PLAYER_PREFIX;
    private static final String PLUGIN_TOKEN_PREFIX = RedisKeys.PLUGIN_TOKEN_PREFIX;
    private static final String ACCEPT_SEQUENCE_SCRIPT = """
            local current = redis.call('GET', KEYS[1])
            local next_sequence = tonumber(ARGV[1])
            if current == false or next_sequence > tonumber(current) then
              redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
              return 1
            end
            return 0
            """;

    private final RedisCommands<String, String> commands;
    private final ObjectMapper mapper;

    public RedisRuntimeStore(RedisCommands<String, String> commands, ObjectMapper mapper) {
        this.commands = commands;
        this.mapper = mapper;
    }

    // --- Nodes ---

    public void saveNode(String nodeId, NodeState state) {
        set(RedisKeys.node(nodeId), state);
    }

    public void removeNode(String nodeId) {
        commands.del(RedisKeys.node(nodeId));
    }

    // --- Instances ---

    public void saveInstance(String instanceId, InstanceInfo info) {
        set(RedisKeys.instance(instanceId), info);
    }

    /**
     * Load a single instance from the shared projection. Used by the node-owning
     * controller to adopt a peer-placed instance on demand when a daemon reports
     * status for it before the periodic reconcile has learned it (see
     * {@link ClusterState#adoptInstanceFromRedis}).
     */
    public Optional<InstanceInfo> loadInstance(String instanceId) {
        String json = commands.get(RedisKeys.instance(instanceId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, InstanceInfo.class));
        } catch (JsonProcessingException e) {
            logger.warn("Failed to deserialize instance {}: {}", instanceId, e.getMessage());
            return Optional.empty();
        }
    }

    public void removeInstance(String instanceId) {
        commands.del(RedisKeys.instance(instanceId));
    }

    // --- Players ---

    public void savePlayer(UUID uuid, PlayerInfo info) {
        set(RedisKeys.player(uuid.toString()), info);
    }

    public void removePlayer(UUID uuid) {
        commands.del(RedisKeys.player(uuid.toString()));
    }

    // --- Plugin tokens ---

    /**
     * Persist a plugin token entry with a Redis TTL mirroring the token's
     * expiry, so revoked and expired entries are removed even if the
     * controller process dies before cleanup.
     */
    public void savePluginToken(String token, WorkloadIdentityRegistry.PluginTokenEntry entry) {
        long ttlSeconds = Math.max(
                1,
                Duration.between(Instant.now(Clock.systemUTC()), entry.expiresAt())
                        .getSeconds());
        try {
            commands.setex(RedisKeys.pluginToken(token), ttlSeconds, mapper.writeValueAsString(entry));
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize plugin token {}: {}", entry.instanceId(), e.getMessage());
        }
    }

    public void removePluginToken(String token) {
        commands.del(RedisKeys.pluginToken(token));
    }

    // --- Workload callback replay windows ---

    @Override
    public boolean acceptSequence(String instanceId, long sequence, Duration ttl) {
        Long accepted = commands.eval(
                ACCEPT_SEQUENCE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {RedisKeys.workloadSequence(instanceId)},
                Long.toString(sequence),
                Long.toString(Math.max(1, ttl.getSeconds())));
        return accepted != null && accepted == 1L;
    }

    @Override
    public void clearSequence(String instanceId) {
        commands.del(RedisKeys.workloadSequence(instanceId));
    }

    // --- Bulk load ---

    public RedisSnapshot loadAll() {
        Map<String, NodeState> nodes = new HashMap<>();
        Map<String, InstanceInfo> instances = new HashMap<>();
        Map<UUID, PlayerInfo> players = new HashMap<>();
        Map<String, WorkloadIdentityRegistry.PluginTokenEntry> pluginTokens = new HashMap<>();

        scanLoad(NODE_PREFIX, NodeState.class, nodes);
        scanLoad(INSTANCE_PREFIX, InstanceInfo.class, instances);

        // Players: UUID keys
        var playerScanArgs = ScanArgs.Builder.matches(PLAYER_PREFIX + "*").limit(500);
        KeyScanCursor<String> playerCursor = commands.scan(playerScanArgs);
        while (true) {
            for (String key : playerCursor.getKeys()) {
                String json = commands.get(key);
                if (json != null) {
                    try {
                        UUID uuid = UUID.fromString(key.substring(PLAYER_PREFIX.length()));
                        players.put(uuid, mapper.readValue(json, PlayerInfo.class));
                    } catch (Exception e) {
                        logger.warn("Failed to deserialize {}: {}", key, e.getMessage());
                    }
                }
            }
            if (playerCursor.isFinished()) break;
            playerCursor = commands.scan(playerCursor, playerScanArgs);
        }

        scanLoad(PLUGIN_TOKEN_PREFIX, WorkloadIdentityRegistry.PluginTokenEntry.class, pluginTokens);

        logger.info(
                "Hydrated from Redis: {} nodes, {} instances, {} players, {} plugin tokens",
                nodes.size(),
                instances.size(),
                players.size(),
                pluginTokens.size());
        return new RedisSnapshot(nodes, instances, players, pluginTokens);
    }

    /**
     * Load just the instance projection from Redis (no nodes/players/tokens). Used by the
     * periodic cross-controller reconcile so peers learn about instances on nodes they do
     * not own — far lighter than a full {@link #loadAll()} each scheduler tick.
     */
    public Map<String, InstanceInfo> loadInstances() {
        Map<String, InstanceInfo> instances = new HashMap<>();
        scanLoad(INSTANCE_PREFIX, InstanceInfo.class, instances);
        return instances;
    }

    private <V> void scanLoad(String prefix, Class<V> type, Map<String, V> target) {
        var scanArgs = ScanArgs.Builder.matches(prefix + "*").limit(500);
        KeyScanCursor<String> cursor = commands.scan(scanArgs);
        while (true) {
            for (String key : cursor.getKeys()) {
                String json = commands.get(key);
                if (json != null) {
                    try {
                        target.put(key.substring(prefix.length()), mapper.readValue(json, type));
                    } catch (JsonProcessingException e) {
                        logger.warn("Failed to deserialize {}: {}", key, e.getMessage());
                    }
                }
            }
            if (cursor.isFinished()) break;
            cursor = commands.scan(cursor, scanArgs);
        }
    }

    private void set(String key, Object value) {
        try {
            commands.set(key, mapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize {} to Redis: {}", key, e.getMessage());
        }
    }

    public record RedisSnapshot(
            Map<String, NodeState> nodes,
            Map<String, InstanceInfo> instances,
            Map<UUID, PlayerInfo> players,
            Map<String, WorkloadIdentityRegistry.PluginTokenEntry> pluginTokens) {}
}
