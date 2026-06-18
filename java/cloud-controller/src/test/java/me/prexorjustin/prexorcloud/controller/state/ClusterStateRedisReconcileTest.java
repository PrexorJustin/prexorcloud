package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.api.sync.RedisCommands;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the cross-controller convergence paths added for the HA divergence findings
 * (G2/G3): {@link ClusterState#adoptInstanceFromRedis} and the add / converge / prune
 * behaviour of {@link ClusterState#reconcileInstancesFromRedis}.
 */
@DisplayName("ClusterState — Redis reconcile / adopt")
class ClusterStateRedisReconcileTest {

    private Map<String, String> backing;
    private RedisRuntimeStore store;
    private EventBus eventBus;
    private ClusterState state;

    @BeforeEach
    void setUp() {
        backing = new HashMap<>();
        // InstanceInfo carries an Instant; the mapper needs jsr310 or serialization silently fails.
        store = new RedisRuntimeStore(inMemoryCommands(backing), new ObjectMapper().findAndRegisterModules());
        eventBus = new EventBus();
        state = new ClusterState(eventBus, store);
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    // --- G3: adopt a peer-placed instance on status ---

    @Test
    @DisplayName("adopt: peer-placed instance on the reporting node is adopted from Redis")
    void adoptsPeerPlacedInstance() {
        store.saveInstance("lobby-1", instance("lobby-1", "lobby", "node-a", InstanceState.SCHEDULED));
        assertTrue(state.getInstance("lobby-1").isEmpty(), "precondition: not known locally");

        assertTrue(state.adoptInstanceFromRedis("lobby-1", "node-a"));
        assertTrue(state.getInstance("lobby-1").isPresent());
        assertEquals(InstanceState.SCHEDULED, state.getInstance("lobby-1").get().state());
    }

    @Test
    @DisplayName("adopt: refuses when Redis has the instance on a different node")
    void refusesNodeMismatch() {
        store.saveInstance("lobby-1", instance("lobby-1", "lobby", "node-a", InstanceState.SCHEDULED));
        assertFalse(state.adoptInstanceFromRedis("lobby-1", "node-b"));
        assertTrue(state.getInstance("lobby-1").isEmpty());
    }

    @Test
    @DisplayName("adopt: refuses when the instance is absent from Redis")
    void refusesWhenAbsent() {
        assertFalse(state.adoptInstanceFromRedis("ghost", "node-a"));
        assertTrue(state.getInstance("ghost").isEmpty());
    }

    // --- G2: reconcile add / converge / prune ---

    @Test
    @DisplayName("reconcile: learns an instance present in Redis but unknown locally")
    void learnsMissingInstance() {
        store.saveInstance("lobby-1", instance("lobby-1", "lobby", "node-a", InstanceState.RUNNING));
        assertEquals(1, state.reconcileInstancesFromRedis());
        assertTrue(state.getInstance("lobby-1").isPresent());
    }

    @Test
    @DisplayName("reconcile: converges a known instance's state forward toward Redis")
    void convergesStateForward() {
        state.addInstance(instance("lobby-1", "lobby", "node-a", InstanceState.SCHEDULED));
        // Owner advances it to RUNNING in the shared projection.
        store.saveInstance("lobby-1", instance("lobby-1", "lobby", "node-a", InstanceState.RUNNING));

        state.reconcileInstancesFromRedis();
        assertEquals(InstanceState.RUNNING, state.getInstance("lobby-1").get().state());
    }

    @Test
    @DisplayName("reconcile: never moves a fresher local state backward (no owner clobber)")
    void doesNotClobberFresherLocal() {
        state.addInstance(instance("lobby-1", "lobby", "node-a", InstanceState.RUNNING));
        // Stale Redis read still shows STARTING.
        store.saveInstance("lobby-1", instance("lobby-1", "lobby", "node-a", InstanceState.STARTING));

        state.reconcileInstancesFromRedis();
        assertEquals(InstanceState.RUNNING, state.getInstance("lobby-1").get().state());
    }

    @Test
    @DisplayName("reconcile: prunes a local mirror gone from Redis only after the grace tick")
    void prunesAfterGraceTick() {
        state.addInstance(instance("lobby-1", "lobby", "node-a", InstanceState.RUNNING));
        // Owner removed it from the shared projection.
        backing.remove(RedisKeys.instance("lobby-1"));

        state.reconcileInstancesFromRedis(); // tick 1: streak=1, kept
        assertTrue(state.getInstance("lobby-1").isPresent(), "kept after first absent tick");

        state.reconcileInstancesFromRedis(); // tick 2: streak=2, pruned
        assertTrue(state.getInstance("lobby-1").isEmpty(), "pruned after grace tick");
    }

    @Test
    @DisplayName("reconcile: a reappearing instance resets the prune streak")
    void reappearanceResetsStreak() {
        state.addInstance(instance("lobby-1", "lobby", "node-a", InstanceState.RUNNING));
        backing.remove(RedisKeys.instance("lobby-1"));
        state.reconcileInstancesFromRedis(); // streak=1

        store.saveInstance("lobby-1", instance("lobby-1", "lobby", "node-a", InstanceState.RUNNING)); // back
        state.reconcileInstancesFromRedis(); // streak reset
        backing.remove(RedisKeys.instance("lobby-1"));
        state.reconcileInstancesFromRedis(); // streak=1 again, NOT pruned

        assertTrue(state.getInstance("lobby-1").isPresent(), "streak reset on reappearance");
    }

    private static InstanceInfo instance(String id, String group, String nodeId, InstanceState st) {
        return new InstanceInfo(id, group, nodeId, st, 30000, 0, 0, Instant.now());
    }

    /** Minimal in-memory {@link RedisCommands} backed by a map: get/set/del/scan only. */
    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> inMemoryCommands(Map<String, String> map) {
        return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "set":
                            map.put((String) args[0], (String) args[1]);
                            return "OK";
                        case "get":
                            return map.get((String) args[0]);
                        case "del": {
                            long removed = 0;
                            for (String key : keysOf(args[0])) {
                                if (map.remove(key) != null) removed++;
                            }
                            return removed;
                        }
                        case "scan": {
                            KeyScanCursor<String> cursor = new KeyScanCursor<>();
                            cursor.setCursor("0");
                            cursor.setFinished(true);
                            cursor.getKeys().addAll(new ArrayList<>(map.keySet()));
                            return cursor;
                        }
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == long.class) return 0L;
                            if (rt == int.class) return 0;
                            return null;
                    }
                });
    }

    private static String[] keysOf(Object arg) {
        return arg instanceof String[] keys ? keys : new String[] {(String) arg};
    }
}
