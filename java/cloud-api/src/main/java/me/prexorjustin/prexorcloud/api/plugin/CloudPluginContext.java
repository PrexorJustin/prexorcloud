package me.prexorjustin.prexorcloud.api.plugin;

import java.util.logging.Logger;

import me.prexorjustin.prexorcloud.api.client.CloudClient;
import me.prexorjustin.prexorcloud.api.event.EventBus;
import me.prexorjustin.prexorcloud.api.plugin.command.CloudCommandRegistry;
import me.prexorjustin.prexorcloud.api.plugin.player.PlayerManager;

/** Context provided to plugins on enable — entry point to all Cloud APIs. */
public interface CloudPluginContext {

    /** Info about this plugin's own server instance. */
    InstanceContext self();

    /** Fluent event bus for subscribing to cluster events. */
    EventBus events();

    /**
     * Command registry — register
     * {@link me.prexorjustin.prexorcloud.api.plugin.command.Command}-annotated
     * classes.
     */
    CloudCommandRegistry commands();

    /** Access to players currently online on this instance. */
    PlayerManager players();

    /** Platform-agnostic task scheduler (Folia-safe). */
    PluginScheduler scheduler();

    /** Low-level cloud communication client. */
    CloudClient client();

    Logger logger();
}
