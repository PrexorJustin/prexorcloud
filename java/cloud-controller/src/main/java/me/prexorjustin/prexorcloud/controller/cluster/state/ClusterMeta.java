package me.prexorjustin.prexorcloud.controller.cluster.state;

import java.time.Instant;

/**
 * The cluster's identity stamp. Lives as a singleton in the Raft state machine.
 * {@code seedSecret} (base64 of 32 random bytes) is the HMAC key used to sign
 * single-use join tokens; it NEVER appears in any REST response or audit log.
 * See docs/engineering/cluster-join-plan.md.
 */
public record ClusterMeta(String clusterId, String seedSecretBase64, Instant createdAt, int schemaVersion) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
