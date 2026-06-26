package me.prexorjustin.prexorcloud.tests;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import me.prexorjustin.prexorcloud.controller.deployment.DeploymentRecord;
import me.prexorjustin.prexorcloud.controller.scheduler.composition.InstanceCompositionPlan;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.NodeState;
import me.prexorjustin.prexorcloud.harness.PlatformModuleTestJarFactory;
import me.prexorjustin.prexorcloud.harness.RestClient;
import me.prexorjustin.prexorcloud.harness.TestCluster;
import me.prexorjustin.prexorcloud.modules.runtime.ModuleLifecycleManager;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Restart/recovery integration scenarios for Phase 1 verification.
 */
class RecoveryTest {

    @Test
    void daemonRestartRemovesAndReRegistersNode() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");
        try (TestCluster cluster = TestCluster.start(1)) {
            String nodeId = "test-node-1";

            assertTrue(cluster.controller().clusterState().getNode(nodeId).isPresent());

            cluster.stopDaemon(nodeId);
            assertTrue(cluster.controller().clusterState().getNode(nodeId).isEmpty());

            cluster.restartDaemon(nodeId);
            cluster.waitForNode(nodeId, 15_000);

            assertTrue(cluster.controller().clusterState().getNode(nodeId).isPresent());
        }
    }

    @Test
    void controllerRestartPreservesPersistedCompositionPlan() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");
        try (TestCluster cluster = TestCluster.start(1)) {
            RestClient admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

            admin.post(
                    "/api/v1/groups",
                    Map.of(
                            "name",
                            "recover-lobby",
                            "platform",
                            "PAPER",
                            "platformVersion",
                            "1.21.1",
                            "minInstances",
                            0,
                            "maxInstances",
                            1,
                            "maxPlayers",
                            25));

            cluster.controller().scheduler().scheduleOne("recover-lobby");
            cluster.waitForCondition(
                    "scheduled recover-lobby instance",
                    15_000,
                    () -> !cluster.controller()
                            .clusterState()
                            .getInstancesByGroup("recover-lobby")
                            .isEmpty());

            String instanceId = cluster.controller()
                    .clusterState()
                    .getInstancesByGroup("recover-lobby")
                    .getFirst()
                    .id();

            cluster.waitForCondition(
                    "persisted composition plan for " + instanceId,
                    15_000,
                    () -> cluster.controller()
                            .stateStore()
                            .getInstanceCompositionPlan(instanceId)
                            .isPresent());

            var beforeRestart = cluster.controller()
                    .stateStore()
                    .getInstanceCompositionPlan(instanceId)
                    .orElseThrow();
            assertEquals("recover-lobby", beforeRestart.groupName());
            assertFalse(beforeRestart.planHash().isBlank());
            assertEquals(instanceId, beforeRestart.instanceId());

            cluster.restartController();
            cluster.waitForNode("test-node-1", 15_000);

            RestClient restartedAdmin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
            var response = restartedAdmin.get("/api/v1/services/" + instanceId + "/composition");

            assertEquals(200, response.status(), response.body());
            assertEquals(instanceId, response.json().get("instanceId").asText());
            assertEquals(
                    beforeRestart.planHash(), response.json().get("planHash").asText());
            assertEquals("recover-lobby", response.json().get("groupName").asText());
        }
    }

    @Test
    void standbyControllerPreservesPersistedCompositionPlan() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");
        try (TestCluster cluster = TestCluster.startHa(1, 2)) {
            RestClient admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

            admin.post(
                    "/api/v1/groups",
                    Map.of(
                            "name",
                            "ha-recover-lobby",
                            "platform",
                            "PAPER",
                            "platformVersion",
                            "1.21.1",
                            "minInstances",
                            0,
                            "maxInstances",
                            1,
                            "maxPlayers",
                            25));

            cluster.controller().scheduler().scheduleOne("ha-recover-lobby");
            cluster.waitForCondition(
                    "scheduled ha-recover-lobby instance",
                    15_000,
                    () -> !cluster.controller()
                            .clusterState()
                            .getInstancesByGroup("ha-recover-lobby")
                            .isEmpty());

            String instanceId = cluster.controller()
                    .clusterState()
                    .getInstancesByGroup("ha-recover-lobby")
                    .getFirst()
                    .id();

            cluster.waitForCondition(
                    "persisted composition plan before failover for " + instanceId,
                    15_000,
                    () -> cluster.controller()
                            .stateStore()
                            .getInstanceCompositionPlan(instanceId)
                            .isPresent());

            var beforeFailover = cluster.controller()
                    .stateStore()
                    .getInstanceCompositionPlan(instanceId)
                    .orElseThrow();

            cluster.failoverController();
            cluster.waitForNode("test-node-1", 20_000);

            RestClient standbyAdmin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
            var response = standbyAdmin.get("/api/v1/services/" + instanceId + "/composition");

            assertEquals(200, response.status(), response.body());
            assertEquals(instanceId, response.json().get("instanceId").asText());
            assertEquals(
                    beforeFailover.planHash(), response.json().get("planHash").asText());
            assertEquals("ha-recover-lobby", response.json().get("groupName").asText());
        }
    }

    @Test
    void standbyControllerConvergesDesiredStatePlacementAfterFailover() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");
        try (TestCluster cluster = TestCluster.startHa(1, 2)) {
            RestClient admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
            String groupName = "ha-placement-lobby";

            admin.post(
                    "/api/v1/groups",
                    Map.of(
                            "name",
                            groupName,
                            "platform",
                            "PAPER",
                            "platformVersion",
                            "1.21.1",
                            "minInstances",
                            1,
                            "maxInstances",
                            1,
                            "maxPlayers",
                            25));

            assertTrue(cluster.controller()
                    .clusterState()
                    .getInstancesByGroup(groupName)
                    .isEmpty());

            cluster.failoverController();
            cluster.waitForNode("test-node-1", 20_000);
            cluster.waitForCondition(
                    "standby placement convergence for " + groupName,
                    20_000,
                    () -> cluster.controller()
                                            .clusterState()
                                            .getInstancesByGroup(groupName)
                                            .size()
                                    == 1
                            && cluster.controller()
                                    .stateStore()
                                    .getInstanceCompositionPlan(cluster.controller()
                                            .clusterState()
                                            .getInstancesByGroup(groupName)
                                            .getFirst()
                                            .id())
                                    .isPresent());

            var placedInstance = cluster.controller()
                    .clusterState()
                    .getInstancesByGroup(groupName)
                    .getFirst();
            assertEquals("test-node-1", placedInstance.nodeId());
            assertTrue(cluster.controller()
                    .stateStore()
                    .getInstanceCompositionPlan(placedInstance.id())
                    .isPresent());
        }
    }

    @Test
    void controllerRestartRecoversPersistedNodeDrainAfterDaemonReconnect() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");
        try (TestCluster cluster = TestCluster.start(1)) {
            RestClient admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
            String nodeId = "test-node-1";
            String instanceId = "recover-drain-1";

            cluster.controller()
                    .clusterState()
                    .addInstance(new InstanceInfo(
                            instanceId, "recover-lobby", nodeId, InstanceState.RUNNING, 25565, 1, 0, Instant.now()));

            var drainResponse = admin.postEmpty("/api/v1/nodes/" + nodeId + "/drain?shutdown=false&timeout=30");
            assertEquals(200, drainResponse.status(), drainResponse.body());

            cluster.waitForCondition(
                    "persisted node drain intent",
                    10_000,
                    () -> cluster.controller()
                            .workflowStateStore()
                            .getNodeDrain(nodeId)
                            .isPresent());
            assertEquals(
                    InstanceState.DRAINING,
                    cluster.controller()
                            .clusterState()
                            .getInstance(instanceId)
                            .orElseThrow()
                            .state());

            cluster.restartController();
            cluster.waitForNode(nodeId, 15_000);
            cluster.waitForCondition(
                    "drain recovery to cordon node " + nodeId,
                    20_000,
                    () -> cluster.controller()
                                    .workflowStateStore()
                                    .getNodeDrain(nodeId)
                                    .isEmpty()
                            && cluster.controller()
                                    .clusterState()
                                    .getNode(nodeId)
                                    .map(node -> node.status() == NodeState.NodeStatus.CORDONED)
                                    .orElse(false));

            assertTrue(cluster.controller()
                    .workflowStateStore()
                    .getNodeDrain(nodeId)
                    .isEmpty());
            assertEquals(
                    NodeState.NodeStatus.CORDONED,
                    cluster.controller()
                            .clusterState()
                            .getNode(nodeId)
                            .orElseThrow()
                            .status());
        }
    }

    @Test
    void controllerRestartContinuesPersistedDeploymentThroughRedisRecovery() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");
        try (TestCluster cluster = TestCluster.start(1)) {
            String nodeId = "test-node-1";
            cluster.controller()
                    .clusterState()
                    .addInstance(new InstanceInfo(
                            "recover-deploy-1",
                            "recover-deploy",
                            nodeId,
                            InstanceState.RUNNING,
                            25565,
                            1,
                            0,
                            Instant.now(),
                            2));
            cluster.controller()
                    .clusterState()
                    .addInstance(new InstanceInfo(
                            "recover-deploy-2",
                            "recover-deploy",
                            nodeId,
                            InstanceState.RUNNING,
                            25566,
                            1,
                            0,
                            Instant.now(),
                            0));
            cluster.controller()
                    .stateStore()
                    .createDeployment(new DeploymentRecord(
                            0,
                            "recover-deploy",
                            2,
                            "manual",
                            "ROLLING",
                            "IN_PROGRESS",
                            "{}",
                            "{}",
                            2,
                            1,
                            Instant.now().toString(),
                            null,
                            null));

            cluster.restartController();
            cluster.waitForNode(nodeId, 15_000);
            cluster.waitForCondition(
                    "deployment recovery for recover-deploy",
                    20_000,
                    () -> cluster.controller()
                                    .stateStore()
                                    .getDeployment("recover-deploy", 2)
                                    .map(deployment -> "COMPLETED".equals(deployment.state())
                                            && deployment.updatedInstances() == 2)
                                    .orElse(false)
                            && cluster.controller()
                                    .clusterState()
                                    .getInstance("recover-deploy-2")
                                    .map(instance -> instance.state() == InstanceState.STOPPING)
                                    .orElse(false));

            var recovered = cluster.controller()
                    .stateStore()
                    .getDeployment("recover-deploy", 2)
                    .orElseThrow();
            assertEquals("COMPLETED", recovered.state());
            assertEquals(2, recovered.updatedInstances());
        }
    }

    @Test
    void standbyControllerFailoverRecoversPersistedNodeDrainAfterDaemonReconnect() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");
        try (TestCluster cluster = TestCluster.startHa(1, 2)) {
            RestClient admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
            String nodeId = "test-node-1";
            String instanceId = "failover-drain-1";

            cluster.controller()
                    .clusterState()
                    .addInstance(new InstanceInfo(
                            instanceId, "failover-lobby", nodeId, InstanceState.RUNNING, 25569, 1, 0, Instant.now()));

            var drainResponse = admin.postEmpty("/api/v1/nodes/" + nodeId + "/drain?shutdown=false&timeout=30");
            assertEquals(200, drainResponse.status(), drainResponse.body());

            cluster.waitForCondition(
                    "persisted node drain intent before failover",
                    10_000,
                    () -> cluster.controller()
                            .workflowStateStore()
                            .getNodeDrain(nodeId)
                            .isPresent());

            cluster.failoverController();
            cluster.waitForNode(nodeId, 20_000);
            cluster.waitForCondition(
                    "standby controller drain recovery to cordon node " + nodeId,
                    20_000,
                    () -> cluster.controller()
                                    .workflowStateStore()
                                    .getNodeDrain(nodeId)
                                    .isEmpty()
                            && cluster.controller()
                                    .clusterState()
                                    .getNode(nodeId)
                                    .map(node -> node.status() == NodeState.NodeStatus.CORDONED)
                                    .orElse(false));

            assertEquals(
                    NodeState.NodeStatus.CORDONED,
                    cluster.controller()
                            .clusterState()
                            .getNode(nodeId)
                            .orElseThrow()
                            .status());
        }
    }

    @Test
    void standbyControllerFailoverContinuesPersistedDeploymentAfterDaemonReconnect() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");
        try (TestCluster cluster = TestCluster.startHa(1, 2)) {
            String nodeId = "test-node-1";
            cluster.controller()
                    .clusterState()
                    .addInstance(new InstanceInfo(
                            "failover-deploy-1",
                            "failover-deploy",
                            nodeId,
                            InstanceState.RUNNING,
                            25570,
                            1,
                            0,
                            Instant.now(),
                            2));
            cluster.controller()
                    .clusterState()
                    .addInstance(new InstanceInfo(
                            "failover-deploy-2",
                            "failover-deploy",
                            nodeId,
                            InstanceState.RUNNING,
                            25571,
                            1,
                            0,
                            Instant.now(),
                            0));
            cluster.controller()
                    .stateStore()
                    .createDeployment(new DeploymentRecord(
                            0,
                            "failover-deploy",
                            2,
                            "manual",
                            "ROLLING",
                            "IN_PROGRESS",
                            "{}",
                            "{}",
                            2,
                            1,
                            Instant.now().toString(),
                            null,
                            null));

            cluster.failoverController();
            cluster.waitForNode(nodeId, 20_000);
            cluster.waitForCondition(
                    "standby deployment recovery for failover-deploy",
                    20_000,
                    () -> cluster.controller()
                                    .stateStore()
                                    .getDeployment("failover-deploy", 2)
                                    .map(deployment -> "COMPLETED".equals(deployment.state())
                                            && deployment.updatedInstances() == 2)
                                    .orElse(false)
                            && cluster.controller()
                                    .clusterState()
                                    .getInstance("failover-deploy-2")
                                    .map(instance -> instance.state() == InstanceState.STOPPING)
                                    .orElse(false));

            var recovered = cluster.controller()
                    .stateStore()
                    .getDeployment("failover-deploy", 2)
                    .orElseThrow();
            assertEquals("COMPLETED", recovered.state());
            assertEquals(2, recovered.updatedInstances());
        }
    }

    @Test
    void standbyControllerRedispatchesRecoverableStartAfterFailover() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");
        Assumptions.assumeTrue(
                ToolProvider.getSystemJavaCompiler() != null,
                "A full JDK is required for recoverable start harness tests");

        try (TestCluster cluster = TestCluster.startHa(1, 2)) {
            RestClient admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
            String nodeId = "test-node-1";
            String groupName = "ha-recover-start";
            String instanceId = "ha-recover-start-1";

            admin.post(
                    "/api/v1/groups",
                    Map.of(
                            "name",
                            groupName,
                            "platform",
                            "PAPER",
                            "platformVersion",
                            "1.21.1",
                            "minInstances",
                            0,
                            "maxInstances",
                            1,
                            "maxPlayers",
                            25));

            var runtimeJar = createRecoverableRuntimeJar(cluster.workDir().resolve("ha-recovery-runtime"));
            var runtimeServer = serveJar(runtimeJar);
            try {
                cluster.controller()
                        .clusterState()
                        .addInstance(new InstanceInfo(
                                instanceId, groupName, nodeId, InstanceState.SCHEDULED, 25572, 0, 0, Instant.now()));
                cluster.controller()
                        .stateStore()
                        .saveInstanceCompositionPlan(new InstanceCompositionPlan(
                                instanceId,
                                groupName,
                                nodeId,
                                25572,
                                256,
                                new InstanceCompositionPlan.RuntimeIsolation(0.0, 0),
                                java.util.List.of(),
                                Map.of(),
                                false,
                                java.util.List.of(),
                                java.util.List.of(),
                                new InstanceCompositionPlan.ResolvedRuntime(
                                        "runtime.jar",
                                        "http://127.0.0.1:"
                                                + runtimeServer.getAddress().getPort() + "/runtime.jar",
                                        "",
                                        "",
                                        "",
                                        "SERVER",
                                        "",
                                        ""),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.Map.of(),
                                "ha-recover-start-plan",
                                Instant.now()));

                cluster.failoverController();
                cluster.waitForNode(nodeId, 20_000);
                cluster.waitForCondition(
                        "standby recoverable start redispatch for " + instanceId,
                        20_000,
                        () -> cluster.controller()
                                        .clusterState()
                                        .getInstance(instanceId)
                                        .map(instance -> instance.state() == InstanceState.STARTING
                                                || instance.state() == InstanceState.STOPPED)
                                        .orElse(false)
                                && java.nio.file.Files.isDirectory(cluster.workDir()
                                        .resolve("daemon-" + nodeId)
                                        .resolve("instances")
                                        .resolve(groupName)
                                        .resolve(instanceId)));

                assertTrue(java.nio.file.Files.isDirectory(cluster.workDir()
                        .resolve("daemon-" + nodeId)
                        .resolve("instances")
                        .resolve(groupName)
                        .resolve(instanceId)));
                assertTrue(cluster.controller()
                        .clusterState()
                        .getInstance(instanceId)
                        .map(instance ->
                                instance.state() == InstanceState.STARTING || instance.state() == InstanceState.STOPPED)
                        .orElse(false));
            } finally {
                runtimeServer.stop(0);
            }
        }
    }

    @Test
    void controllerRestartRedispatchesRecoverableStartAfterDaemonReconnect() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");
        Assumptions.assumeTrue(
                ToolProvider.getSystemJavaCompiler() != null,
                "A full JDK is required for recoverable start harness tests");

        try (TestCluster cluster = TestCluster.start(1)) {
            RestClient admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
            String nodeId = "test-node-1";
            String groupName = "recover-start";
            String instanceId = "recover-start-1";

            admin.post(
                    "/api/v1/groups",
                    Map.of(
                            "name",
                            groupName,
                            "platform",
                            "PAPER",
                            "platformVersion",
                            "1.21.1",
                            "minInstances",
                            0,
                            "maxInstances",
                            1,
                            "maxPlayers",
                            25));

            var runtimeJar = createRecoverableRuntimeJar(cluster.workDir().resolve("recovery-runtime"));
            var runtimeServer = serveJar(runtimeJar);
            try {
                cluster.controller()
                        .clusterState()
                        .addInstance(new InstanceInfo(
                                instanceId, groupName, nodeId, InstanceState.SCHEDULED, 25568, 0, 0, Instant.now()));
                cluster.controller()
                        .stateStore()
                        .saveInstanceCompositionPlan(new InstanceCompositionPlan(
                                instanceId,
                                groupName,
                                nodeId,
                                25568,
                                256,
                                new InstanceCompositionPlan.RuntimeIsolation(0.0, 0),
                                java.util.List.of(),
                                Map.of(),
                                false,
                                java.util.List.of(),
                                java.util.List.of(),
                                new InstanceCompositionPlan.ResolvedRuntime(
                                        "runtime.jar",
                                        "http://127.0.0.1:"
                                                + runtimeServer.getAddress().getPort() + "/runtime.jar",
                                        "",
                                        "",
                                        "",
                                        "SERVER",
                                        "",
                                        ""),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.Map.of(),
                                "recover-start-plan",
                                Instant.now()));

                cluster.restartController();
                cluster.waitForNode(nodeId, 15_000);
                cluster.waitForCondition(
                        "recoverable start redispatch for " + instanceId,
                        20_000,
                        () -> cluster.controller()
                                        .clusterState()
                                        .getInstance(instanceId)
                                        .map(instance -> instance.state() == InstanceState.STARTING
                                                || instance.state() == InstanceState.STOPPED)
                                        .orElse(false)
                                && java.nio.file.Files.isDirectory(cluster.workDir()
                                        .resolve("daemon-" + nodeId)
                                        .resolve("instances")
                                        .resolve(groupName)
                                        .resolve(instanceId)));

                assertTrue(java.nio.file.Files.isDirectory(cluster.workDir()
                        .resolve("daemon-" + nodeId)
                        .resolve("instances")
                        .resolve(groupName)
                        .resolve(instanceId)));
                assertTrue(cluster.controller()
                        .clusterState()
                        .getInstance(instanceId)
                        .map(instance ->
                                instance.state() == InstanceState.STARTING || instance.state() == InstanceState.STOPPED)
                        .orElse(false));
            } finally {
                runtimeServer.stop(0);
            }
        }
    }

    @Test
    void controllerRestartReconcilesStaleRunningInstanceWhenDaemonReconnects() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");
        try (TestCluster cluster = TestCluster.start(1)) {
            String nodeId = "test-node-1";
            String instanceId = "reconnect-stale-1";

            cluster.controller()
                    .clusterState()
                    .addInstance(new InstanceInfo(
                            instanceId,
                            "recover-reconnect",
                            nodeId,
                            InstanceState.RUNNING,
                            25567,
                            1,
                            0,
                            Instant.now()));

            cluster.restartController();
            cluster.waitForNode(nodeId, 15_000);
            cluster.waitForCondition(
                    "daemon reconnect reconciliation for " + instanceId,
                    20_000,
                    () -> cluster.controller()
                            .clusterState()
                            .getInstance(instanceId)
                            .map(instance -> instance.state() == InstanceState.CRASHED)
                            .orElse(false));

            assertEquals(
                    InstanceState.CRASHED,
                    cluster.controller()
                            .clusterState()
                            .getInstance(instanceId)
                            .orElseThrow()
                            .state());
        }
    }

    private static Path createRecoverableRuntimeJar(Path directory) throws Exception {
        java.nio.file.Files.createDirectories(directory);
        Path sourceFile = directory.resolve("RecoverableMain.java");
        Path classFile = directory.resolve("RecoverableMain.class");
        Path jarFile = directory.resolve("runtime.jar");
        java.nio.file.Files.writeString(sourceFile, """
                public final class RecoverableMain {
                    public static void main(String[] args) throws Exception {
                        while (true) {
                            Thread.sleep(1000L);
                        }
                    }
                }
                """);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            var compilationUnits = fileManager.getJavaFileObjects(sourceFile.toFile());
            Boolean compiled = compiler.getTask(
                            null,
                            fileManager,
                            null,
                            java.util.List.of("-d", directory.toString()),
                            null,
                            compilationUnits)
                    .call();
            if (!Boolean.TRUE.equals(compiled)) {
                throw new IllegalStateException("Failed to compile recoverable runtime jar fixture");
            }
        }

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "RecoverableMain");
        try (JarOutputStream out = new JarOutputStream(java.nio.file.Files.newOutputStream(jarFile), manifest)) {
            out.putNextEntry(new JarEntry("RecoverableMain.class"));
            java.nio.file.Files.copy(classFile, out);
            out.closeEntry();
        }
        return jarFile;
    }

    private static HttpServer serveJar(Path jarFile) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] jarBytes = java.nio.file.Files.readAllBytes(jarFile);
        server.createContext("/runtime.jar", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, jarBytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(jarBytes);
            }
        });
        server.start();
        return server;
    }

    /**
     * Rolling-restart-mid-failover: a deployment is in progress with 2 of 4 instances
     * already restarted. The controller fails over. The standby controller resumes from
     * the persisted {@code updatedInstances=2} cursor and only restarts the remaining 2
     * outdated instances — never re-restarts the 2 that already moved to the new
     * revision.
     */
    @Test
    void rollingRestartResumesAfterControllerFailoverWithoutDuplicateRestarts() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");

        try (TestCluster cluster = TestCluster.startHa(1, 2)) {
            String nodeId = "test-node-1";
            String groupName = "ha-rolling-restart";

            // Two instances already on new revision (=updatedInstances cursor)
            cluster.controller()
                    .clusterState()
                    .addInstance(new InstanceInfo(
                            "ha-rr-1", groupName, nodeId, InstanceState.RUNNING, 25600, 1, 0, Instant.now(), 2));
            cluster.controller()
                    .clusterState()
                    .addInstance(new InstanceInfo(
                            "ha-rr-2", groupName, nodeId, InstanceState.RUNNING, 25601, 1, 0, Instant.now(), 2));
            // Two instances still on old revision
            cluster.controller()
                    .clusterState()
                    .addInstance(new InstanceInfo(
                            "ha-rr-3", groupName, nodeId, InstanceState.RUNNING, 25602, 1, 0, Instant.now(), 0));
            cluster.controller()
                    .clusterState()
                    .addInstance(new InstanceInfo(
                            "ha-rr-4", groupName, nodeId, InstanceState.RUNNING, 25603, 1, 0, Instant.now(), 0));

            cluster.controller()
                    .stateStore()
                    .createDeployment(new DeploymentRecord(
                            0,
                            groupName,
                            2,
                            "manual",
                            "ROLLING",
                            "IN_PROGRESS",
                            "{}",
                            "{}",
                            4,
                            2,
                            Instant.now().toString(),
                            null,
                            null));

            cluster.failoverController();
            cluster.waitForNode(nodeId, 20_000);

            cluster.waitForCondition(
                    "rolling restart completes after failover for " + groupName,
                    20_000,
                    () -> cluster.controller()
                            .stateStore()
                            .getDeployment(groupName, 2)
                            .map(deployment ->
                                    "COMPLETED".equals(deployment.state()) && deployment.updatedInstances() == 4)
                            .orElse(false));

            var recovered = cluster.controller()
                    .stateStore()
                    .getDeployment(groupName, 2)
                    .orElseThrow();
            assertEquals("COMPLETED", recovered.state());
            assertEquals(4, recovered.updatedInstances(), "all 4 instances marked updated");

            // The two already-updated instances must remain RUNNING (not restarted again).
            var inst1 =
                    cluster.controller().clusterState().getInstance("ha-rr-1").orElseThrow();
            var inst2 =
                    cluster.controller().clusterState().getInstance("ha-rr-2").orElseThrow();
            assertEquals(
                    InstanceState.RUNNING,
                    inst1.state(),
                    "ha-rr-1 must not be re-restarted (already on revision 2 before failover)");
            assertEquals(
                    InstanceState.RUNNING,
                    inst2.state(),
                    "ha-rr-2 must not be re-restarted (already on revision 2 before failover)");
            assertEquals(2, inst1.deploymentRevision(), "ha-rr-1 stays on revision 2");
            assertEquals(2, inst2.deploymentRevision(), "ha-rr-2 stays on revision 2");
        }
    }

    /**
     * Placement-time standby promotion: the active controller has persisted a composition
     * plan for a SCHEDULED instance but has not yet dispatched the start RPC when it dies.
     * The standby acquires the group lease, runs {@code reconcileRecoverableStartsForGroup}
     * via its lease-acquired listener, and dispatches the start using the same persisted
     * plan — no second composition plan is written and the instance reaches STARTING
     * (or STOPPED if the daemon binary fails to launch in-test).
     */
    @Test
    void standbyPromotionResumesPlacementAfterMidPlacementFailover() throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");

        try (TestCluster cluster = TestCluster.startHa(1, 2)) {
            RestClient admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());
            String nodeId = "test-node-1";
            String groupName = "ha-placement-transfer";

            admin.post(
                    "/api/v1/groups",
                    Map.of(
                            "name",
                            groupName,
                            "platform",
                            "PAPER",
                            "platformVersion",
                            "1.21.1",
                            "minInstances",
                            0,
                            "maxInstances",
                            1,
                            "maxPlayers",
                            25));

            CountDownLatch checkpointReached = new CountDownLatch(1);
            CountDownLatch goAhead = new CountDownLatch(1);
            cluster.controller().scheduler().placementCoordinator().preDispatchHookForTesting = () -> {
                checkpointReached.countDown();
                try {
                    goAhead.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("simulated mid-placement failover (test hook)");
            };

            AtomicReference<Throwable> placementError = new AtomicReference<>();
            Thread placementThread = Thread.ofVirtual()
                    .name("test-placement-driver")
                    .start(() -> {
                        try {
                            cluster.controller().scheduler().scheduleOne(groupName);
                        } catch (Throwable t) {
                            placementError.set(t);
                        }
                    });

            assertTrue(
                    checkpointReached.await(20, TimeUnit.SECONDS),
                    "placement never reached the post-persistence checkpoint");

            String instanceId = cluster.controller()
                    .clusterState()
                    .getInstancesByGroup(groupName)
                    .getFirst()
                    .id();
            var planBeforeFailover = cluster.controller()
                    .stateStore()
                    .getInstanceCompositionPlan(instanceId)
                    .orElseThrow(() -> new AssertionError("composition plan not persisted before failover"));
            String planHashBeforeFailover = planBeforeFailover.planHash();

            cluster.failoverController();
            goAhead.countDown();
            placementThread.join(5_000);

            cluster.waitForNode(nodeId, 20_000);
            cluster.waitForCondition(
                    "standby redispatches placement for " + instanceId,
                    20_000,
                    () -> cluster.controller()
                            .clusterState()
                            .getInstance(instanceId)
                            .map(instance -> instance.state() == InstanceState.STARTING
                                    || instance.state() == InstanceState.STOPPED
                                    || instance.state() == InstanceState.RUNNING
                                    || instance.state() == InstanceState.CRASHED)
                            .orElse(false));

            var planAfterFailover = cluster.controller()
                    .stateStore()
                    .getInstanceCompositionPlan(instanceId)
                    .orElseThrow();
            assertEquals(
                    planHashBeforeFailover,
                    planAfterFailover.planHash(),
                    "standby must reuse the persisted composition plan, not re-plan");
            assertEquals(nodeId, planAfterFailover.nodeId(), "standby must reuse the original placement node");
            assertNotNull(placementError.get(), "active-side placement was supposed to abort via the hook");
        }
    }

    /**
     * In-flight module mutation standby promotion: the active controller commits a v2
     * module jar to the on-disk store but dies before the classloader is opened. The
     * standby's periodic {@code reconcileStoredModules()} sees the new jar and reloads
     * to v2 with no manual intervention. Verifies that the platform-module install path
     * is failover-safe at the jar-accept-but-not-yet-loaded checkpoint.
     */
    @Test
    void standbyPromotionResumesModuleUpgradeAfterMidLoadFailover(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        Assumptions.assumeTrue(TestCluster.mongoAvailable(), "MongoDB is required for recovery harness tests");

        try (TestCluster cluster = TestCluster.startHa(1, 2)) {
            RestClient admin = new RestClient(cluster.restBaseUrl(), cluster.adminJwtToken());

            Path providerV1Jar = PlatformModuleTestJarFactory.createProviderV1Jar(tempDir.resolve("profile-v1.jar"));
            Path providerV2Jar = PlatformModuleTestJarFactory.createProviderV2Jar(tempDir.resolve("profile-v2.jar"));

            var v1Install = admin.postMultipartFile("/api/v1/modules/platform/upload", "file", providerV1Jar);
            assertEquals(201, v1Install.status(), v1Install.body());
            assertEquals(
                    "1.0.0",
                    cluster.controller()
                            .moduleRegistry()
                            .platformManager()
                            .snapshot("profile")
                            .orElseThrow()
                            .version());

            CountDownLatch checkpointReached = new CountDownLatch(1);
            CountDownLatch goAhead = new CountDownLatch(1);
            cluster.controller().moduleRegistry().platformManager().preLoadHookForTesting = () -> {
                checkpointReached.countDown();
                try {
                    goAhead.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("simulated mid-load failover (test hook)");
            };

            AtomicReference<Integer> upgradeStatus = new AtomicReference<>();
            Thread upgradeThread = Thread.ofVirtual()
                    .name("test-module-upgrade-driver")
                    .start(() -> {
                        try {
                            var response = admin.postMultipartFile(
                                    "/api/v1/modules/platform/profile/upgrade", "file", providerV2Jar);
                            upgradeStatus.set(response.status());
                        } catch (Exception _) {
                            upgradeStatus.set(-1);
                        }
                    });

            assertTrue(
                    checkpointReached.await(20, TimeUnit.SECONDS),
                    "module upgrade never reached the post-commit checkpoint");

            cluster.failoverController();
            goAhead.countDown();
            upgradeThread.join(10_000);

            cluster.waitForCondition(
                    "standby reconciles profile module to v2 after failover",
                    20_000,
                    () -> cluster.controller()
                            .moduleRegistry()
                            .platformManager()
                            .snapshot("profile")
                            .map(snapshot -> "2.0.0".equals(snapshot.version())
                                    && snapshot.state() == ModuleLifecycleManager.ModuleState.ACTIVE)
                            .orElse(false));

            var standbySnapshot = cluster.controller()
                    .moduleRegistry()
                    .platformManager()
                    .snapshot("profile")
                    .orElseThrow();
            assertEquals("2.0.0", standbySnapshot.version(), "standby converged to v2 jar from on-disk store");
            assertEquals(
                    ModuleLifecycleManager.ModuleState.ACTIVE,
                    standbySnapshot.state(),
                    "v2 module is ACTIVE on standby, not stuck in WAITING/FAILED");
        }
    }
}
