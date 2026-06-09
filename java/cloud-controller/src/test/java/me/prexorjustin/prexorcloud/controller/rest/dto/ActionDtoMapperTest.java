package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ActionDtoMapper")
class ActionDtoMapperTest {

    @Test
    @DisplayName("maps shared status envelopes")
    void mapsStatusEnvelopes() {
        assertEquals(Map.of("status", "deleted"), ActionDtoMapper.statusResponse("deleted"));
        assertEquals(Map.of("status", "scheduled", "count", 3), ActionDtoMapper.statusCountResponse("scheduled", 3));
    }
}
