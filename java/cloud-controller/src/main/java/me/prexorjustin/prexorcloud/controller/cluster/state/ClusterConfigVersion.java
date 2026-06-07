package me.prexorjustin.prexorcloud.controller.cluster.state;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One version of the cluster-shared config. Append-only: a mutation creates a
 * new version that references its parent, and a separate active-version pointer
 * picks the live one. {@code patch} is the diff against {@code parentVersion},
 * not the full config — the resolver layer (R3) flattens versions 1..N to
 * compute the effective config.
 */
public record ClusterConfigVersion(
        int version, int parentVersion, String mutator, Instant mutatedAt, Map<String, Object> patch, String reason) {

    public ClusterConfigVersion {
        patch = patch == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(patch));
    }
}
