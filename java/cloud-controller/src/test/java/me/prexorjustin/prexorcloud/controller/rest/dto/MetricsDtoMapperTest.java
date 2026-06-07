package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MetricsDtoMapper")
class MetricsDtoMapperTest {

    @Test
    @DisplayName("maps metrics summary payload")
    void mapsSummaryDto() {
        assertEquals(
                Map.of("nodes", 2, "instances", 10, "players", 84, "groups", 4, "crashes", 1),
                MetricsDtoMapper.summaryDto(2, 10, 84, 4, 1));
    }
}
