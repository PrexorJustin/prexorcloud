package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

class RedisRuntimeStoreTest {

    @Test
    void acceptsOnlyIncreasingWorkloadSequences() {
        var sequences = new HashMap<String, Long>();
        var store = new RedisRuntimeStore(redisCommands(sequences), new ObjectMapper());

        assertTrue(store.acceptSequence("instance-1", 10L, Duration.ofMinutes(15)));
        assertFalse(store.acceptSequence("instance-1", 10L, Duration.ofMinutes(15)));
        assertFalse(store.acceptSequence("instance-1", 9L, Duration.ofMinutes(15)));
        assertTrue(store.acceptSequence("instance-1", 11L, Duration.ofMinutes(15)));
    }

    @Test
    void clearsWorkloadSequenceWindow() {
        var sequences = new HashMap<String, Long>();
        var store = new RedisRuntimeStore(redisCommands(sequences), new ObjectMapper());

        assertTrue(store.acceptSequence("instance-1", 10L, Duration.ofMinutes(15)));

        store.clearSequence("instance-1");

        assertTrue(store.acceptSequence("instance-1", 10L, Duration.ofMinutes(15)));
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> redisCommands(Map<String, Long> sequences) {
        return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(), new Class<?>[] {RedisCommands.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "eval" -> {
                            String[] keys = (String[]) args[2];
                            String[] values = (String[]) args[3];
                            String key = keys[0];
                            long next = Long.parseLong(values[0]);
                            Long current = sequences.get(key);
                            if (current == null || next > current) {
                                sequences.put(key, next);
                                yield 1L;
                            }
                            yield 0L;
                        }
                        case "del" -> {
                            long removed = 0;
                            for (String key : redisKeys(args[0])) {
                                removed += sequences.remove(key) == null ? 0 : 1;
                            }
                            yield removed;
                        }
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static String[] redisKeys(Object arg) {
        return arg instanceof String[] keys ? keys : new String[] {(String) arg};
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == long.class) return 0L;
        if (returnType == int.class) return 0;
        return null;
    }
}
