package me.prexorjustin.prexorcloud.controller.cluster;

import java.io.IOException;

/**
 * Thrown by the cluster {@link ClusterPlane} when a conflict-checked write is rejected (stale
 * {@code parentVersion} on a config patch, an already-redeemed or revoked join token, an unknown
 * rollback target, …). The {@code code} is a stable machine-readable token so the REST layer maps it
 * to a deterministic HTTP status (typically 409 for a stale parent, 410 for a redeemed/revoked token)
 * without re-parsing the message.
 */
public final class ClusterWriteConflict extends IOException {

    private final String code;

    public ClusterWriteConflict(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
