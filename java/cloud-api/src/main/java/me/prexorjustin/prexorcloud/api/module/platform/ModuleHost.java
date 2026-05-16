package me.prexorjustin.prexorcloud.api.module.platform;

/**
 * Process the module is hosted by.
 *
 * <p>
 * Returned by {@link ModuleContext#host()} so a module that targets both
 * controller and daemon (manifest {@code hosts: [controller, daemon]}) can
 * branch on which side of the cluster it's currently running in.
 * </p>
 */
public enum ModuleHost {

    /**
     * Module runs in the controller process. Has Mongo + Redis storage,
     * cluster-wide event bus, REST route registration.
     */
    CONTROLLER,

    /**
     * Module runs in a daemon process. Has node-local storage (if any),
     * subscribes to controller events through the daemon's gRPC bridge,
     * receives instance lifecycle hooks for instances on this node.
     */
    DAEMON
}
