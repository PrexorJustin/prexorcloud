package me.prexorjustin.prexorcloud.modules.tablist.platform;

import me.prexorjustin.prexorcloud.api.module.platform.ModuleContext;
import me.prexorjustin.prexorcloud.api.module.platform.PlatformModule;
import me.prexorjustin.prexorcloud.api.module.rest.RouteRegistrar;
import me.prexorjustin.prexorcloud.modules.tablist.data.TablistRepository;
import me.prexorjustin.prexorcloud.modules.tablist.rest.TablistRoutes;

/**
 * Backend entry point for the tablist module.
 *
 * <p>Owns Mongo persistence + REST CRUD for templates. The in-game plugin
 * polls {@code GET /api/v1/modules/tablist/active?group=<group>} every
 * {@code refreshSeconds} and renders the returned header/footer through its
 * version-specific {@code @ForVersion} adapter.
 *
 * <p>This module deliberately exposes <strong>no</strong> capability — its
 * surface is the REST API and the per-instance plugin. That keeps the focus
 * of this reference module on the {@code @ForVersion} mechanism, not on the
 * capability graph (covered by stats-aggregator).
 */
public final class TablistModule implements PlatformModule {

    private TablistRepository repository;
    private TablistRoutes routes;

    @Override
    public void onLoad(ModuleContext context) {
        repository = new TablistRepository(context.requireMongoStorage());
        routes = new TablistRoutes(repository);
    }

    @Override
    public void onRegisterRoutes(RouteRegistrar registrar) {
        if (routes != null) {
            routes.register(registrar);
        }
    }

    @Override
    public void onUnload(ModuleContext context) {
        routes = null;
        repository = null;
    }
}
