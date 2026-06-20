package me.prexorjustin.prexorcloud.controller.cluster.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A small binary blob the cluster shares through the Mongo cluster store — cluster CA cert + key,
 * future trust roots, the like. The {@code cluster_files} collection holds the bytes inline; the
 * SHA-256 lets readers verify they got what was written.
 *
 * <p>Large blobs do NOT belong here — the rule of thumb is "a few KB at most".
 */
public record ClusterFile(
        @JsonProperty("key") String key,
        @JsonProperty("sha256") String sha256,
        @JsonProperty("bytes") byte[] bytes) {

    public static final String KEY_CLUSTER_CA_CERT = "cluster-ca.crt";
    public static final String KEY_CLUSTER_CA_KEY = "cluster-ca.key";
}
