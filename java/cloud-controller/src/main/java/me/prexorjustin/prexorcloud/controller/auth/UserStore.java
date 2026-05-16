package me.prexorjustin.prexorcloud.controller.auth;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Persistence interface for user accounts.
 */
public interface UserStore {

    List<User> loadAll() throws IOException;

    Optional<User> getByUsername(String username) throws IOException;

    User create(String username, String passwordHash, String role) throws IOException;

    void update(String username, String newUsername, String role, String passwordHash) throws IOException;

    void updateAvatar(String username, String avatarPath) throws IOException;

    void updateMinecraftLink(String username, String minecraftUuid, String minecraftName) throws IOException;

    void delete(String username) throws IOException;

    /**
     * Look up a user by their email address (case-insensitive). Returns empty
     * when no user has the given email. Default implementation scans
     * {@link #loadAll()} and is suitable for in-memory test stores; production
     * stores should override with an indexed query.
     */
    default Optional<User> findByEmail(String email) throws IOException {
        if (email == null || email.isBlank()) return Optional.empty();
        String needle = email.toLowerCase();
        for (User u : loadAll()) {
            if (u.email() != null && needle.equals(u.email().toLowerCase())) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    /**
     * Set or clear the email address on an existing user. Pass {@code null} or
     * blank to clear it. Implementations should normalise to lower case before
     * persisting so {@link #findByEmail(String)} matches consistently.
     */
    default void updateEmail(String username, String email) throws IOException {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support email updates");
    }
}
