package me.prexorjustin.prexorcloud.api;

import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.module.cluster.ClusterView;

/** Top-level API handle. Obtain via {@link CloudApiProvider#get()}. */
public interface CloudApi {

    /**
     * Global event bus. Plugins use the scoped bus from
     * {@link me.prexorjustin.prexorcloud.api.plugin.CloudPluginContext#events()}.
     */
    EventBus events();

    /** Read-only cluster snapshot. Available at both plugin and module level. */
    ClusterView cluster();

    String version();
}
