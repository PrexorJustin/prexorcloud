package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OverviewDtoMapper")
class OverviewDtoMapperTest {

    @Test
    @DisplayName("maps overview count payload")
    void mapsOverviewDto() {
        assertEquals(
                Map.of("nodeCount", 3, "instanceCount", 12, "playerCount", 247, "groupCount", 5),
                OverviewDtoMapper.toDto(3, 12, 247, 5));
    }
}
