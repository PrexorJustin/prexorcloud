package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.group.GroupConfig;
import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;
import me.prexorjustin.prexorcloud.controller.state.PlayerInfo;
import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WorkloadDtoMapper")
class WorkloadDtoMapperTest {

    @Test
    @DisplayName("maps shared workload instance group and player payloads")
    void mapsWorkloadDtos() {
        InstanceInfo instance = new InstanceInfo(
                "inst-1",
                "lobby",
                "node-1",
                InstanceState.RUNNING,
                25565,
                12,
                15_000,
                Instant.parse("2026-04-17T10:00:00Z"));
        GroupConfig group = new GroupConfig(
                "lobby",
                "",
                "PAPER",
                "1.21.4",
                "server.jar",
                List.of("base"),
                "DYNAMIC",
                1,
                3,
                100,
                0.8,
                300,
                60,
                false,
                0.2,
                0,
                "LOWEST_PLAYERS",
                30000,
                30100,
                120,
                30,
                true,
                0,
                false,
                List.of(),
                List.of(),
                "",
                false,
                List.of(),
                0,
                true,
                "",
                List.of("prexorcloud.maintenance"),
                "ROLLING",
                List.of(),
                List.of(),
                "",
                0,
                1024,
                List.of(),
                Map.of(),
                List.of(),
                "STATIC",
                30,
                List.of(),
                List.of(),
                List.of(),
                Map.of());
        PlayerInfo player = new PlayerInfo(
                UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                "Notch",
                "inst-1",
                "lobby",
                "proxy-1",
                Instant.parse("2026-04-17T10:05:00Z"));

        Map<String, Object> instanceDto = WorkloadDtoMapper.toInstanceDto(instance, "10.0.0.5");
        Map<String, Object> groupDto = WorkloadDtoMapper.toGroupDto(group, 7);
        Map<String, Object> playerDto = WorkloadDtoMapper.toPlayerDto(player);

        assertEquals("10.0.0.5", instanceDto.get("nodeAddress"));
        assertEquals(12, instanceDto.get("playerCount"));
        assertEquals(List.of("prexorcloud.maintenance"), groupDto.get("maintenanceBypass"));
        assertEquals(true, groupDto.get("isMaintenance"));
        assertEquals(1024, groupDto.get("memoryMb"));
        assertEquals(0.0, groupDto.get("cpuReservation"));
        assertEquals(0L, groupDto.get("diskReservationMb"));
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", playerDto.get("id"));
    }

    @Test
    @DisplayName("maps shared transfer and status helper payloads")
    void mapsWorkloadHelperPayloads() {
        InstanceInfo instance = new InstanceInfo(
                "inst-2",
                "survival",
                "node-2",
                InstanceState.RUNNING,
                25570,
                4,
                5_000,
                Instant.parse("2026-04-17T10:10:00Z"));
        UUID playerUuid = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");

        Map<String, Object> pendingTransfer = WorkloadDtoMapper.toPendingTransferDto(playerUuid, instance, "10.0.0.7");

        assertEquals("inst-2", pendingTransfer.get("targetInstanceId"));
        assertEquals("10.0.0.7", pendingTransfer.get("nodeAddress"));
        assertEquals(Map.of("status", "ok"), WorkloadDtoMapper.statusResponse("ok"));
        assertEquals(Map.of("token", "next-token"), WorkloadDtoMapper.tokenRefreshResponse("next-token"));
        assertEquals(Map.of("id", "42", "status", "QUEUED"), WorkloadDtoMapper.messageQueuedResponse("42"));
        assertEquals(
                Map.of(
                        "status",
                        "transfer_queued",
                        "playerId",
                        "853c80ef-3c37-49fd-aa49-938b674adae6",
                        "targetInstanceId",
                        "inst-2"),
                WorkloadDtoMapper.transferQueuedResponse(playerUuid, "inst-2"));
    }
}
