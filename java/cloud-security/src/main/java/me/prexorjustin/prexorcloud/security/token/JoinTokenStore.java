package me.prexorjustin.prexorcloud.security.token;

import java.util.List;
import java.util.Optional;

public interface JoinTokenStore {

    /**
     * Create a new join token for the given node.
     *
     * @param nodeId
     *            target node identifier
     * @param ttlSeconds
     *            time-to-live in seconds
     * @return the created token; the plaintext token value is returned only once
     */
    JoinTokenResult create(String nodeId, int ttlSeconds);

    /**
     * Validate a plaintext token. Returns the token record if valid and not
     * expired.
     */
    Optional<JoinToken> validate(String plaintextToken);

    /**
     * Consume (delete) a token after successful use. Tokens are single-use.
     */
    void consume(String tokenId);

    /**
     * List all tokens (including expired).
     */
    List<JoinToken> list();

    /**
     * Remove expired tokens.
     */
    void cleanup();

    /**
     * Result of token creation, including the plaintext value (returned only once).
     */
    record JoinTokenResult(JoinToken token, String plaintextToken) {
        @Override
        public String toString() {
            // Why: the default record toString would emit the plaintext token. Audit logs or accidental
            // log/exception paths that hit toString must never leak the one-shot bootstrap credential.
            return "JoinTokenResult[token=" + token + ", plaintextToken=<redacted>]";
        }
    }
}
