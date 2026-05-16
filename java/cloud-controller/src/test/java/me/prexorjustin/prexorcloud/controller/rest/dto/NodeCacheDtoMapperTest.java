package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.common.cache.CacheEntries.BootstrapCacheInfo;
import me.prexorjustin.prexorcloud.common.cache.CacheEntries.JarCacheInfo;
import me.prexorjustin.prexorcloud.common.cache.CacheEntries.TemplateCacheInfo;
import me.prexorjustin.prexorcloud.controller.state.NodeCacheStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NodeCacheDtoMapper")
class NodeCacheDtoMapperTest {

    @Test
    @DisplayName("maps nested cache details including received timestamp")
    void mapsCachePayload() {
        NodeCacheStatus status = new NodeCacheStatus(
                List.of(new TemplateCacheInfo("base", "abc", 1024L, "2026-04-17T10:00:00Z")),
                List.of(new JarCacheInfo("paper", "1.21.4", "paper.jar", 2048L, "sha", "2026-04-17T10:01:00Z")),
                List.of(new BootstrapCacheInfo("paper", "1.21.4", true, 4096L)),
                7168L,
                Instant.parse("2026-04-17T10:05:00Z"));

        Map<String, Object> dto = NodeCacheDtoMapper.toDto(status);

        assertEquals("2026-04-17T10:05:00Z", dto.get("receivedAt"));
        Map<?, ?> template = assertInstanceOf(
                Map.class, assertInstanceOf(List.class, dto.get("templates")).getFirst());
        assertEquals("base", template.get("name"));
    }

    @Test
    @DisplayName("returns a stable empty payload")
    void mapsEmptyCachePayload() {
        Map<String, Object> dto = NodeCacheDtoMapper.emptyDto();

        assertEquals(List.of(), dto.get("templates"));
        assertEquals(null, dto.get("receivedAt"));
    }
}
