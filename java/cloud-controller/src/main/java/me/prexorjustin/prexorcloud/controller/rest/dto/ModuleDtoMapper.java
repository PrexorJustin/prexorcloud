package me.prexorjustin.prexorcloud.controller.rest.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.prexorjustin.prexorcloud.api.module.frontend.FrontendManifest;
import me.prexorjustin.prexorcloud.controller.module.ModuleFrontendManager;
import me.prexorjustin.prexorcloud.controller.module.platform.PlatformModuleStorageManager;

public final class ModuleDtoMapper {

    private ModuleDtoMapper() {}

    public static Map<String, Object> toDto(ModuleFrontendManager.LoadedFrontend frontend) {
        FrontendManifest manifest = frontend.manifest();
        String basePath = "/api/v1/modules/" + frontend.moduleName() + "/frontend/";
        String hash = frontend.contentHash();

        List<Map<String, Object>> routes = manifest.routes().stream()
                .map(route -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("path", route.path());
                    dto.put("component", route.component());
                    dto.put("title", route.title());
                    dto.put("icon", route.icon());
                    dto.put("nav", route.nav());
                    dto.put("navGroup", route.navGroup());
                    dto.put("navGroupOrder", route.navGroupOrder());
                    dto.put("adminOnly", route.adminOnly());
                    return dto;
                })
                .toList();

        Map<String, Object> frontendDto = new LinkedHashMap<>();
        frontendDto.put("displayName", manifest.displayName());
        frontendDto.put("entry", basePath + manifest.entry() + "?v=" + hash);
        if (manifest.css() != null) {
            frontendDto.put("css", basePath + manifest.css() + "?v=" + hash);
        }
        frontendDto.put("contentHash", hash);
        frontendDto.put("icon", manifest.icon());
        frontendDto.put("routes", routes);
        frontendDto.put("events", manifest.events());

        Map<String, Object> moduleDto = new LinkedHashMap<>();
        moduleDto.put("name", frontend.moduleName());
        moduleDto.put("enabled", true);
        moduleDto.put("frontend", frontendDto);
        moduleDto.put("plugins", List.of());
        return moduleDto;
    }

    public static Map<String, Object> storageDropResponse(PlatformModuleStorageManager.StorageDropResult dropped) {
        return Map.of(
                "moduleId",
                dropped.moduleId(),
                "mongoCollectionsDropped",
                dropped.mongoCollectionsDropped());
    }
}
