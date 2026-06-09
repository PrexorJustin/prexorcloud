package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.Map;

public final class MetricsDtoMapper {

    private MetricsDtoMapper() {}

    public static Map<String, Object> summaryDto(
            int nodeCount, int instanceCount, int playerCount, int groupCount, int crashCount) {
        return Map.of(
                "nodes",
                nodeCount,
                "instances",
                instanceCount,
                "players",
                playerCount,
                "groups",
                groupCount,
                "crashes",
                crashCount);
    }
}
