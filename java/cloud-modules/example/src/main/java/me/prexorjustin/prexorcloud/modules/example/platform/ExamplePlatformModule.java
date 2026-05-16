package me.prexorjustin.prexorcloud.modules.example.platform;

import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;

import me.prexorjustin.prexorcloud.api.module.platform.CapabilityHandle;
import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.example.config.Config;
import me.prexorjustin.prexorcloud.modules.example.data.PlaytimeRepository;
import me.prexorjustin.prexorcloud.modules.example.rest.PlaytimeRoutes;
import me.prexorjustin.prexorcloud.modules.example.service.PlaytimeQueryService;
import me.prexorjustin.prexorcloud.modules.example.service.PlaytimeQueryServiceImpl;

/**
 * Platform-module backend entrypoint for the example playtime module.
 */
public final class ExamplePlatformModule implements PlatformModule {

    public static final String QUERY_CAPABILITY_ID = "example-playtime-query";

    private PlaytimeRepository repository;
    private PlaytimeQueryService queryService;
    private boolean started;

    @Override
    public void onLoad(ModuleContext context) {
        repository = new PlaytimeRepository(context.requireMongoStorage());
        queryService = new PlaytimeQueryServiceImpl(repository);
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar registrar) {
        new PlaytimeRoutes(repository, Config.defaults()).register(registrar);
    }

    @Override
    public void onStart(ModuleContext context) {
        started = true;
    }

    @Override
    public void onStop(ModuleContext context) {
        started = false;
    }

    @Override
    public void onUnload(ModuleContext context) {
        queryService = null;
        repository = null;
        started = false;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<CapabilityHandle<?>> capabilityHandles() {
        if (queryService == null) {
            return List.of();
        }
        ToLongFunction<UUID> totalPlaytimeQuery = queryService::totalMs;
        return List.of(CapabilityHandle.of(QUERY_CAPABILITY_ID, (Class) ToLongFunction.class, totalPlaytimeQuery));
    }

    public boolean started() {
        return started;
    }
}
