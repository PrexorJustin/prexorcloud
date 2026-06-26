package me.prexorjustin.prexorcloud.controller.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeploymentReconcilerTest {

    private EventBus eventBus;
    private ClusterState clusterState;
    private StateStore stateStore;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        clusterState = new ClusterState(eventBus);
        stateStore = mock(StateStore.class);
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Test
    void rollingRestartCompletesAndTracksProgress() {
        var deployment = new DeploymentRecord(
                1,
                "lobby",
                2,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{}",
                2,
                0,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(
                new InstanceInfo("lobby-1", "lobby", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now()));
        clusterState.addInstance(
                new InstanceInfo("lobby-2", "lobby", "node-1", InstanceState.RUNNING, 25566, 0, 0, Instant.now()));
        when(stateStore.getDeployment("lobby", 2)).thenReturn(Optional.of(deployment));

        var stopped = new CopyOnWriteArrayList<String>();
        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            stopped.add(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            // Every stopped instance gets a new-revision replacement (as real placement would), so each
            // wave reaches its expected updated count instead of relying on the old "continue anyway".
            Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                clusterState.addInstance(new InstanceInfo(
                        instanceId + "-r2", "lobby", "node-1", InstanceState.SCHEDULED, 25600 + stopped.size(), 0, 0,
                        Instant.now(), 2));
            });
            return true;
        });

        reconciler.rollingRestart(deployment);

        assertEquals(List.of("lobby-1", "lobby-2"), stopped);
        verify(stateStore).updateDeploymentProgress(1, 1);
        verify(stateStore).updateDeploymentProgress(1, 2);
        verify(stateStore).updateDeploymentState(1, "COMPLETED");
    }

    @Test
    void rollingRestartStopsEarlyWhenDeploymentPaused() {
        var initial = new DeploymentRecord(
                3,
                "proxy",
                4,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{}",
                2,
                0,
                Instant.now().toString(),
                null,
                null);
        var paused = new DeploymentRecord(
                3,
                "proxy",
                4,
                "manual",
                "ROLLING",
                "PAUSED",
                "{}",
                "{}",
                2,
                1,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(
                new InstanceInfo("proxy-1", "proxy", "node-1", InstanceState.RUNNING, 25570, 0, 0, Instant.now()));
        clusterState.addInstance(
                new InstanceInfo("proxy-2", "proxy", "node-1", InstanceState.RUNNING, 25571, 0, 0, Instant.now()));
        when(stateStore.getDeployment("proxy", 4)).thenReturn(Optional.of(initial), Optional.of(paused));

        var stopped = new CopyOnWriteArrayList<String>();
        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            stopped.add(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            // The first wave's replacement comes up, so the pause is observed on the next loop iteration
            // (revision 4 is the deployment under test) rather than being masked by a replacement timeout.
            clusterState.addInstance(new InstanceInfo(
                    instanceId + "-r4", "proxy", "node-1", InstanceState.SCHEDULED, 25600 + stopped.size(), 0, 0,
                    Instant.now(), 4));
            return true;
        });

        reconciler.rollingRestart(initial);

        assertEquals(List.of("proxy-1"), stopped);
        verify(stateStore).updateDeploymentProgress(3, 1);
        verify(stateStore, times(1)).updateDeploymentProgress(3, 1);
        verify(stateStore).updateDeploymentState(3, "PAUSED");
    }

    @Test
    void rollingRestartResumesOnlyOutdatedInstancesByDeploymentRevision() {
        var deployment = new DeploymentRecord(
                7,
                "minigame",
                3,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{}",
                2,
                1,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(new InstanceInfo(
                "minigame-1", "minigame", "node-1", InstanceState.RUNNING, 25580, 0, 0, Instant.now(), 3));
        clusterState.addInstance(new InstanceInfo(
                "minigame-2", "minigame", "node-1", InstanceState.RUNNING, 25581, 0, 0, Instant.now(), 0));
        when(stateStore.getDeployment("minigame", 3)).thenReturn(Optional.of(deployment), Optional.of(deployment));

        var stopped = new CopyOnWriteArrayList<String>();
        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            stopped.add(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            Thread.startVirtualThread(() -> clusterState.addInstance(new InstanceInfo(
                    "minigame-3", "minigame", "node-1", InstanceState.SCHEDULED, 25582, 0, 0, Instant.now(), 3)));
            return true;
        });

        reconciler.rollingRestart(deployment);

        assertEquals(List.of("minigame-2"), stopped);
        verify(stateStore, never()).updateDeploymentProgress(7, 1);
        verify(stateStore).updateDeploymentProgress(7, 2);
        verify(stateStore).updateDeploymentState(7, "COMPLETED");
    }

    @Test
    void rollingRestartLeavesDeploymentInProgressWhenGuardFailsMidRollout() {
        var deployment = new DeploymentRecord(
                9,
                "skyblock",
                5,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{}",
                2,
                0,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(new InstanceInfo(
                "skyblock-1", "skyblock", "node-1", InstanceState.RUNNING, 25590, 0, 0, Instant.now(), 0));
        clusterState.addInstance(new InstanceInfo(
                "skyblock-2", "skyblock", "node-1", InstanceState.RUNNING, 25591, 0, 0, Instant.now(), 0));
        when(stateStore.getDeployment("skyblock", 5)).thenReturn(Optional.of(deployment), Optional.of(deployment));

        var stopped = new CopyOnWriteArrayList<String>();
        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            stopped.add(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            return true;
        });

        var guardCalls = new java.util.concurrent.atomic.AtomicInteger();
        reconciler.rollingRestart(deployment, action -> guardCalls.incrementAndGet() == 1);

        assertEquals(1, stopped.size());
        verify(stateStore).updateDeploymentProgress(9, 1);
        verify(stateStore, never()).updateDeploymentState(9, "COMPLETED");
        verify(stateStore, never()).updateDeploymentState(9, "PAUSED");
    }

    @Test
    void rollingRestartUsesCanaryWaveBeforeLargerBatches() {
        var deployment = new DeploymentRecord(
                11,
                "canary",
                2,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{\"group\":\"canary\",\"strategy\":\"ROLLING\",\"batchSize\":2,\"canaryInstances\":1}",
                4,
                0,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(
                new InstanceInfo("canary-1", "canary", "node-1", InstanceState.RUNNING, 25561, 0, 0, Instant.now(), 0));
        clusterState.addInstance(
                new InstanceInfo("canary-2", "canary", "node-1", InstanceState.RUNNING, 25562, 0, 0, Instant.now(), 0));
        clusterState.addInstance(
                new InstanceInfo("canary-3", "canary", "node-1", InstanceState.RUNNING, 25563, 0, 0, Instant.now(), 0));
        clusterState.addInstance(
                new InstanceInfo("canary-4", "canary", "node-1", InstanceState.RUNNING, 25564, 0, 0, Instant.now(), 0));
        when(stateStore.getDeployment("canary", 2))
                .thenReturn(Optional.of(deployment), Optional.of(deployment), Optional.of(deployment));

        var stopped = new CopyOnWriteArrayList<String>();
        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            stopped.add(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                clusterState.addInstance(new InstanceInfo(
                        instanceId + "-repl",
                        "canary",
                        "node-1",
                        InstanceState.SCHEDULED,
                        25600 + stopped.size(),
                        0,
                        0,
                        Instant.now(),
                        2));
            });
            return true;
        });

        reconciler.rollingRestart(deployment);

        assertEquals(4, stopped.size());
        assertEquals(
                java.util.Set.of("canary-1", "canary-2", "canary-3", "canary-4"),
                new java.util.LinkedHashSet<>(stopped));
        verify(stateStore).updateDeploymentProgress(11, 1);
        verify(stateStore).updateDeploymentProgress(11, 2);
        verify(stateStore).updateDeploymentProgress(11, 3);
        verify(stateStore).updateDeploymentProgress(11, 4);
        verify(stateStore).updateDeploymentState(11, "COMPLETED");
    }

    @Test
    void rollingRestartFailsWhenHealthGateTimesOut() {
        var deployment = new DeploymentRecord(
                12,
                "healthgate",
                2,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{\"group\":\"healthgate\",\"strategy\":\"ROLLING\",\"canaryInstances\":1,"
                        + "\"healthGateEnabled\":true,\"promotionTimeoutSeconds\":1}",
                2,
                0,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(new InstanceInfo(
                "healthgate-1", "healthgate", "node-1", InstanceState.RUNNING, 25565, 0, 0, Instant.now(), 0));
        clusterState.addInstance(new InstanceInfo(
                "healthgate-2", "healthgate", "node-1", InstanceState.RUNNING, 25566, 0, 0, Instant.now(), 0));
        when(stateStore.getDeployment("healthgate", 2)).thenReturn(Optional.of(deployment), Optional.of(deployment));

        var stopped = new CopyOnWriteArrayList<String>();
        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            stopped.add(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            Thread.startVirtualThread(() -> clusterState.addInstance(new InstanceInfo(
                    "healthgate-repl-1",
                    "healthgate",
                    "node-1",
                    InstanceState.SCHEDULED,
                    25567,
                    0,
                    0,
                    Instant.now(),
                    2)));
            return true;
        });

        reconciler.rollingRestart(deployment);

        assertEquals(List.of("healthgate-1"), stopped);
        verify(stateStore).updateDeploymentProgress(12, 1);
        verify(stateStore).updateDeploymentState(12, "FAILED");
        verify(stateStore, never()).updateDeploymentState(12, "COMPLETED");
    }

    @Test
    void rollingRestartWaitsForMinimumHealthyUptimeBeforePromoting() {
        var deployment = new DeploymentRecord(
                15,
                "stablegate",
                2,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{\"group\":\"stablegate\",\"strategy\":\"ROLLING\",\"canaryInstances\":1,"
                        + "\"healthGateEnabled\":true,\"promotionTimeoutSeconds\":2,\"minHealthySeconds\":2}",
                2,
                1,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(new InstanceInfo(
                "stablegate-1", "stablegate", "node-1", InstanceState.RUNNING, 25568, 0, 2500, Instant.now(), 2));
        clusterState.addInstance(new InstanceInfo(
                "stablegate-2", "stablegate", "node-1", InstanceState.RUNNING, 25569, 0, 0, Instant.now(), 0));
        when(stateStore.getDeployment("stablegate", 2)).thenReturn(Optional.of(deployment), Optional.of(deployment));

        var stopped = new CopyOnWriteArrayList<String>();
        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            stopped.add(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            Thread.startVirtualThread(() -> {
                clusterState.addInstance(new InstanceInfo(
                        "stablegate-repl-1",
                        "stablegate",
                        "node-1",
                        InstanceState.RUNNING,
                        25570,
                        0,
                        1000,
                        Instant.now(),
                        2));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                clusterState.updateInstanceStatus("stablegate-repl-1", InstanceState.RUNNING, 0, 2000);
            });
            return true;
        });

        reconciler.rollingRestart(deployment);

        assertEquals(List.of("stablegate-2"), stopped);
        verify(stateStore).updateDeploymentProgress(15, 2);
        verify(stateStore).updateDeploymentState(15, "COMPLETED");
    }

    @Test
    void rollingRestartAutoRollsBackWhenHealthGateTimesOut() {
        var deployment = new DeploymentRecord(
                13,
                "autorollback",
                2,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{\"group\":\"autorollback\",\"strategy\":\"ROLLING\",\"canaryInstances\":1,"
                        + "\"healthGateEnabled\":true,\"autoRollbackOnFailure\":true,"
                        + "\"promotionTimeoutSeconds\":1}",
                2,
                0,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(new InstanceInfo(
                "autorollback-1", "autorollback", "node-1", InstanceState.RUNNING, 25575, 0, 0, Instant.now(), 0));
        clusterState.addInstance(new InstanceInfo(
                "autorollback-2", "autorollback", "node-1", InstanceState.RUNNING, 25576, 0, 0, Instant.now(), 0));
        when(stateStore.getDeployment("autorollback", 2)).thenReturn(Optional.of(deployment), Optional.of(deployment));

        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            Thread.startVirtualThread(() -> clusterState.addInstance(new InstanceInfo(
                    "autorollback-repl-1",
                    "autorollback",
                    "node-1",
                    InstanceState.SCHEDULED,
                    25577,
                    0,
                    0,
                    Instant.now(),
                    2)));
            return true;
        });

        reconciler.rollingRestart(deployment);

        verify(stateStore).updateDeploymentProgress(13, 1);
        verify(stateStore).updateDeploymentState(13, "ROLLED_BACK");
        verify(stateStore, never()).updateDeploymentState(13, "COMPLETED");
    }

    @Test
    void rollingRestartFailsImmediatelyWhenUpdatedWaveCrashes() {
        var deployment = new DeploymentRecord(
                14,
                "crashgate",
                2,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{\"group\":\"crashgate\",\"strategy\":\"ROLLING\",\"canaryInstances\":1,"
                        + "\"healthGateEnabled\":true,\"promotionTimeoutSeconds\":5}",
                2,
                0,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(new InstanceInfo(
                "crashgate-1", "crashgate", "node-1", InstanceState.RUNNING, 25585, 0, 0, Instant.now(), 0));
        clusterState.addInstance(new InstanceInfo(
                "crashgate-2", "crashgate", "node-1", InstanceState.RUNNING, 25586, 0, 0, Instant.now(), 0));
        when(stateStore.getDeployment("crashgate", 2)).thenReturn(Optional.of(deployment), Optional.of(deployment));

        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                clusterState.addInstance(new InstanceInfo(
                        "crashgate-repl-1",
                        "crashgate",
                        "node-1",
                        InstanceState.CRASHED,
                        25587,
                        0,
                        0,
                        Instant.now(),
                        2));
            });
            return true;
        });

        reconciler.rollingRestart(deployment);

        verify(stateStore).updateDeploymentProgress(14, 1);
        verify(stateStore).updateDeploymentState(14, "FAILED");
        verify(stateStore, never()).updateDeploymentState(14, "COMPLETED");
    }

    @Test
    void rollingRestartHaltsWhenReplacementNeverArrives() {
        // The footgun fix: a wave whose replacement is never scheduled (e.g. no capacity) must halt the
        // rollout, not "continue anyway" and keep stopping instances into an outage.
        var deployment = new DeploymentRecord(
                16,
                "stuck",
                2,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{\"group\":\"stuck\",\"strategy\":\"ROLLING\",\"replacementTimeoutSeconds\":1}",
                2,
                0,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(
                new InstanceInfo("stuck-1", "stuck", "node-1", InstanceState.RUNNING, 25595, 0, 0, Instant.now(), 0));
        clusterState.addInstance(
                new InstanceInfo("stuck-2", "stuck", "node-1", InstanceState.RUNNING, 25596, 0, 0, Instant.now(), 0));
        when(stateStore.getDeployment("stuck", 2)).thenReturn(Optional.of(deployment), Optional.of(deployment));

        var stopped = new CopyOnWriteArrayList<String>();
        // No replacement is ever scheduled — no new-revision instance appears.
        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            stopped.add(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            return true;
        });

        reconciler.rollingRestart(deployment);

        // Only one instance was stopped — the rollout halted instead of cascading into the second.
        assertEquals(1, stopped.size());
        verify(stateStore).updateDeploymentProgress(16, 1);
        verify(stateStore).updateDeploymentState(16, "FAILED");
        verify(stateStore, never()).updateDeploymentState(16, "COMPLETED");
    }

    @Test
    void rollingRestartHaltsWhenReplacementCrashesEvenWithoutHealthGate() {
        // A crashed replacement is now caught during the replacement wait itself, so a crash-looping new
        // revision halts the rollout even when the (optional) health gate is disabled.
        var deployment = new DeploymentRecord(
                17,
                "crashnogate",
                2,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{\"group\":\"crashnogate\",\"strategy\":\"ROLLING\",\"replacementTimeoutSeconds\":5}",
                2,
                0,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(new InstanceInfo(
                "crashnogate-1", "crashnogate", "node-1", InstanceState.RUNNING, 25597, 0, 0, Instant.now(), 0));
        clusterState.addInstance(new InstanceInfo(
                "crashnogate-2", "crashnogate", "node-1", InstanceState.RUNNING, 25598, 0, 0, Instant.now(), 0));
        when(stateStore.getDeployment("crashnogate", 2)).thenReturn(Optional.of(deployment), Optional.of(deployment));

        var stopped = new CopyOnWriteArrayList<String>();
        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            stopped.add(instanceId);
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                clusterState.addInstance(new InstanceInfo(
                        "crashnogate-repl-1",
                        "crashnogate",
                        "node-1",
                        InstanceState.CRASHED,
                        25599,
                        0,
                        0,
                        Instant.now(),
                        2));
            });
            return true;
        });

        reconciler.rollingRestart(deployment);

        assertEquals(1, stopped.size());
        verify(stateStore).updateDeploymentState(17, "FAILED");
        verify(stateStore, never()).updateDeploymentState(17, "COMPLETED");
    }

    @Test
    void autoRollbackTriggersRollbackActionWithFailedDeployment() {
        var deployment = new DeploymentRecord(
                18,
                "rollbackfire",
                2,
                "manual",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{\"group\":\"rollbackfire\",\"strategy\":\"ROLLING\",\"canaryInstances\":1,"
                        + "\"healthGateEnabled\":true,\"autoRollbackOnFailure\":true,\"promotionTimeoutSeconds\":1}",
                2,
                0,
                Instant.now().toString(),
                null,
                null);
        clusterState.addInstance(new InstanceInfo(
                "rollbackfire-1", "rollbackfire", "node-1", InstanceState.RUNNING, 25700, 0, 0, Instant.now(), 0));
        clusterState.addInstance(new InstanceInfo(
                "rollbackfire-2", "rollbackfire", "node-1", InstanceState.RUNNING, 25701, 0, 0, Instant.now(), 0));
        when(stateStore.getDeployment("rollbackfire", 2)).thenReturn(Optional.of(deployment), Optional.of(deployment));

        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            // Replacement comes up SCHEDULED but never RUNNING → the health gate times out.
            Thread.startVirtualThread(() -> clusterState.addInstance(new InstanceInfo(
                    "rollbackfire-repl", "rollbackfire", "node-1", InstanceState.SCHEDULED, 25702, 0, 0, Instant.now(),
                    2)));
            return true;
        });
        var rolledBack = new CopyOnWriteArrayList<DeploymentRecord>();
        reconciler.setRollbackAction(rolledBack::add);

        reconciler.rollingRestart(deployment);

        verify(stateStore).updateDeploymentState(18, "ROLLED_BACK");
        assertEquals(1, rolledBack.size());
        assertEquals(2, rolledBack.getFirst().revision());
    }

    @Test
    void rollbackDeploymentDoesNotAutoRollBackAgain() {
        // A rollback deployment that fails its own health gate halts as FAILED (manual intervention) and
        // must not trigger another rollback — otherwise a bad known-good config loops forever.
        var deployment = new DeploymentRecord(
                19,
                "noloop",
                2,
                "rollback",
                "ROLLING",
                "IN_PROGRESS",
                "{}",
                "{\"group\":\"noloop\",\"strategy\":\"ROLLING\",\"canaryInstances\":1,"
                        + "\"healthGateEnabled\":true,\"autoRollbackOnFailure\":true,\"promotionTimeoutSeconds\":1}",
                2,
                0,
                Instant.now().toString(),
                null,
                1);
        clusterState.addInstance(new InstanceInfo(
                "noloop-1", "noloop", "node-1", InstanceState.RUNNING, 25710, 0, 0, Instant.now(), 0));
        clusterState.addInstance(new InstanceInfo(
                "noloop-2", "noloop", "node-1", InstanceState.RUNNING, 25711, 0, 0, Instant.now(), 0));
        when(stateStore.getDeployment("noloop", 2)).thenReturn(Optional.of(deployment), Optional.of(deployment));

        var reconciler = new DeploymentReconciler(clusterState, stateStore, eventBus, 1, (instanceId, force) -> {
            clusterState.updateInstanceState(instanceId, InstanceState.STOPPING);
            Thread.startVirtualThread(() -> clusterState.addInstance(new InstanceInfo(
                    "noloop-repl", "noloop", "node-1", InstanceState.SCHEDULED, 25712, 0, 0, Instant.now(), 2)));
            return true;
        });
        var rolledBack = new CopyOnWriteArrayList<DeploymentRecord>();
        reconciler.setRollbackAction(rolledBack::add);

        reconciler.rollingRestart(deployment);

        verify(stateStore).updateDeploymentState(19, "FAILED");
        verify(stateStore, never()).updateDeploymentState(19, "ROLLED_BACK");
        assertTrue(rolledBack.isEmpty());
    }
}
