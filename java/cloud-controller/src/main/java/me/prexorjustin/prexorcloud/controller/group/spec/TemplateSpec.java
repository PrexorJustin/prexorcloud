package me.prexorjustin.prexorcloud.controller.group.spec;

import java.util.List;

import me.prexorjustin.prexorcloud.controller.template.TemplateConfig;

/**
 * TemplateSpec v2 -- the proposed template authoring contract (Group/Template v2, Phase 0; proposal
 * in code, nothing consumes it yet). It adds the depth the assessment found missing without breaking
 * the "templates are pure file packages" model.
 *
 * <p>The first five fields are today's {@link TemplateConfig} verbatim and resolve straight back via
 * {@link #toTemplateConfig()}. Everything else is additive and takes effect only when later phases
 * land:
 * <ul>
 *   <li>{@code variables} -- typed/validated, upgrading today's untyped {@code TemplateVariable} (Phase 2).</li>
 *   <li>{@code includes} -- URL/artifact pulled into {@code files/} at build time, CloudNet-style.</li>
 *   <li>{@code install} -- an optional sandboxed setup script (Pterodactyl-egg-style).</li>
 *   <li>{@code parserRules} -- data-driven config patches (path/wildcard/regex), replacing the
 *       per-platform hardcoded {@code ServerConfigPatcher} (Phase 3).</li>
 *   <li>{@code signature}/{@code provenance} -- cosign signing, reusing the module-signing path.</li>
 *   <li>{@code storage} -- chunked/deduplicated/delta-synced backend selection (Phase 4).</li>
 * </ul>
 */
public record TemplateSpec(
        String name,
        String description,
        String platform,
        String hash,
        long sizeBytes,
        List<VariableDef> variables,
        List<Include> includes,
        InstallHook install,
        List<ConfigRule> parserRules,
        String signature,
        String provenance,
        StorageRef storage) {

    /** Fetch an artifact into the template's {@code files/} tree at build time. {@code sha256} is verified. */
    public record Include(String url, String destPath, String sha256) {}

    /** Optional sandboxed setup step run once when the template is built/updated. */
    public record InstallHook(String interpreter, List<String> script, int timeoutSeconds) {}

    /**
     * One data-driven config edit. {@code path} is a dot/wildcard selector (e.g. {@code servers.*.address});
     * {@code op} chooses set vs in-line replace vs regex. Replaces today's flat {@code configPatches}
     * and the per-platform {@code ServerConfigPatcher}.
     */
    public record ConfigRule(String file, Format format, String path, Op op, String value) {

        public enum Format { PROPERTIES, YAML, JSON, TOML, TEXT }

        public enum Op { SET, REPLACE, REGEX }
    }

    /** Where the template's content-addressed chunks live. {@code chunkManifest} replaces the per-version tar.gz. */
    public record StorageRef(Backend backend, String chunkManifest) {

        public enum Backend { LOCAL, S3 }
    }

    /** Resolve down to today's {@link TemplateConfig} (the five fields the running system stores/serves). */
    public TemplateConfig toTemplateConfig() {
        return new TemplateConfig(name, description, platform, hash, sizeBytes);
    }
}
