package me.prexorjustin.prexorcloud.daemon.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.config.GrpcConfig;
import me.prexorjustin.prexorcloud.controller.config.HeartbeatConfig;
import me.prexorjustin.prexorcloud.controller.config.HttpConfig;
import me.prexorjustin.prexorcloud.controller.config.SchedulerConfig;
import me.prexorjustin.prexorcloud.daemon.config.ControllerConnectionConfig;
import me.prexorjustin.prexorcloud.protocol.ProtocolConstants;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StartupContractDriftTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void startupSnapshotMatchesCommittedContract() throws Exception {
        Path repoRoot = Path.of(System.getProperty("prexor.repo.root"));
        Path snapshot = repoRoot.resolve("java/cloud-protocol/contracts/startup-contract.json");

        Map<String, Object> expected =
                MAPPER.readValue(Files.readString(snapshot, StandardCharsets.UTF_8), new TypeReference<>() {});

        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("protocolVersion", ProtocolConstants.PROTOCOL_VERSION);
        actual.put("defaultGrpcPort", ProtocolConstants.DEFAULT_GRPC_PORT);
        actual.put("defaultHeartbeatIntervalMs", ProtocolConstants.DEFAULT_HEARTBEAT_INTERVAL_MS);
        actual.put("defaultNodeTimeoutMs", ProtocolConstants.DEFAULT_NODE_TIMEOUT_MS);
        actual.put("controllerHttpHost", new HttpConfig().host());
        actual.put("controllerHttpPort", new HttpConfig().port());
        actual.put("controllerGrpcHost", new GrpcConfig().host());
        actual.put("controllerGrpcPort", new GrpcConfig().port());
        actual.put("daemonControllerHost", new ControllerConnectionConfig().host());
        actual.put("daemonControllerGrpcPort", new ControllerConnectionConfig().grpcPort());
        actual.put("cliControllerHttpPort", 8080);
        actual.put("cliControllerGrpcPort", 9090);
        actual.put("cliDaemonControllerGrpcPort", 9090);
        actual.put("heartbeatMissedThreshold", new HeartbeatConfig().missedThreshold());
        actual.put("schedulerNodeTimeoutSeconds", new SchedulerConfig().nodeTimeoutSeconds());

        assertEquals(
                expected,
                actual,
                "Startup contract snapshot drifted. Update java/cloud-protocol/contracts/startup-contract.json if the change is intentional.");
    }
}
