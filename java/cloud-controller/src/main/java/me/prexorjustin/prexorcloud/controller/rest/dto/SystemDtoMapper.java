package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.Map;

import me.prexorjustin.prexorcloud.common.util.VersionInfo;

public final class SystemDtoMapper {

    private SystemDtoMapper() {}

    public static Map<String, Object> versionDto(VersionInfo versionInfo) {
        return Map.of(
                "version",
                versionInfo.version(),
                "gitCommit",
                versionInfo.gitCommit(),
                "javaVersion",
                versionInfo.javaVersion());
    }

    public static Map<String, Object> settingsDto(
            int nodeCount,
            int instanceCount,
            int playerCount,
            int schedulerInterval,
            long heartbeatInterval,
            boolean metricsEnabled,
            boolean shareEnabled,
            boolean tracingEnabled,
            String traceUiTemplate) {
        return Map.of(
                "nodeCount",
                nodeCount,
                "instanceCount",
                instanceCount,
                "playerCount",
                playerCount,
                "schedulerInterval",
                schedulerInterval,
                "heartbeatInterval",
                heartbeatInterval,
                "metricsEnabled",
                metricsEnabled,
                "shareEnabled",
                shareEnabled,
                // Track D.3: lets the dashboard offer a "view trace" deep-link (X-Trace-Id header
                // + this template). Empty template = tracing off or no UI configured.
                "tracingEnabled",
                tracingEnabled,
                "traceUiTemplate",
                traceUiTemplate == null ? "" : traceUiTemplate);
    }
}
