package me.prexorjustin.prexorcloud.security.token;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public record JoinToken(
        @JsonProperty("tokenId") String tokenId,
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("tokenHash") String tokenHash,
        @JsonIgnore String plainToken,
        @JsonProperty("expiresAt") Instant expiresAt) {

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    @Override
    public String toString() {
        // Why: default record toString would leak plainToken via any log path that stringifies a token.
        return "JoinToken[tokenId=" + tokenId + ", nodeId=" + nodeId + ", tokenHash=" + tokenHash
                + ", plainToken=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
