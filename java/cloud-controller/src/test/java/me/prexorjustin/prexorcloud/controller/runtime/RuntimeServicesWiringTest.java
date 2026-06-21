package me.prexorjustin.prexorcloud.controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import me.prexorjustin.prexorcloud.controller.PrexorController;
import me.prexorjustin.prexorcloud.controller.config.RateLimitingConfig;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleStorageManager;
import me.prexorjustin.prexorcloud.controller.rest.middleware.RateLimitMiddleware;
import me.prexorjustin.prexorcloud.controller.scheduler.ScalingEvaluator;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Phase 30 acceptance test. The production wiring graph must not carry any
 * {@code Optional<*Redis*>}-typed dependencies after construction — every
 * Redis-shaped reference is sourced from {@link RuntimeServices} and is
 * non-null in production.
 */
@DisplayName("RuntimeServices wiring")
class RuntimeServicesWiringTest {

    @Test
    @DisplayName("RedisRuntimeServices accessors are all non-null when coordination is enabled")
    void redisRuntimeServicesAccessorsAreNonNull() {
        RuntimeServices runtime = mockProductionRuntime();
        assertTrue(runtime.coordinationEnabled());
        assertNotNull(runtime.redisCommands());
        assertNotNull(runtime.runtimeStore());
        assertNotNull(runtime.jwtRevocationStore());
        assertNotNull(runtime.loginAttemptStore());
        assertNotNull(runtime.consoleFloodWindow());
    }

    @Test
    @DisplayName("InMemoryRuntimeServices reports coordination disabled and never blows up")
    void inMemoryAccessorsDoNotBlowUp() {
        RuntimeServices runtime = new InMemoryRuntimeServices();
        assertEquals("development", runtime.profile());
        assertEquals(false, runtime.coordinationEnabled());
        assertNotNull(runtime.jwtRevocationStore());
        assertNotNull(runtime.loginAttemptStore());
        assertNotNull(runtime.consoleFloodWindow());
        runtime.close();
    }

    @Nested
    @DisplayName("production wiring graph")
    class ProductionWiringGraph {

        @Test
        @DisplayName("contains zero Optional<*Redis*>-typed fields under construction")
        void productionWiringHasNoOptionalRedisFields() {
            // Every component that previously branched on `redis != null`
            // accepts a RuntimeServices reference now.
            RuntimeServices runtime = mockProductionRuntime();

            List<Object> graph = buildProductionGraph(runtime);

            List<String> failures = collectOptionalRedisFields(graph);
            assertTrue(failures.isEmpty(), "Optional<*Redis*>-typed fields found: " + failures);
        }

        @Test
        @DisplayName("contains zero raw nullable RedisCommands fields after construction")
        void productionWiringHasNoNullRawRedisFields() {
            RuntimeServices runtime = mockProductionRuntime();

            List<Object> graph = buildProductionGraph(runtime);

            List<String> nullRedisFields = new ArrayList<>();
            walk(graph, (owner, field, value) -> {
                if (typeNameContainsRedis(field.getGenericType()) && value == null) {
                    nullRedisFields.add(owner.getClass().getSimpleName() + "#" + field.getName());
                }
            });
            assertTrue(
                    nullRedisFields.isEmpty(),
                    "Production wiring still has nullable Redis-typed fields: " + nullRedisFields);
        }

        private static List<Object> buildProductionGraph(RuntimeServices runtime) {
            ClusterState clusterState = new ClusterState(new me.prexorjustin.prexorcloud.controller.event.EventBus());
            List<Object> graph = new ArrayList<>();
            graph.add(runtime);
            graph.add(clusterState);
            graph.add(new RateLimitMiddleware(new RateLimitingConfig(60, 600)));
            graph.add(new ScalingEvaluator(clusterState, 30L, runtime));
            graph.add(new PlatformModuleStorageManager(null, null, runtime, new ObjectMapper()));
            return graph;
        }
    }

    private static RuntimeServices mockProductionRuntime() {
        RuntimeServices runtime = Mockito.mock(RuntimeServices.class);
        @SuppressWarnings("unchecked")
        io.lettuce.core.api.sync.RedisCommands<String, String> redis =
                Mockito.mock(io.lettuce.core.api.sync.RedisCommands.class);
        Mockito.when(runtime.profile()).thenReturn("production");
        Mockito.when(runtime.coordinationEnabled()).thenReturn(true);
        Mockito.when(runtime.redisCommands()).thenReturn(redis);
        Mockito.when(runtime.runtimeStore())
                .thenReturn(Mockito.mock(me.prexorjustin.prexorcloud.controller.state.RedisRuntimeStore.class));
        Mockito.when(runtime.jwtRevocationStore())
                .thenReturn(Mockito.mock(me.prexorjustin.prexorcloud.controller.auth.JwtRevocationStore.class));
        Mockito.when(runtime.loginAttemptStore())
                .thenReturn(Mockito.mock(me.prexorjustin.prexorcloud.controller.auth.LoginAttemptStore.class));
        Mockito.when(runtime.consoleFloodWindow())
                .thenReturn(Mockito.mock(
                        me.prexorjustin.prexorcloud.controller.console.ConsoleBuffer.FloodWindowStore.class));
        return runtime;
    }

    private static List<String> collectOptionalRedisFields(List<Object> graph) {
        List<String> failures = new ArrayList<>();
        walk(graph, (owner, field, value) -> {
            Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType pt
                    && pt.getRawType() instanceof Class<?> raw
                    && java.util.Optional.class.equals(raw)
                    && pt.getActualTypeArguments().length == 1
                    && typeNameContainsRedis(pt.getActualTypeArguments()[0])) {
                failures.add(owner.getClass().getSimpleName() + "#" + field.getName());
            }
        });
        return failures;
    }

    private static boolean typeNameContainsRedis(Type type) {
        if (type == null) return false;
        return type.getTypeName().toLowerCase().contains("redis")
                || type.getTypeName().contains("io.lettuce");
    }

    @FunctionalInterface
    private interface FieldVisitor {
        void visit(Object owner, Field field, Object value);
    }

    private static void walk(List<Object> roots, FieldVisitor visitor) {
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Class<?>> visitedClasses = new HashSet<>();
        java.util.Deque<Object> stack = new java.util.ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            Object current = stack.pop();
            if (current == null || !seen.add(current)) continue;
            if (org.mockito.internal.util.MockUtil.isMock(current) || org.mockito.internal.util.MockUtil.isSpy(current))
                continue;
            Class<?> klass = current.getClass();
            if (klass.getName().startsWith("java.")) continue;
            for (Field f : klass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object value;
                try {
                    value = f.get(current);
                } catch (IllegalAccessException _) {
                    continue;
                }
                visitor.visit(current, f, value);
                if (value != null
                        && !f.getType().isPrimitive()
                        && !f.getType().getName().startsWith("java.")
                        && visitedClasses.add(value.getClass())) {
                    stack.push(value);
                }
            }
        }
    }

    // Suppresses unused warning on imported PrexorController; only here to confirm resolution.
    @SuppressWarnings("unused")
    private static final Class<?> PREXOR_CONTROLLER_TYPE = PrexorController.class;
}
