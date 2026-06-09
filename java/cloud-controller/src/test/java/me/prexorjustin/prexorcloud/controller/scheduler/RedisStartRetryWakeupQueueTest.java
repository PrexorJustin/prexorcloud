package me.prexorjustin.prexorcloud.controller.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.controller.state.StartRetryIntent;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

class RedisStartRetryWakeupQueueTest {

    @Test
    void claimsDueWakeupsOnlyOnceAcrossControllers() {
        var redis = new InMemoryRetryWakeupRedis();
        var queueA = new RedisStartRetryWakeupQueue(redis.commands(), "controller-a", 30);
        var queueB = new RedisStartRetryWakeupQueue(redis.commands(), "controller-b", 30);
        var intent = new StartRetryIntent(
                "lobby-1", "lobby", "node-1", "RUNTIME_PROVISION_FAILED", "plan-hash", 1, Instant.now(), Instant.now());

        queueA.schedule(intent);

        assertEquals(List.of("lobby-1"), queueA.claimDue(Instant.now(), 10));
        assertTrue(queueB.claimDue(Instant.now(), 10).isEmpty());
    }

    @Test
    void schedulingAgainRearmsClaimedWakeup() {
        var redis = new InMemoryRetryWakeupRedis();
        var queueA = new RedisStartRetryWakeupQueue(redis.commands(), "controller-a", 30);
        var queueB = new RedisStartRetryWakeupQueue(redis.commands(), "controller-b", 30);
        var intent = new StartRetryIntent(
                "lobby-2", "lobby", "node-1", "RUNTIME_PROVISION_FAILED", "plan-hash", 2, Instant.now(), Instant.now());

        queueA.schedule(intent);
        assertEquals(List.of("lobby-2"), queueA.claimDue(Instant.now(), 10));

        queueA.schedule(intent);

        assertEquals(List.of("lobby-2"), queueB.claimDue(Instant.now(), 10));
    }

    private static final class InMemoryRetryWakeupRedis {

        private final Map<String, String> values = new ConcurrentHashMap<>();
        private final Map<String, java.util.NavigableMap<Double, java.util.Set<String>>> zsets =
                new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        private RedisCommands<String, String> commands() {
            return (RedisCommands<String, String>) Proxy.newProxyInstance(
                    RedisCommands.class.getClassLoader(),
                    new Class<?>[] {RedisCommands.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> set((String) args[0], (String) args[1], (SetArgs) args[2]);
                        case "del" -> deleteAll(args);
                        case "zadd" -> zadd((String) args[0], toDouble(args[1]), (String) args[2]);
                        case "zrem" -> zrem((String) args[0], args[1]);
                        case "zrangebyscore" -> zrangebyscore((String) args[0], toDouble(args[1]), toDouble(args[2]));
                        case "toString" -> "InMemoryRetryWakeupRedis";
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
                    deleted += zsets.remove(key) != null ? 1 : 0;
                } else if (rawKey instanceof String[] keys) {
                    for (String key : keys) {
                        deleted += values.remove(key) != null ? 1 : 0;
                        deleted += zsets.remove(key) != null ? 1 : 0;
                    }
                }
            }
            return deleted;
        }

        private long zadd(String key, double score, String member) {
            var sortedMembers = zsets.computeIfAbsent(key, ignored -> new java.util.TreeMap<>());
            sortedMembers.values().forEach(members -> members.remove(member));
            sortedMembers
                    .computeIfAbsent(score, ignored -> new java.util.LinkedHashSet<>())
                    .add(member);
            return 1L;
        }

        private long zrem(String key, Object rawMembers) {
            var sortedMembers = zsets.get(key);
            if (sortedMembers == null) {
                return 0L;
            }
            var members = new java.util.ArrayList<String>();
            if (rawMembers instanceof String member) {
                members.add(member);
            } else if (rawMembers instanceof String[] manyMembers) {
                members.addAll(List.of(manyMembers));
            }
            long removed = 0L;
            var iterator = sortedMembers.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                for (String member : members) {
                    if (entry.getValue().remove(member)) {
                        removed++;
                    }
                }
                if (entry.getValue().isEmpty()) {
                    iterator.remove();
                }
            }
            return removed;
        }

        private List<String> zrangebyscore(String key, double min, double max) {
            var sortedMembers = zsets.get(key);
            if (sortedMembers == null) {
                return List.of();
            }
            var result = new java.util.ArrayList<String>();
            for (var entry : sortedMembers.entrySet()) {
                if (entry.getKey() < min || entry.getKey() > max) {
                    continue;
                }
                result.addAll(entry.getValue());
            }
            return List.copyOf(result);
        }

        private double toDouble(Object value) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            return Double.parseDouble(String.valueOf(value));
        }
    }
}
