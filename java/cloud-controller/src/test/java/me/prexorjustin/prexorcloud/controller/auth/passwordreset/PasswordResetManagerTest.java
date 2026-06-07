package me.prexorjustin.prexorcloud.controller.auth.passwordreset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.auth.User;
import me.prexorjustin.prexorcloud.controller.auth.UserStore;
import me.prexorjustin.prexorcloud.security.password.PasswordHasher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PasswordResetManager")
class PasswordResetManagerTest {

    @Test
    @DisplayName("request for unknown email is silently dropped (no enumeration leak)")
    void requestUnknownEmailIsSilent() {
        var users = new InMemoryUserStore();
        var tokens = new InMemoryPasswordResetTokenStore();
        var mailer = new LogMailer();
        var manager = new PasswordResetManager(users, tokens, mailer, Duration.ofMinutes(30), "https://dash.example");

        manager.request("nobody@example.com");

        assertEquals(0, tokens.size(), "no token must be issued");
        assertNull(mailer.lastSent(), "no mail must be sent");
    }

    @Test
    @DisplayName("request for known email mints a token and emails the link")
    void requestKnownEmailMintsToken() {
        var users = new InMemoryUserStore();
        users.put(new User("alice", "$argon2id$dummy", "ADMIN", null, null, null, "2026-01-01", "alice@example.com"));
        var tokens = new InMemoryPasswordResetTokenStore();
        var mailer = new LogMailer();
        var manager = new PasswordResetManager(users, tokens, mailer, Duration.ofMinutes(30), "https://dash.example/");

        manager.request("ALICE@example.com");

        assertEquals(1, tokens.size(), "exactly one token must be issued");
        var sent = mailer.lastSent();
        assertNotNull(sent);
        assertEquals("alice@example.com", sent.to());
        assertTrue(
                sent.body().contains("https://dash.example/auth/reset-password?token="),
                "body must include the reset URL");
    }

    @Test
    @DisplayName("request for password-less user is dropped (no local password to reset)")
    void requestPasswordlessUserIsDropped() {
        var users = new InMemoryUserStore();
        users.put(new User("carol", null, "VIEWER", null, null, null, "2026-01-01", "carol@example.com"));
        var tokens = new InMemoryPasswordResetTokenStore();
        var mailer = new LogMailer();
        var manager = new PasswordResetManager(users, tokens, mailer, Duration.ofMinutes(30), "https://dash.example");

        manager.request("carol@example.com");

        assertEquals(0, tokens.size());
        assertNull(mailer.lastSent());
    }

    @Test
    @DisplayName("complete with valid token rewrites password hash")
    void completeWithValidTokenRewritesHash() throws Exception {
        var users = new InMemoryUserStore();
        users.put(new User(
                "alice",
                PasswordHasher.hash("oldpassword"),
                "ADMIN",
                null,
                null,
                null,
                "2026-01-01",
                "alice@example.com"));
        var tokens = new InMemoryPasswordResetTokenStore();
        var mailer = new LogMailer();
        var manager = new PasswordResetManager(users, tokens, mailer, Duration.ofMinutes(30), "https://dash.example");

        manager.request("alice@example.com");
        String tokenId = extractToken(mailer.lastSent().body());

        var outcome = manager.complete(tokenId, "newSecret123");
        assertInstanceOf(PasswordResetManager.CompleteOutcome.Success.class, outcome);

        var alice = users.getByUsername("alice").orElseThrow();
        assertTrue(
                PasswordHasher.verify("newSecret123", alice.passwordHash()),
                "new password must verify against the stored hash");
        assertTrue(!PasswordHasher.verify("oldpassword", alice.passwordHash()), "old password must no longer verify");
    }

