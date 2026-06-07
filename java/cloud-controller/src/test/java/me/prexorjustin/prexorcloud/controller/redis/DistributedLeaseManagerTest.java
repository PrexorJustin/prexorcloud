package me.prexorjustin.prexorcloud.controller.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

class DistributedLeaseManagerTest {

    @Test
    void reacquisitionMonotonicallyIncrementsFenceToken() {
        InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
        DistributedLeaseManager leaseManager = new DistributedLeaseManager(redis.commands(), "controller-a", 60);

        var first = leaseManager.tryAcquireLease("group:lobby").orElseThrow();
        leaseManager.release(first);
        var second = leaseManager.tryAcquireLease("group:lobby").orElseThrow();

        assertEquals(1L, first.token());
        assertEquals(2L, second.token());
        assertTrue(leaseManager.isCurrent(second));
    }

    @Test
    void staleLeaseCannotReleaseNewerHolder() {
        InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
        DistributedLeaseManager controllerA = new DistributedLeaseManager(redis.commands(), "controller-a", 60);
        DistributedLeaseManager controllerB = new DistributedLeaseManager(redis.commands(), "controller-b", 60);

        var first = controllerA.tryAcquireLease("group:lobby").orElseThrow();
        redis.delete(RedisKeys.lease("group:lobby"));
        var second = controllerB.tryAcquireLease("group:lobby").orElseThrow();

        assertFalse(controllerA.isCurrent(first));
        assertTrue(controllerB.isCurrent(second));

        controllerA.release(first);

        assertTrue(controllerB.currentLease("group:lobby").isPresent());
        assertEquals(2L, controllerB.currentLease("group:lobby").orElseThrow().token());
    }

    @Test
    void listenerRunsOnlyWhenLeaseOwnershipIsAcquired() {
        InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
        DistributedLeaseManager controllerA = new DistributedLeaseManager(redis.commands(), "controller-a", 60);
        DistributedLeaseManager controllerB = new DistributedLeaseManager(redis.commands(), "controller-b", 60);
        List<DistributedLeaseManager.Lease> observed = new ArrayList<>();
        controllerA.addLeaseChangeListener(observed::add);

        controllerA.tryAcquireLease("group:lobby").orElseThrow();
        controllerA.tryAcquireLease("group:lobby").orElseThrow();
        controllerB.release("group:lobby");
        controllerA.release("group:lobby");
        controllerA.tryAcquireLease("group:lobby").orElseThrow();

        assertEquals(2, observed.size());
        assertEquals(1L, observed.getFirst().token());
        assertEquals(2L, observed.get(1).token());
    }

    private static final class InMemoryLeaseRedis {

        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, Long> counters = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        private RedisCommands<String, String> commands() {
            return (RedisCommands<String, String>) Proxy.newProxyInstance(
                    RedisCommands.class.getClassLoader(),
                    new Class<?>[] {RedisCommands.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> set((String) args[0], (String) args[1], (SetArgs) args[2]);
                        case "get" -> values.get((String) args[0]);
                        case "expire" -> true;
                        case "del" -> deleteAll(args);
                        case "incr" -> counters.merge((String) args[0], 1L, Long::sum);
                        case "scan" -> scan(args);
                        case "toString" -> "InMemoryLeaseRedis";
                        default ->
                            throw new UnsupportedOperationException("Unsupported Redis method: " + method.getName());
                    });
        }

        private String set(String key, String value, SetArgs args) {
            if (values.containsKey(key)) {
                return null;
            }
            values.put(key, value);
            return "OK";
        }

        private long deleteAll(Object[] args) {
            long deleted = 0;
            for (Object rawKey : args) {
                if (rawKey instanceof String key) {
                    deleted += values.remove(key) != null ? 1 : 0;
                } else if (rawKey instanceof String[] keys) {
                    for (String key : keys) {
                        deleted += values.remove(key) != null ? 1 : 0;
                    }
                }
            }
            return deleted;
        }

        private KeyScanCursor<String> scan(Object[] args) {
            KeyScanCursor<String> cursor = new KeyScanCursor<>();
            cursor.setCursor(ScanCursor.FINISHED.getCursor());
            cursor.setFinished(true);
            cursor.getKeys().addAll(values.keySet());
            return cursor;
        }

        private void delete(String key) {
            values.remove(key);
        }
    }
}
