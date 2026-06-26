package me.prexorjustin.prexorcloud.controller.group.spec;

import java.util.Locale;

/**
 * One data-driven config edit -- the unit that replaces the per-platform hardcoded
 * {@code ServerConfigPatcher} (Group/Template v2, Phase 3). A rule names a {@code file}, a selector
 * {@code path}, and an {@link Op operation}:
 *
 * <ul>
 *   <li>{@code SET} -- set (creating it if absent) the value at {@code path}, a dot/wildcard key
 *       selector (e.g. {@code settings.bungeecord} or {@code servers.*.address}).</li>
 *   <li>{@code REPLACE} -- set the value at {@code path} only if the key already exists; never creates
 *       it (the conservative "patch what's there" edit).</li>
 *   <li>{@code REGEX} -- treat {@code path} as a regular expression matched against the file's lines and
 *       substitute each match with {@code value} (Pterodactyl-parity find/replace).</li>
 * </ul>
 *
 * <p>{@code format} names the file's syntax; when null it is inferred from the file extension via
 * {@link #formatFor(String)}. {@code value} may carry {@code %VAR%}/{@code {{VAR}}} placeholders --
 * variable substitution happens later, at apply time, not in this model.
 */
public record ConfigRule(String file, Format format, String path, Op op, String value) {

    public enum Format {
        PROPERTIES,
        YAML,
        JSON,
        TOML,
        TEXT
    }

    public enum Op {
        SET,
        REPLACE,
        REGEX
    }

    // Normalize the operation so a partial rule (e.g. JSON from a client that omitted it) defaults to
    // SET rather than leaving a null the applier would have to special-case.
    public ConfigRule {
        if (op == null) op = Op.SET;
    }

    /** Infer a file's {@link Format} from its extension; defaults to {@link Format#TEXT}. */
    public static Format formatFor(String file) {
        String lower = file == null ? "" : file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".properties")) return Format.PROPERTIES;
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return Format.YAML;
        if (lower.endsWith(".json")) return Format.JSON;
        if (lower.endsWith(".toml")) return Format.TOML;
        return Format.TEXT;
    }

    /** This rule with its {@link #format} filled from the file extension when it was left null. */
    public ConfigRule withInferredFormat() {
        return format != null ? this : new ConfigRule(file, formatFor(file), path, op, value);
    }
}
