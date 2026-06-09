package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryPasswordResetTokenStore")
class InMemoryPasswordResetTokenStoreTest {

    private static PasswordResetToken token(String id, String username) {
        return new PasswordResetToken(id, username, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("take returns token and removes it (single-use)")
    void takeIsSingleUse() {
        var store = new InMemoryPasswordResetTokenStore();
        store.store(token("abc", "alice"), Duration.ofMinutes(5));

        var first = store.take("abc");
        assertTrue(first.isPresent());
        assertEquals("alice", first.get().username());

        var second = store.take("abc");
        assertTrue(second.isEmpty(), "second take must be empty — token is single-use");
    }

    @Test
    @DisplayName("expired tokens are not returned")
    void expirySkipped() {
        var store = new InMemoryPasswordResetTokenStore();
        store.store(token("xyz", "bob"), Duration.ofSeconds(-1));
        assertTrue(store.take("xyz").isEmpty());
    }

    @Test
    @DisplayName("missing/null token returns empty")
    void missingToken() {
        var store = new InMemoryPasswordResetTokenStore();
        assertTrue(store.take("missing").isEmpty());
        assertTrue(store.take(null).isEmpty());
    }
}
