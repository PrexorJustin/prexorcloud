package me.prexorjustin.prexorcloud.daemon.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Daemon-side platform-module signing policy. Defaults match a development cluster:
 * {@code required=false} accepts unsigned daemon-host modules. Production deployments
 * should set {@code required=true} and point {@code trustRoot} at a PEM bundle whose
 * shape matches {@code mode}.
 *
 * <p>{@code mode} values:
 * <ul>
 *   <li>{@code KEYED} — sidecar is a Base64 {@code .sig} signed with one of the trusted
 *       public keys (raw RSA / EC / Ed25519).</li>
 *   <li>{@code COSIGN_BUNDLE} — sidecar is a {@code .cosign.bundle} JSON; the trust root
 *       holds either trusted public keys or X.509 CAs.</li>
 * </ul>
 */
public record ModuleSigningDaemonConfig(
        @JsonProperty("required") boolean required,
        @JsonProperty("mode") Mode mode,
        @JsonProperty("trustRoot") String trustRoot) {

    public enum Mode {
        KEYED,
        COSIGN_BUNDLE
    }

    public ModuleSigningDaemonConfig {
        if (mode == null) mode = Mode.COSIGN_BUNDLE;
        if (trustRoot == null) trustRoot = "";
    }

    public ModuleSigningDaemonConfig() {
        this(false, Mode.COSIGN_BUNDLE, "");
    }
}
