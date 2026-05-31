package me.prexorjustin.prexorcloud.controller.cluster.state;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;

/**
 * Every write that goes through the cluster Raft log is one of these. Sealed —
 * the state machine can {@code switch} exhaustively on the concrete type
 * without a default branch, so adding a new entry kind is a compile-time
 * change everywhere it matters. See {@code docs/engineering/cluster-join-plan.md}.
 *
 * <p>Reads do NOT go through Raft — they read the state machine's local
 * projection directly. ClusterControlPlane exposes typed accessors.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ClusterEntry.SetClusterMeta.class, name = "SET_CLUSTER_META"),
    @JsonSubTypes.Type(value = ClusterEntry.RotateSeed.class, name = "ROTATE_SEED"),
    @JsonSubTypes.Type(value = ClusterEntry.WriteConfigVersion.class, name = "WRITE_CONFIG_VERSION"),
    @JsonSubTypes.Type(value = ClusterEntry.SetActiveConfigVersion.class, name = "SET_ACTIVE_CONFIG_VERSION"),
    @JsonSubTypes.Type(value = ClusterEntry.AddMember.class, name = "ADD_MEMBER"),
    @JsonSubTypes.Type(value = ClusterEntry.RemoveMember.class, name = "REMOVE_MEMBER"),
    @JsonSubTypes.Type(value = ClusterEntry.TouchMember.class, name = "TOUCH_MEMBER"),
    @JsonSubTypes.Type(value = ClusterEntry.WriteJoinToken.class, name = "WRITE_JOIN_TOKEN"),
    @JsonSubTypes.Type(value = ClusterEntry.RedeemJoinToken.class, name = "REDEEM_JOIN_TOKEN"),
    @JsonSubTypes.Type(value = ClusterEntry.RevokeJoinToken.class, name = "REVOKE_JOIN_TOKEN"),
    @JsonSubTypes.Type(value = ClusterEntry.GrantLease.class, name = "GRANT_LEASE"),
    @JsonSubTypes.Type(value = ClusterEntry.RenewLease.class, name = "RENEW_LEASE"),
    @JsonSubTypes.Type(value = ClusterEntry.ReleaseLease.class, name = "RELEASE_LEASE"),
    @JsonSubTypes.Type(value = ClusterEntry.WriteClusterFile.class, name = "WRITE_CLUSTER_FILE"),
    @JsonSubTypes.Type(value = ClusterEntry.DeleteClusterFile.class, name = "DELETE_CLUSTER_FILE")
})
public sealed interface ClusterEntry {

    ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    default ByteString encode() {
        try {
            return ByteString.copyFrom(MAPPER.writeValueAsBytes(this));
        } catch (IOException e) {
            throw new IllegalStateException("encode ClusterEntry", e);
        }
    }

    static ClusterEntry decode(ByteString bytes) {
        try {
            return MAPPER.readValue(bytes.toByteArray(), ClusterEntry.class);
        } catch (IOException e) {
            throw new IllegalStateException("decode ClusterEntry", e);
        }
    }

    record SetClusterMeta(ClusterMeta meta) implements ClusterEntry {}

    record RotateSeed(String newSeedSecretBase64, String rotatedBy, Instant rotatedAt) implements ClusterEntry {}

    /**
     * Append a new versioned config patch. Validation in the state machine:
     * {@code version} must equal current-max-version + 1; {@code parentVersion}
     * must equal the current active version (concurrency control). Failure
     * returns a structured reply the client uses to either rebase or surface a 409.
     */
    record WriteConfigVersion(ClusterConfigVersion version) implements ClusterEntry {}

    record SetActiveConfigVersion(int version, String setBy, Instant setAt) implements ClusterEntry {}

    record AddMember(Member member) implements ClusterEntry {}

    record RemoveMember(String nodeId, String reason, Instant removedAt) implements ClusterEntry {}

    record TouchMember(String nodeId, Instant lastSeen) implements ClusterEntry {}

    /** Persist a new outstanding token. Idempotent on duplicate jti (no-op). */
    record WriteJoinToken(JoinToken token) implements ClusterEntry {}

    /**
     * Single-use redemption. Apply rejects if the token is already redeemed,
     * revoked, or expired; the reply carries the rejection reason.
     */
    record RedeemJoinToken(String jti, Instant redeemedAt, String redeemedFrom, String redeemedAs)
            implements ClusterEntry {}

    record RevokeJoinToken(String jti, String revokedBy, Instant revokedAt) implements ClusterEntry {}

    record GrantLease(String name, String holder, Instant grantedAt, long ttlMillis) implements ClusterEntry {}

    record RenewLease(String name, String holder, Instant renewedAt) implements ClusterEntry {}

    record ReleaseLease(String name, String holder) implements ClusterEntry {}

    /**
     * Persist a small binary blob (cluster CA cert/key, future trust roots).
     * Idempotent on identical bytes for the same key; mismatched bytes for an
     * existing key are rejected unless the caller intends an overwrite — for
     * now we accept overwrites silently and rely on the caller to gate them.
     */
    record WriteClusterFile(ClusterFile file) implements ClusterEntry {}

    record DeleteClusterFile(String key) implements ClusterEntry {}

    // --- Reply shape ---

    /**
     * Structured apply-reply. Writes that conflict (wrong parentVersion, replayed
     * token, lease held by someone else) return {@code ok=false} with a
     * machine-parseable {@code code} and a human-readable {@code message}; the
     * call layer turns this into an exception or a 409 as appropriate.
     */
    record Reply(boolean ok, String code, String message, Map<String, Object> data) {
        public static Reply success() {
            return new Reply(true, "OK", null, Map.of());
        }

        public static Reply success(Map<String, Object> data) {
            return new Reply(true, "OK", null, data);
        }

        public static Reply rejected(String code, String message) {
            return new Reply(false, code, message, Map.of());
        }

        public ByteString encode() {
            try {
                return ByteString.copyFrom(MAPPER.writeValueAsBytes(this));
            } catch (IOException e) {
                throw new IllegalStateException("encode Reply", e);
            }
        }

        public static Reply decode(ByteString bytes) {
            try {
                return MAPPER.readValue(bytes.toByteArray(), Reply.class);
            } catch (IOException e) {
                throw new IllegalStateException("decode Reply", e);
            }
        }
    }
}
