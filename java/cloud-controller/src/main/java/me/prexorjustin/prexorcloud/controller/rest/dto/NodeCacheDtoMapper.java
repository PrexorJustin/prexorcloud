package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.controller.state.NodeCacheStatus;

public final class NodeCacheDtoMapper {

    private NodeCacheDtoMapper() {}

    public static Map<String, Object> toDto(NodeCacheStatus cacheStatus) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put(
                "templates",
                cacheStatus.templates().stream()
                        .map(template -> Map.<String, Object>of(
                                "name", template.name(),
                                "hash", template.hash(),
                                "sizeBytes", template.sizeBytes(),
                                "lastUsed", template.lastUsed()))
                        .toList());
        dto.put(
                "jars",
                cacheStatus.jars().stream()
                        .map(jar -> Map.<String, Object>of(
                                "platform", jar.platform(),
                                "version", jar.version(),
                                "jarFile", jar.jarFile(),
                                "sizeBytes", jar.sizeBytes(),
                                "sha256", jar.sha256(),
                                "cachedAt", jar.cachedAt()))
                        .toList());
        dto.put(
                "bootstraps",
                cacheStatus.bootstraps().stream()
                        .map(bootstrap -> Map.<String, Object>of(
                                "configFormat", bootstrap.configFormat(),
                                "version", bootstrap.version(),
                                "hasCds", bootstrap.hasCds(),
                                "sizeBytes", bootstrap.sizeBytes()))
                        .toList());
        dto.put("totalSizeBytes", cacheStatus.totalSizeBytes());
        dto.put("receivedAt", cacheStatus.receivedAt().toString());
        return dto;
    }

    public static Map<String, Object> emptyDto() {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("templates", List.of());
        dto.put("jars", List.of());
        dto.put("bootstraps", List.of());
        dto.put("totalSizeBytes", 0);
        dto.put("receivedAt", null);
        return dto;
    }
}
