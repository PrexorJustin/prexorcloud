package me.prexorjustin.prexorcloud.modules.example.config;

/**
 * Root config record for the example-playtime module.
 *
 * <p>Kept as a reusable config record for route/task support that will be
 * wired through the platform module API as those surfaces land.
 *
 * <p>Jackson deserialises YAML into this record positionally via parameter
 * names, so the component names must match the YAML keys (kebab-case in YAML,
 * camelCase in Java — Jackson handles the conversion when configured with the
 * {@code jackson-module-parameter-names} module, which the controller already
 * wires in).
 */
public record Config(
        int flushIntervalSeconds,
        int topSize,
        int retainSessionsDays,
        String reportVia,
        boolean enableDestructiveDemos) {

    /** Compile-time default used when the config file does not yet exist. */
    public static Config defaults() {
        return new Config(30, 25, 30, "events", false);
    }
}
