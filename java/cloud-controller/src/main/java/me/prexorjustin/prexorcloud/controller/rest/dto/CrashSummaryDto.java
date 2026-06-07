package me.prexorjustin.prexorcloud.controller.rest.dto;

public record CrashSummaryDto(
        String id,
        String instanceId,
        String group,
        String node,
        int exitCode,
        String classification,
        String causeSummary,
        String signature,
        long uptimeMs,
        String crashedAt) {}
