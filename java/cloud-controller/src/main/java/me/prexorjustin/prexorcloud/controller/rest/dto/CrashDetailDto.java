package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.List;

public record CrashDetailDto(
        String id,
        String instanceId,
        String group,
        String node,
        int exitCode,
        String classification,
        String causeSummary,
        String signature,
        long uptimeMs,
        String crashedAt,
        List<String> logTail) {}
