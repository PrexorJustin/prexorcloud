package me.prexorjustin.prexorcloud.controller.group.spec;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The per-platform default {@link ConfigRule}s (Group/Template v2, Phase 3) -- the data-driven
 * successor to the daemon's hardcoded per-platform patch methods. Keyed by config format, this table
 * supplies the {@code base-<platform>} slice of an instance's resolved rule chain (see
 * {@link ConfigRuleResolver}); a new platform contributes its config edits as data here, with no
 * imperative Java in either the controller or the daemon.
 *
 * <p>It deliberately holds <em>only</em> the edits that genuinely need patching at provision time --
 * not everything a platform configures:
 * <ul>
 *   <li>Per-instance scalars (port, max-players, MOTD) are <strong>not</strong> here: the shipped
 *       default files carry {@code %VAR%} placeholders the daemon's variable-substitution stage fills
 *       in. Duplicating them as rules would re-introduce the parallel mechanism v2 retires.</li>
 *   <li>Paper's velocity {@code enabled}/{@code online-mode} are <strong>not</strong> here either: they
 *       are baked into the shared bootstrap cache once by {@code PaperBootstrapCache}. Only the
 *       per-instance forwarding secret remains, as a {@code %FORWARDING_SECRET%} placeholder the daemon
 *       resolves locally (the secret never travels on the wire or through {@code planHash}).</li>
 *   <li>Geyser {@code remote.*} is <strong>not</strong> here -- it is resolved dynamically per instance
 *       from a live proxy and contributed by the planner.</li>
 * </ul>
 *
 * <p>Forks share their parent's format (purpur/folia/pufferfish -> {@code paper}; waterfall ->
 * {@code bungeecord}), so they inherit the same rules automatically.
 */
public final class PlatformConfigDefaults {

    private PlatformConfigDefaults() {}

    /**
     * The placeholder, resolved by the daemon at apply time from the instance's {@code forwarding.secret}
     * file. Kept symbolic on the controller so the HMAC secret is never serialised into a dispatched plan
     * or its hash.
     */
    public static final String FORWARDING_SECRET_PLACEHOLDER = "%FORWARDING_SECRET%";

    private static final Map<String, List<ConfigRule>> BY_FORMAT = Map.of(
            // Paper (+ purpur/folia/pufferfish): paper-global.yml is bootstrap-generated, not a shipped
            // template file, so the velocity forwarding secret is patched per instance. enabled +
            // online-mode are already baked into the bootstrap cache.
            "paper",
                    List.of(new ConfigRule(
                            "config/paper-global.yml",
                            ConfigRule.Format.YAML,
                            "proxies.velocity.secret",
                            ConfigRule.Op.REPLACE,
                            FORWARDING_SECRET_PLACEHOLDER)),
            // Spigot: spigot.yml is server-generated; enable BungeeCord forwarding. Replace-only -- if the
            // file is not present yet the rule is a no-op (matching the legacy patcher).
            "spigot",
                    List.of(new ConfigRule(
                            "spigot.yml",
                            ConfigRule.Format.YAML,
                            "settings.bungeecord",
                            ConfigRule.Op.REPLACE,
                            "true")));

    /** The base-platform default rules for a config format; empty for formats that need none. */
    public static List<ConfigRule> forConfigFormat(String configFormat) {
        if (configFormat == null || configFormat.isBlank()) {
            return List.of();
        }
        return BY_FORMAT.getOrDefault(configFormat.toLowerCase(Locale.ROOT), List.of());
    }
}
