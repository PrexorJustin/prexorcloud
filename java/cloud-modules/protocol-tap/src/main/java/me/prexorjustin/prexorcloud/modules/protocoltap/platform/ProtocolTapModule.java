package me.prexorjustin.prexorcloud.modules.protocoltap.platform;

import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.protocoltap.data.PacketCountRepository;
import me.prexorjustin.prexorcloud.modules.protocoltap.rest.ProtocolTapRoutes;

/**
 * Backend entrypoint for protocol-tap.
 *
 * <p>Receives observations from per-instance plugins via REST and exposes a
 * Prometheus surface aggregating them. The cross-version teaching point is
 * on the plugin side — see plugin/paper/v1_20 and plugin/paper/v1_21.
 */
public final class ProtocolTapModule implements PlatformModule {

    private PacketCountRepository repository;
    private ProtocolTapRoutes routes;

    @Override
    public void onLoad(ModuleContext context) {
        repository = new PacketCountRepository(context.requireMongoStorage());
        routes = new ProtocolTapRoutes(repository);
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar registrar) {
        if (routes != null) routes.register(registrar);
    }

    @Override
    public void onUnload(ModuleContext context) {
        routes = null;
        repository = null;
    }
}
