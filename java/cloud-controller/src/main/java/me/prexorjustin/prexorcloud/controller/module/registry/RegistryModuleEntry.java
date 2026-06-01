package me.prexorjustin.prexorcloud.controller.module.registry;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One installable module version in a {@link RegistryIndex}. Carries everything
 * the controller needs to pull and verify a signed JAR without trusting the
 * registry itself: the {@code sha256} pins the artifact bytes and the cosign
 * bundle / sig sidecar is verified against the controller's own configured trust
 * root after download. The registry is a discovery convenience, never a trust
 * anchor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryModuleEntry(
        @JsonProperty("moduleId") String moduleId,
        @JsonProperty("version") String version,
        @JsonProperty("jarUrl") String jarUrl,
        @JsonProperty("sha256") String sha256,
        @JsonProperty("cosignBundleUrl") String cosignBundleUrl,
        @JsonProperty("sigUrl") String sigUrl,
        @JsonProperty("compatibleControllerVersions") List<String> compatibleControllerVersions,
        @JsonProperty("tags") List<String> tags,
        @JsonProperty("readme") String readme) {

    public RegistryModuleEntry {
        compatibleControllerVersions =
                compatibleControllerVersions == null ? List.of() : List.copyOf(compatibleControllerVersions);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** The signature sidecar kind this entry advertises, preferring a cosign bundle. */
    public boolean hasCosignBundle() {
        return cosignBundleUrl != null && !cosignBundleUrl.isBlank();
    }

    public boolean hasSig() {
        return sigUrl != null && !sigUrl.isBlank();
    }
}
