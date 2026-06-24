package me.prexorjustin.prexorcloud.controller.metrics;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.controller.crash.CrashStore;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.group.GroupManager;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleManager;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleRuntimeFactory;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleStore;
import me.prexorjustin.prexorcloud.controller.state.ClusterState;
import me.prexorjustin.prexorcloud.controller.state.HealingActionIntent;
import me.prexorjustin.prexorcloud.controller.state.NodeDrainIntent;
import me.prexorjustin.prexorcloud.controller.state.StartRetryIntent;
import me.prexorjustin.prexorcloud.controller.state.StateStore;
import me.prexorjustin.prexorcloud.controller.state.WorkflowStateStore;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("MetricsCollector")
class MetricsCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("exports workflow, module, extension, capability, and planning metrics")
    void exportsPlatformOperationalMetrics() throws Exception {
        WorkflowStateStore workflowStateStore = new WorkflowStateStore();
        workflowStateStore.queueTransfer(UUID.randomUUID(), "lobby-1");
        workflowStateStore.saveNodeDrain(new NodeDrainIntent(
                "node-a", false, "", Instant.now(), Instant.now().plusSeconds(60), Set.of("lobby-1")));
        workflowStateStore.saveHealingAction(new HealingActionIntent("lobby-1", "lobby", "crashed", Instant.now()));
        workflowStateStore.saveStartRetry(new StartRetryIntent(
                "lobby-1", "lobby", "node-a", "transient", "plan-1", 1, Instant.now(), Instant.now()));

        PlatformModuleManager platformModuleManager = new PlatformModuleManager(
                new PlatformModuleStore(tempDir.resolve("platform-store")),
                storedModule -> new PlatformModuleRuntimeFactory.LoadedRuntime(
                        storedModule.manifest(), new PlatformModule() {}, () -> {}));
        platformModuleManager.install(createModuleJar(tempDir.resolve("metrics-module.jar")));

        MetricsCollector collector = new MetricsCollector(
                new ClusterState(new EventBus(), null),
                new GroupManager(null),
                new CrashStore(10),
                workflowStateStore,
                platformModuleManager);
        collector.recordCompositionPlanningFailure();

        MeterRegistry registry = collector.registry();
        assertGauge(registry, "prexorcloud.workflows.pending_transfers", 1.0);
        assertGauge(registry, "prexorcloud.workflows.node_drains", 1.0);
        assertGauge(registry, "prexorcloud.workflows.healing_actions", 1.0);
        assertGauge(registry, "prexorcloud.workflows.start_retries", 1.0);
        assertGauge(registry, "prexorcloud.platform_modules.total", 1.0);
        assertEquals(
                1.0,
                registry.find("prexorcloud.platform_modules.state")
                        .tag("state", "active")
                        .gauge()
                        .value());
        assertGauge(registry, "prexorcloud.platform_extensions.total", 1.0);
        assertGauge(registry, "prexorcloud.platform_extension_variants.total", 2.0);
        assertGauge(registry, "prexorcloud.capabilities.unresolved_requirements", 0.0);
        assertEquals(
                1.0,
                registry.find("prexorcloud.composition.planning.failures")
                        .counter()
                        .count());
    }

    @Test
    @DisplayName("exports the state-store epoch fence rejection counter")
    void exportsStateStoreFenceCounter() {
        MetricsCollector collector = new MetricsCollector(
                new ClusterState(new EventBus(), null), new GroupManager(null), new CrashStore(10));
        StateStore stateStore = org.mockito.Mockito.mock(StateStore.class);
        org.mockito.Mockito.when(stateStore.fencedWriteRejections()).thenReturn(3L);

        collector.registerStateStoreFenceMetrics(stateStore);

        assertEquals(
                3.0,
                collector.registry()
                        .find("prexorcloud.statestore.fenced_write_rejections")
                        .functionCounter()
                        .count());
    }

    @Test
    @DisplayName("records scheduler tick + lease + jwt + http + daemon counters")
    void recordsOperationalCounters() {
        MetricsCollector collector = new MetricsCollector(
                new ClusterState(new EventBus(), null), new GroupManager(null), new CrashStore(10));

        collector.recordSchedulerTick(Duration.ofMillis(5), true, 4);
        collector.recordSchedulerTick(Duration.ofMillis(2), false, 0);
        collector.recordLeaseAcquisition();
        collector.recordLeaseRenewal();
        collector.recordLeaseRenewal();
        collector.recordLeaseContention();
        collector.recordJwtRevocation();
        collector.recordDaemonInbound("HEARTBEAT");
        collector.recordDaemonOutbound("START_INSTANCE", true);
        collector.recordDaemonOutbound("STOP_INSTANCE", false);
        collector.recordHttpRequest("GET", 200, Duration.ofMillis(8));
        collector.recordHttpRequest("POST", 422, Duration.ofMillis(3));
        collector.recordHttpRequest("GET", 502, Duration.ofMillis(40));

        MeterRegistry registry = collector.registry();
        assertEquals(
                2, registry.find("prexorcloud.scheduler.tick.duration").timer().count());
        assertEquals(
                1.0,
                registry.find("prexorcloud.scheduler.tick.failures").counter().count());
        assertEquals(
                1.0,
                registry.find("prexorcloud.coordination.lease.acquisitions")
                        .counter()
                        .count());
        assertEquals(
                2.0,
                registry.find("prexorcloud.coordination.lease.renewals")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.find("prexorcloud.coordination.lease.contentions")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.find("prexorcloud.coordination.jwt.revocations")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.find("prexorcloud.grpc.daemon.messages_in")
                        .tag("payload_case", "heartbeat")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.find("prexorcloud.grpc.daemon.messages_out")
                        .tag("outcome", "delivered")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.find("prexorcloud.grpc.daemon.messages_out")
                        .tag("outcome", "dropped")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.find("prexorcloud.http.requests")
                        .tag("status_class", "2xx")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.find("prexorcloud.http.requests")
                        .tag("status_class", "4xx")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.find("prexorcloud.http.requests")
                        .tag("status_class", "5xx")
                        .counter()
                        .count());
    }

    @Test
    @DisplayName("registers SSE streamer probe gauges from a live probe")
    void registersSseStreamerProbeGauges() {
        MetricsCollector collector = new MetricsCollector(
                new ClusterState(new EventBus(), null), new GroupManager(null), new CrashStore(10));
        collector.registerSseStreamerMetrics(new MetricsCollector.SseStreamerProbe() {
            @Override
            public int clientCount() {
                return 3;
            }

            @Override
            public long latestSequence() {
                return 100;
            }

            @Override
            public long earliestSequence() {
                return 51;
            }
        });

        MeterRegistry registry = collector.registry();
        assertEquals(
                3.0, registry.find("prexorcloud.sse.clients.connected").gauge().value());
        assertEquals(
                50.0,
                registry.find("prexorcloud.sse.replay.buffer_size").gauge().value());
    }

    @Test
    @DisplayName("registers leadership / convergence / change-stream probe gauges and counters")
    void registersLeadershipProbeMetrics() {
        MetricsCollector collector = new MetricsCollector(
                new ClusterState(new EventBus(), null), new GroupManager(null), new CrashStore(10));
        collector.registerLeadershipMetrics(new MetricsCollector.LeadershipMetricsProbe() {
            @Override
            public boolean isLeader() {
                return true;
            }

            @Override
            public long currentEpoch() {
                return 7;
            }

            @Override
            public long leadershipTransitions() {
                return 3;
            }

            @Override
            public long renewAgeMillis() {
                return 1200;
            }

            @Override
            public boolean isObserving() {
                return true;
            }

            @Override
            public long lastObservationDurationMillis() {
                return 4500;
            }

            @Override
            public boolean changeStreamRunning() {
                return true;
            }

            @Override
            public long changeStreamChangesObserved() {
                return 11;
            }

            @Override
            public long changeStreamFullResyncs() {
                return 2;
            }

            @Override
            public long changeStreamOpens() {
                return 5;
            }

            @Override
            public long changeStreamLastEventAgeMillis() {
                return 800;
            }
        });

        MeterRegistry registry = collector.registry();
        assertGauge(registry, "prexorcloud.leadership.is_leader", 1.0);
        assertGauge(registry, "prexorcloud.leadership.epoch", 7.0);
        assertGauge(registry, "prexorcloud.leadership.renew_age.millis", 1200.0);
        assertEquals(
                3.0,
                registry.find("prexorcloud.leadership.transitions")
                        .functionCounter()
                        .count());
        assertGauge(registry, "prexorcloud.convergence.observing", 1.0);
        assertGauge(registry, "prexorcloud.convergence.last_observation.millis", 4500.0);
        assertGauge(registry, "prexorcloud.changestream.running", 1.0);
        assertGauge(registry, "prexorcloud.changestream.last_event_age.millis", 800.0);
        assertEquals(
                11.0,
                registry.find("prexorcloud.changestream.changes")
                        .functionCounter()
                        .count());
        assertEquals(
                2.0,
                registry.find("prexorcloud.changestream.full_resyncs")
                        .functionCounter()
                        .count());
        assertEquals(
                5.0,
                registry.find("prexorcloud.changestream.opens")
                        .functionCounter()
                        .count());
    }

    private static void assertGauge(MeterRegistry registry, String name, double expected) {
        assertEquals(expected, registry.find(name).gauge().value());
    }

    private static Path createModuleJar(Path jarPath) throws IOException {
        String manifest = """
                manifestVersion: 1
                id: metrics-module
                version: 1.0.0
                backend:
                  entrypoint: example.MetricsModule
                extensions:
                  - id: metrics-paper
                    target: server/paper
                    activation: default-enabled
                    variants:
                      - id: metrics-paper-1-20
                        mcVersionRange: "[1.20,1.21)"
                        runtimeApiVersion: 1
                        artifact: extensions/metrics-paper-1.20.jar
                        sha256: "1111111111111111111111111111111111111111111111111111111111111111"
                        installPath: plugins/
                      - id: metrics-paper-1-21
                        mcVersionRange: "[1.21,1.22)"
                        runtimeApiVersion: 1
                        artifact: extensions/metrics-paper-1.21.jar
                        sha256: "2222222222222222222222222222222222222222222222222222222222222222"
                        installPath: plugins/
                """;
        try (JarOutputStream jar = new JarOutputStream(java.nio.file.Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("META-INF/prexor/module.yaml"));
            jar.write(manifest.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("extensions/metrics-paper-1.20.jar"));
            jar.write(new byte[] {1});
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("extensions/metrics-paper-1.21.jar"));
            jar.write(new byte[] {2});
            jar.closeEntry();
        }
        return jarPath;
    }
}
