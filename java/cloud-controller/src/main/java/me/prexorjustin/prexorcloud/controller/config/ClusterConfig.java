package me.prexorjustin.prexorcloud.controller.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Cluster identity. {@code id} pins this controller to a specific Mongo cluster
 * — at boot, the controller cross-checks it against the {@code cluster_meta}
 * collection and refuses to start on mismatch. {@code joinedFrom} / {@code joinedAt}
 * are informational, written by the join wizard for a controller that joined an
 * existing cluster.
 */
public record ClusterConfig(
        @JsonProperty("id") String id,
        @JsonProperty("joinedFrom") String joinedFrom,
        @JsonProperty("joinedAt") String joinedAt) {

    public ClusterConfig() {
        this(null, null, null);
    }
}
