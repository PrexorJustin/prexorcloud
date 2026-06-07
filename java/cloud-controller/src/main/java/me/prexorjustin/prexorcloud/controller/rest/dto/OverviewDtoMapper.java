package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.Map;

public final class OverviewDtoMapper {

    private OverviewDtoMapper() {}

    public static Map<String, Object> toDto(int nodeCount, int instanceCount, int playerCount, int groupCount) {
        return Map.of(
                "nodeCount",
                nodeCount,
                "instanceCount",
                instanceCount,
                "playerCount",
                playerCount,
                "groupCount",
                groupCount);
    }
}
