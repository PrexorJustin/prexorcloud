package me.prexorjustin.prexorcloud.api.client.env;

import java.util.Optional;

/**
 * Reads {@code CLOUD_*} environment variables injected by the daemon into every
 * managed instance.
 */
public final class PluginEnv {

    private PluginEnv() {}

    public static String instanceId() {
        return require("CLOUD_INSTANCE_ID");
    }

    public static String group() {
        return require("CLOUD_GROUP");
    }

    public static String nodeId() {
        return require("CLOUD_NODE_ID");
    }

    public static String controllerHost() {
        return require("CLOUD_CONTROLLER_HOST");
    }

    public static int controllerPort() {
        return Integer.parseInt(require("CLOUD_CONTROLLER_PORT"));
    }

    /** Returns the raw value of {@code CLOUD_<key>} or empty if not set. */
    public static Optional<String> get(String key) {
        return Optional.ofNullable(System.getenv("CLOUD_" + key));
    }

    /** Returns {@code true} if this JVM was launched by the PrexorCloud daemon. */
    public static boolean isCloudManaged() {
        return System.getenv("CLOUD_INSTANCE_ID") != null;
    }

    /** Returns {@code http://host:port} for the controller. */
    public static String controllerUrl() {
        return "http://" + controllerHost() + ":" + controllerPort();
    }

    /** Returns the plugin authentication token injected by the daemon. */
    public static String pluginToken() {
        return require("CLOUD_PLUGIN_TOKEN");
    }

    private static String require(String key) {
        String val = System.getenv(key);
        if (val == null) throw new IllegalStateException("Required environment variable '" + key + "' is not set");
        return val;
    }
}
