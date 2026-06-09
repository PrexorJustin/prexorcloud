package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.Map;

import me.prexorjustin.prexorcloud.controller.state.InstanceInfo;

public final class InstanceDtoMapper {

    private InstanceDtoMapper() {}

    public static Map<String, Object> toDto(InstanceInfo instance) {
        return Map.of(
                "id",
                instance.id(),
                "group",
                instance.group(),
                "node",
                instance.nodeId(),
                "state",
                instance.state().name(),
                "port",
                instance.port(),
                "playerCount",
                instance.playerCount(),
                "uptimeMs",
                instance.uptimeMs(),
                "startedAt",
                instance.startedAt(),
                "deploymentRevision",
                instance.deploymentRevision());
    }
}
