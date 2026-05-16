package me.prexorjustin.prexorcloud.controller.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.redis.DistributedLeaseManager;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.HealingActionIntent;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealingReconcilerTest {

    private ClusterState clusterState;
    private WorkflowStateStore workflowStateStore;
    private HealingReconciler healingReconciler;
    private HealingReconciler.ReplacementAction replacementAction;

    @BeforeEach
    void setUp() {
        clusterState = new ClusterState(new EventBus());
        workflowStateStore = new WorkflowStateStore();
        healingReconciler = new HealingReconciler(clusterState);
        replacementAction = mock(HealingReconciler.ReplacementAction.class);
        healingReconciler.attachWorkflow(workflowStateStore, replacementAction);
    }

    @Test
    void queueHealingPersistsIntentAndSchedulesReplacement() {
        healingReconciler.queueHealing("lobby-1", "lobby", "INSTANCE_CRASHED");

        assertTrue(workflowStateStore.getHealingAction("lobby-1").isPresent());
        verify(replacementAction).scheduleReplacement("lobby", "lobby-1");
    }

    @Test
    void reconcileClearsIntentWhenInstanceRecovered() {
        workflowStateStore.saveHealingAction(
                new HealingActionIntent("lobby-1", "lobby", "INSTANCE_CRASHED", Instant.now()));
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now()));

        healingReconciler.reconcilePersistedHealingActions();

        assertFalse(workflowStateStore.getHealingAction("lobby-1").isPresent());
        verify(replacementAction, never()).scheduleReplacement("lobby", "lobby-1");
    }

    @Test
    void reconcileSchedulesReplacementForTerminalOrMissingInstance() {
        workflowStateStore.saveHealingAction(
                new HealingActionIntent("lobby-1", "lobby", "INSTANCE_CRASHED", Instant.now()));
        workflowStateStore.saveHealingAction(
                new HealingActionIntent("lobby-2", "lobby", "INSTANCE_CRASHED", Instant.now()));
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.CRASHED, 25565, 0, 0, Instant.now()));

        healingReconciler.reconcilePersistedHealingActions();

        verify(replacementAction).scheduleReplacement("lobby", "lobby-1");
        verify(replacementAction).scheduleReplacement("lobby", "lobby-2");
    }

    @Test
    void leaseAwareReconcileSkipsControllersWithoutLease() {
        workflowStateStore.saveHealingAction(
                new HealingActionIntent("lobby-1", "lobby", "INSTANCE_CRASHED", Instant.now()));

        InMemoryLeaseRedis redis = new InMemoryLeaseRedis();
        var controllerALeaseManager = new DistributedLeaseManager(redis.commands(), "controller-a", 60);
        var controllerBLeaseManager = new DistributedLeaseManager(redis.commands(), "controller-b", 60);
        assertTrue(controllerALeaseManager.tryAcquireLease("healing:lobby-1").isPresent());

        var leaseAwareReconciler = new HealingReconciler(clusterState);
        var controllerBReplacementAction = mock(HealingReconciler.ReplacementAction.class);
        leaseAwareReconciler.attachWorkflow(workflowStateStore, controllerBReplacementAction, controllerBLeaseManager);

        leaseAwareReconciler.reconcilePersistedHealingActions();
        verify(controllerBReplacementAction, never()).scheduleReplacement("lobby", "lobby-1");

        controllerALeaseManager.release("healing:lobby-1");

        leaseAwareReconciler.reconcilePersistedHealingActions();
        verify(controllerBReplacementAction, atLeastOnce()).scheduleReplacement("lobby", "lobby-1");
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
                        case "scan" -> scan();
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

        private KeyScanCursor<String> scan() {
            KeyScanCursor<String> cursor = new KeyScanCursor<>();
            cursor.setCursor(ScanCursor.FINISHED.getCursor());
            cursor.setFinished(true);
            cursor.getKeys().addAll(values.keySet());
            return cursor;
        }
    }
}