    @Test
    @DisplayName("complete with weak password is rejected before token is consumed")
    void completeRejectsWeakPassword() {
        var users = new InMemoryUserStore();
        users.put(new User(
                "alice",
                PasswordHasher.hash("oldpassword"),
                "ADMIN",
                null,
                null,
                null,
                "2026-01-01",
                "alice@example.com"));
        var mailer = new LogMailer();
        var manager = new PasswordResetManager(
                users, new InMemoryPasswordResetTokenStore(), mailer, Duration.ofMinutes(30), "https://dash.example");

        manager.request("alice@example.com");
        String tokenId = extractToken(mailer.lastSent().body());

        var weak = manager.complete(tokenId, "short");
        assertInstanceOf(PasswordResetManager.CompleteOutcome.WeakPassword.class, weak);

        // Token must not have been consumed — a valid retry still succeeds.
        var ok = manager.complete(tokenId, "stronger-password");
        assertInstanceOf(PasswordResetManager.CompleteOutcome.Success.class, ok);
    }

    @Test
    @DisplayName("complete with replayed token is rejected (single-use)")
    void completeReplayRejected() {
        var users = new InMemoryUserStore();
        users.put(new User(
                "alice",
                PasswordHasher.hash("oldpassword"),
                "ADMIN",
                null,
                null,
                null,
                "2026-01-01",
                "alice@example.com"));
        var tokens = new InMemoryPasswordResetTokenStore();
        var mailer = new LogMailer();
        var manager = new PasswordResetManager(users, tokens, mailer, Duration.ofMinutes(30), "https://dash.example");

        manager.request("alice@example.com");
        String tokenId = extractToken(mailer.lastSent().body());

        var first = manager.complete(tokenId, "newSecret123");
        assertInstanceOf(PasswordResetManager.CompleteOutcome.Success.class, first);

        var second = manager.complete(tokenId, "anotherSecret");
        assertInstanceOf(PasswordResetManager.CompleteOutcome.InvalidToken.class, second);
    }

    @Test
    @DisplayName("complete with unknown / blank token is rejected")
    void completeUnknownTokenRejected() {
        var users = new InMemoryUserStore();
        var manager = new PasswordResetManager(
                users,
                new InMemoryPasswordResetTokenStore(),
                new LogMailer(),
                Duration.ofMinutes(30),
                "https://dash.example");
        assertInstanceOf(
                PasswordResetManager.CompleteOutcome.InvalidToken.class,
                manager.complete("does-not-exist", "longenough"));
        assertInstanceOf(PasswordResetManager.CompleteOutcome.InvalidToken.class, manager.complete("", "longenough"));
        assertInstanceOf(PasswordResetManager.CompleteOutcome.InvalidToken.class, manager.complete(null, "longenough"));
    }

    private static String extractToken(String body) {
        int idx = body.indexOf("token=");
        if (idx < 0) throw new IllegalStateException("no token in body: " + body);
        int end = body.indexOf('\n', idx);
        if (end < 0) end = body.length();
        return body.substring(idx + "token=".length(), end).trim();
    }

    /** Minimal in-memory UserStore for tests. */
    private static final class InMemoryUserStore implements UserStore {

        private final Map<String, User> users = new HashMap<>();

        void put(User u) {
            users.put(u.username(), u);
        }

        @Override
        public List<User> loadAll() {
            return new ArrayList<>(users.values());
        }

        @Override
        public Optional<User> getByUsername(String username) {
            return Optional.ofNullable(users.get(username));
        }

        @Override
        public User create(String username, String passwordHash, String role) {
            var u = new User(
                    username,
                    passwordHash,
                    role,
                    null,
                    null,
                    null,
                    Instant.now().toString(),
                    null);
            users.put(username, u);
            return u;
        }

        @Override
        public void update(String username, String newUsername, String role, String passwordHash) {
            var u = users.get(username);
            if (u == null) return;
            String name = newUsername != null ? newUsername : u.username();
            users.remove(username);
            users.put(
                    name,
                    new User(
                            name,
                            passwordHash != null ? passwordHash : u.passwordHash(),
                            role != null ? role : u.role(),
                            u.avatarPath(),
                            u.minecraftUuid(),
                            u.minecraftName(),
                            u.createdAt(),
                            u.email()));
        }

        @Override
        public void updateAvatar(String username, String avatarPath) {}

        @Override
        public void updateMinecraftLink(String username, String minecraftUuid, String minecraftName) {}

        @Override
        public void delete(String username) {
            users.remove(username);
        }
    }
}
