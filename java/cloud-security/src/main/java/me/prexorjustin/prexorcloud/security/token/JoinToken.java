package me.prexorjustin.prexorcloud.security.token;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// ignoreUnknown tolerates older persisted files that serialized the derived
// "expired" getter (and any future field drift) so the store still loads.
@JsonIgnoreProperties(ignoreUnknown = true)
public record JoinToken(
        @JsonProperty("tokenId") String tokenId,
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("tokenHash") String tokenHash,
        @JsonIgnore String plainToken,
        @JsonProperty("expiresAt") Instant expiresAt) {

    // @JsonIgnore: derived check, not persisted state. Without it Jackson
    // serializes it as an "expired" property that then fails to read back
    // into the canonical constructor (FAIL_ON_UNKNOWN_PROPERTIES).
    @JsonIgnore
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
