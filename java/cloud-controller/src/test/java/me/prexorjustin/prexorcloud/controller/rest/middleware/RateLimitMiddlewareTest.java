package me.prexorjustin.prexorcloud.controller.rest.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.config.RateLimitingConfig;
import me.prexorjustin.prexorcloud.controller.redis.RedisKeys;
import me.prexorjustin.prexorcloud.controller.runtime.RuntimeServices;

import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RateLimitMiddlewareTest {

    @Test
    void redisCountersAreBucketScoped() {
        Map<String, Long> counters = new HashMap<>();
        var middleware = new RateLimitMiddleware(new RateLimitingConfig(100, 300), runtimeFor(countingRedis(counters)));

        assertFalse(middleware.isLimitedForTesting("ip", "127.0.0.1", 1, 100));
        assertFalse(middleware.isLimitedForTesting("user", "127.0.0.1", 1, 100));
        assertTrue(middleware.isLimitedForTesting("ip", "127.0.0.1", 1, 100));

        assertTrue(counters.containsKey(RedisKeys.rateLimit("ip", "127.0.0.1")));
        assertTrue(counters.containsKey(RedisKeys.rateLimit("user", "127.0.0.1")));
        assertFalse(counters.containsKey(RedisKeys.RATE_LIMIT_PREFIX + "127.0.0.1"));
    }

    @Test
    void redisFailureIsFailClosedByDefault() {
        var middleware = new RateLimitMiddleware(new RateLimitingConfig(100, 300), runtimeFor(throwingRedis()));

        assertTrue(middleware.isLimitedForTesting("ip", "127.0.0.1", 100, 100));
    }

    @Test
    void redisFailureCanBeConfiguredFailOpenForDegradedDevelopment() {
        var middleware = new RateLimitMiddleware(new RateLimitingConfig(100, 300, true), runtimeFor(throwingRedis()));

        assertFalse(middleware.isLimitedForTesting("ip", "127.0.0.1", 100, 100));
    }

    private static RuntimeServices runtimeFor(RedisCommands<String, String> redis) {
        RuntimeServices runtime = Mockito.mock(RuntimeServices.class);
        Mockito.when(runtime.coordinationEnabled()).thenReturn(true);
        Mockito.when(runtime.redisCommands()).thenReturn(redis);
        return runtime;
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> countingRedis(Map<String, Long> counters) {
        return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(),
                new Class<?>[] {RedisCommands.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "incr" -> counters.merge((String) args[0], 1L, Long::sum);
                    case "expire" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private static RedisCommands<String, String> throwingRedis() {
        return (RedisCommands<String, String>) Proxy.newProxyInstance(
                RedisCommands.class.getClassLoader(), new Class<?>[] {RedisCommands.class}, (proxy, method, args) -> {
                    if ("incr".equals(method.getName())) {
                        throw new IllegalStateException("redis unavailable");
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == long.class) return 0L;
        if (returnType == int.class) return 0;
        return null;
    }
}
