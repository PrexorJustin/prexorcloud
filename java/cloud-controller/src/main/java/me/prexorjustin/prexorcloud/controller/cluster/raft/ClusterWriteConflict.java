package me.prexorjustin.prexorcloud.controller.cluster.raft;

import java.io.IOException;

/**
 * Thrown by {@link ClusterControlPlane} when the state machine rejects a write
 * (stale parentVersion on config patch, already-redeemed join token, lease
 * held by someone else, etc). The {@code code} comes verbatim from
 * {@code ClusterEntry.Reply} so the REST layer can map it to a deterministic
 * HTTP status (typically 409 for stale parent, 410 for redeemed/revoked, 423
 * for held lease) without re-parsing the message.
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
