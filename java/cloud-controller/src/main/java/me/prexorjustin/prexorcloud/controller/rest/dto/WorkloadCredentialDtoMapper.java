package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.LinkedHashMap;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.state.WorkloadIdentityRegistry;

public final class WorkloadCredentialDtoMapper {

    private WorkloadCredentialDtoMapper() {}

    public static Map<String, Object> toDto(WorkloadIdentityRegistry.PluginTokenSnapshot snapshot) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("tokenId", snapshot.tokenId());
        dto.put("instanceId", snapshot.instanceId());
        dto.put("issuedAt", snapshot.issuedAt().toString());
        dto.put("expiresAt", snapshot.expiresAt().toString());
        return dto;
    }

    public static Map<String, Object> revokeInstanceResponse(String instanceId, int revokedCredentials) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("instanceId", instanceId);
        dto.put("revokedCredentials", revokedCredentials);
        return dto;
    }
}
