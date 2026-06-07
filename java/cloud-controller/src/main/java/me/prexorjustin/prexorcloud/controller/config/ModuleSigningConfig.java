package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Module package signing policy.
 *
 * <p>{@code required} controls whether the controller refuses to install modules whose
 * signatures cannot be verified. The default resolves to {@code true} in the production
 * profile and {@code false} in the development profile (see
 * {@link #requiredOrDefault(boolean)}).
 *
 * <p>{@code trustRoot} points at a PEM bundle that the verifier consults. The exact contents
 * depend on {@code mode}:
 * <ul>
 *   <li>{@link Mode#KEYED} (default, backwards-compat) — trust root holds {@code PUBLIC KEY}
 *       blocks; modules are accompanied by a {@code <jar>.sig} sidecar (Base64 signature).</li>
 *   <li>{@link Mode#COSIGN_BUNDLE} — modules are accompanied by a {@code <jar>.cosign.bundle}
 *       JSON file produced by {@code cosign sign-blob --bundle …}. Trust root may hold
 *       {@code PUBLIC KEY} blocks (raw cosign-keyed) and/or {@code CERTIFICATE} blocks
 *       (cosign-keyed with self-issued certs validated against internal CAs).</li>
 * </ul>
 *
 * <p>{@code allowUnsignedDevelopment} permits unsigned packages in development profile even
 * when {@code required=true} elsewhere.
 *
 * <p>{@code rekor} configures offline transparency-log enforcement against Rekor's
 * {@code SignedEntryTimestamp}. Only meaningful with {@link Mode#COSIGN_BUNDLE}.
 */
public record ModuleSigningConfig(
        @JsonProperty("required") Boolean required,
        @JsonProperty("trustRoot") String trustRoot,
        @JsonProperty("mode") Mode mode,
        @JsonProperty("allowUnsignedDevelopment") boolean allowUnsignedDevelopment,
        @JsonProperty("rekor") RekorConfig rekor) {

    public enum Mode {
        KEYED,
        COSIGN_BUNDLE
    }

    /**
     * Rekor transparency-log policy. Verification is offline: the controller verifies the
     * SET against {@code publicKey} bundle without contacting Rekor.
     */
    public record RekorConfig(
            @JsonProperty("policy") Policy policy,
            @JsonProperty("publicKey") String publicKey) {

        public enum Policy {
            /** No transparency-log enforcement. */
            DISABLED,
            /** {@code rekorBundle.SignedEntryTimestamp} must be present and verify offline. */
            REQUIRE_SET
        }

        public RekorConfig {
            if (policy == null) {
                policy = Policy.DISABLED;
            }
        }

        public RekorConfig() {
            this(Policy.DISABLED, null);
        }
    }

    public ModuleSigningConfig {
        if (mode == null) {
            mode = Mode.KEYED;
        }
        if (rekor == null) {
            rekor = new RekorConfig();
        }
    }

    public ModuleSigningConfig() {
        this(null, null, Mode.KEYED, true, new RekorConfig());
    }

    public static ModuleSigningConfig defaults() {
        return new ModuleSigningConfig();
    }

    public boolean requiredOrDefault(boolean productionProfile) {
        if (required != null) {
            return required;
        }
        return productionProfile;
    }
}
