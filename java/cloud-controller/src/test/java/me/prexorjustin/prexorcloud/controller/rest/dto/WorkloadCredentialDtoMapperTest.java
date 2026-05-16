package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.state.WorkloadIdentityRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WorkloadCredentialDtoMapper")
class WorkloadCredentialDtoMapperTest {

    @Test
    @DisplayName("maps workload credential admin payloads")
    void mapsCredentialPayloads() {
        var snapshot = new WorkloadIdentityRegistry.PluginTokenSnapshot(
                "token-1", "proxy-1", Instant.parse("2026-04-17T10:00:00Z"), Instant.parse("2026-04-17T10:15:00Z"));

        assertEquals("token-1", WorkloadCredentialDtoMapper.toDto(snapshot).get("tokenId"));
        assertEquals(
                Map.of("instanceId", "proxy-1", "revokedCredentials", 2),
                WorkloadCredentialDtoMapper.revokeInstanceResponse("proxy-1", 2));
    }
}
