package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CatalogDtoMapper")
class CatalogDtoMapperTest {

    @Test
    @DisplayName("maps catalog mutation responses")
    void mapsCatalogMutationResponses() {
        assertEquals(
                Map.of("platform", "PAPER", "version", "1.21.4"), CatalogDtoMapper.versionResponse("PAPER", "1.21.4"));
        assertEquals(
                Map.of("platform", "PAPER", "version", "1.21.4", "recommended", true),
                CatalogDtoMapper.recommendedVersionResponse("PAPER", "1.21.4"));
    }
}
