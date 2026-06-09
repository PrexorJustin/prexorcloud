package me.prexorjustin.prexorcloud.security.token;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import me.prexorjustin.prexorcloud.security.token.JoinTokenStore.JoinTokenResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JoinToken redaction")
class JoinTokenRedactionTest {

    @Test
    @DisplayName("JoinTokenResult.toString redacts the plaintext token")
    void joinTokenResultToStringRedactsPlaintext() {
        JoinToken token = new JoinToken(
                "tok_123", "node-a", "hash-abc", "pxr_SECRET_PLAINTEXT", Instant.parse("2030-01-01T00:00:00Z"));
        JoinTokenResult result = new JoinTokenResult(token, "pxr_SECRET_PLAINTEXT");

        String stringified = result.toString();
        assertFalse(
                stringified.contains("pxr_SECRET_PLAINTEXT"),
                "JoinTokenResult.toString must not contain the plaintext token, was: " + stringified);
        assertTrue(stringified.contains("<redacted>"));
    }

    @Test
    @DisplayName("JoinToken.toString redacts plainToken")
    void joinTokenToStringRedactsPlainToken() {
        JoinToken token = new JoinToken(
                "tok_123", "node-a", "hash-abc", "pxr_SECRET_PLAINTEXT", Instant.parse("2030-01-01T00:00:00Z"));

        String stringified = token.toString();
        assertFalse(
                stringified.contains("pxr_SECRET_PLAINTEXT"),
                "JoinToken.toString must not contain plainToken, was: " + stringified);
        assertTrue(stringified.contains("<redacted>"));
    }
}
