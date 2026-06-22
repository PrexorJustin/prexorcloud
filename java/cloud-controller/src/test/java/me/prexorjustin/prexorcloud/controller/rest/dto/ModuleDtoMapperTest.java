package me.prexorjustin.prexorcloud.controller.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.module.frontend.FrontendManifest;
import me.prexorjustin.prexorcloud.api.module.frontend.FrontendRoute;
import me.prexorjustin.prexorcloud.controller.module.ModuleFrontendManager;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleStorageManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ModuleDtoMapper")
class ModuleDtoMapperTest {

    @Test
    @DisplayName("maps module frontend and plugin metadata for the dashboard")
    void mapsModulePayload() {
        var frontend = new ModuleFrontendManager.LoadedFrontend(
                "queue",
                new FrontendManifest(
                        1,
                        "Queue Tools",
                        "entry.mjs",
                        "queue.css",
                        "boxes",
                        List.of("modules.view"),
                        List.of(new FrontendRoute("/queue", "QueuePage", "Queue", "boxes", true, "Ops", 2, true)),
                        List.of("QueueReadyEvent")),
                "abc123ef",
                Path.of("modules", "queue", "_frontend"));

        Map<String, Object> dto = ModuleDtoMapper.toDto(frontend);

        assertEquals("queue", dto.get("name"));
        assertEquals(true, dto.get("enabled"));

        Map<?, ?> frontendDto = assertInstanceOf(Map.class, dto.get("frontend"));
        assertEquals("Queue Tools", frontendDto.get("displayName"));
        assertEquals("/api/v1/modules/queue/frontend/entry.mjs?v=abc123ef", frontendDto.get("entry"));
        assertEquals("/api/v1/modules/queue/frontend/queue.css?v=abc123ef", frontendDto.get("css"));
        assertEquals(List.of("QueueReadyEvent"), frontendDto.get("events"));

        List<?> routes = assertInstanceOf(List.class, frontendDto.get("routes"));
        Map<?, ?> route = assertInstanceOf(Map.class, routes.getFirst());
        assertEquals("/queue", route.get("path"));
        assertEquals(true, route.get("adminOnly"));

        List<?> plugins = assertInstanceOf(List.class, dto.get("plugins"));
        assertEquals(List.of(), plugins);
        assertEquals(
                Map.of("moduleId", "queue", "mongoCollectionsDropped", 2),
                ModuleDtoMapper.storageDropResponse(new PlatformModuleStorageManager.StorageDropResult("queue", 2)));
    }
}
