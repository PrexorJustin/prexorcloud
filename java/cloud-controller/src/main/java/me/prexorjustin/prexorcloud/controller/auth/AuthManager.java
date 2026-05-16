package me.prexorjustin.prexorcloud.controller.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import me.prexorjustin.prexorcloud.controller.config.LockoutConfig;
import me.prexorjustin.prexorcloud.security.jwt.JwtManager;
import me.prexorjustin.prexorcloud.security.password.PasswordHasher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central authentication and user management. Creates a default admin user on
 * first start if no users exist.
 */
public final class AuthManager {

    private static final Logger logger = LoggerFactory.getLogger(AuthManager.class);

    private final UserStore userConfigLoader;
    private final JwtManager jwtManager;
    private final LoginAttemptStore attemptStore;
    private final LockoutConfig lockout;

    public AuthManager(UserStore userConfigLoader, JwtManager jwtManager) {
        this(userConfigLoader, jwtManager, new InMemoryLoginAttemptStore(), disabledLockout());
    }

    public AuthManager(
            UserStore userConfigLoader, JwtManager jwtManager, LoginAttemptStore attemptStore, LockoutConfig lockout) {
        this.userConfigLoader = Objects.requireNonNull(userConfigLoader, "userConfigLoader");
        this.jwtManager = Objects.requireNonNull(jwtManager, "jwtManager");
        this.attemptStore = Objects.requireNonNull(attemptStore, "attemptStore");
        this.lockout = Objects.requireNonNull(lockout, "lockout");
    }

    private static LockoutConfig disabledLockout() {
        return new LockoutConfig(Boolean.FALSE, 5, 900, 900);
    }

    /** Underlying user store. Exposed so collaborators (e.g. password-reset) can share the same persistence. */
    public UserStore userStore() {
        return userConfigLoader;
    }

    /**
     * Ensure at least one admin user exists. If not, create one with a random
     * password.
     *
     * @param configuredPassword
     *            password from config (if set)
     */
    public void ensureAdminUser(String configuredPassword) {
        try {
            List<User> users = userConfigLoader.loadAll();
            if (!users.isEmpty()) return;

            String password;
            if (configuredPassword != null && !configuredPassword.isBlank()) {
                password = configuredPassword;
            } else {
                password = generateRandomPassword();
            }

            String hash = PasswordHasher.hash(password);
            userConfigLoader.create("admin", hash, Role.ADMIN);

            // Write password to a file instead of logging it (prevents exposure in log
            // aggregation)
            try {
                Path passwordFile = Path.of("config", ".initial-admin-password");
                Files.createDirectories(passwordFile.getParent());
                Files.writeString(passwordFile, password);
                me.prexorjustin.prexorcloud.common.util.FilePermissions.setOwnerOnly(passwordFile);
                logger.info("==========================================================");
                logger.info("  Created default admin user");
                logger.info("  Username: admin");
                logger.info("  Password: saved to {}", passwordFile);
                logger.info("  CHANGE THIS PASSWORD AND DELETE THE FILE AFTER FIRST LOGIN!");
                logger.info("==========================================================");
            } catch (IOException _) {
                // Fallback: log masked password if file write fails (e.g. in tests)
                logger.info("==========================================================");
                logger.info("  Created default admin user");
                logger.info("  Username: admin");
                logger.info("  Password: {}***{}", password.substring(0, 2), password.substring(password.length() - 2));
                logger.info("  CHANGE THIS PASSWORD AFTER FIRST LOGIN!");
                logger.info("==========================================================");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to ensure admin user: " + e.getMessage(), e);
        }
    }

    /**
     * Authenticate with username/password. Returns a sealed
     * {@link LoginOutcome} so callers must handle invalid-credentials and
     * locked-account cases distinctly.
     */
    public LoginOutcome login(String username, String password) {
        String normalized = username == null ? "" : username.toLowerCase();
        if (lockout.isEnabled()) {
            Optional<Instant> lockedUntil = attemptStore.lockedUntil(normalized);
            if (lockedUntil.isPresent()) {
                return new LoginOutcome.Locked(lockedUntil.get());
            }
        }
        try {
            Optional<User> userOpt = userConfigLoader.getByUsername(normalized);
            if (userOpt.isEmpty()) {
                return new LoginOutcome.InvalidCredentials();
            }

            User user = userOpt.get();
            if (user.passwordHash() == null || user.passwordHash().isEmpty()) {
                return new LoginOutcome.InvalidCredentials();
            }
            if (!PasswordHasher.verify(password, user.passwordHash())) {
                if (lockout.isEnabled()) {
                    int count = attemptStore.recordFailure(normalized, Duration.ofSeconds(lockout.windowSeconds()));
                    if (count >= lockout.maxAttempts()) {
                        Instant until = Instant.now().plusSeconds(lockout.lockoutSeconds());
                        attemptStore.lockUntil(normalized, until);
                        logger.warn(
                                "User '{}' locked until {} after {} failed login attempts", normalized, until, count);
                        return new LoginOutcome.Locked(until);
                    }
                }
                return new LoginOutcome.InvalidCredentials();
            }

            if (lockout.isEnabled()) attemptStore.clear(normalized);
            String token = jwtManager.issue(user.username(), user.role());
            return new LoginOutcome.Success(token, user);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to login: " + e.getMessage(), e);
        }
    }

    /**
     * Refresh a JWT (issue a new one with fresh expiry). Re-verifies the user still
     * exists and uses the current role from the store.
     */
    public Optional<String> refresh(String username, String role) {
        try {
            Optional<User> userOpt = userConfigLoader.getByUsername(username.toLowerCase());
            if (userOpt.isEmpty()) {
                logger.warn("Token refresh denied — user '{}' no longer exists", username);
                return Optional.empty();
            }
            User user = userOpt.get();
            // Use the current role from the store, not the one from the old token
            return Optional.of(jwtManager.issue(user.username(), user.role()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to refresh token: " + e.getMessage(), e);
        }
    }

    /** Operator-initiated unlock. Clears any active lock and failure counter. */
    public void unlockUser(String username) {
        if (username == null) return;
        attemptStore.clear(username.toLowerCase());
    }

    // --- User CRUD ---

    public User createUser(String username, String password, String role) {
        if (!Role.isValid(role)) {
            throw new IllegalArgumentException("Invalid role: " + role);
        }
        validateUsername(username);
        String normalized = username.toLowerCase();
        try {
            if (userConfigLoader.getByUsername(normalized).isPresent()) {
                throw new IllegalArgumentException("Username already exists: " + username);
            }
            String hash = PasswordHasher.hash(password);
            return userConfigLoader.create(normalized, hash, role);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create user: " + e.getMessage(), e);
        }
    }

    public Optional<User> getUser(String username) {
        try {
            return userConfigLoader.getByUsername(username);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to get user: " + e.getMessage(), e);
        }
    }

    public Optional<User> getUserByUsername(String username) {
        return getUser(username.toLowerCase());
    }

    public List<User> getAllUsers() {
        try {
            return userConfigLoader.loadAll();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load users: " + e.getMessage(), e);
        }
    }

    public void updateUser(String username, String newUsername, String role, String newPassword) {
        if (newUsername != null && newUsername.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
        String normalized = newUsername != null ? newUsername.toLowerCase() : null;
        try {
            if (normalized != null) {
                var existing = userConfigLoader.getByUsername(normalized);
                if (existing.isPresent() && !existing.get().username().equals(username)) {
                    throw new IllegalArgumentException("Username already taken");
                }
            }
            if (role != null && !Role.isValid(role)) {
                throw new IllegalArgumentException("Invalid role: " + role);
            }
            String hash = newPassword != null ? PasswordHasher.hash(newPassword) : null;
            userConfigLoader.update(username, normalized, role, hash);

            // Once the admin user changes their password, the bootstrap password file is no
            // longer the canonical credential — remove it so it can't accidentally leak.
            if (newPassword != null && "admin".equals(username)) {
                Path passwordFile = Path.of("config", ".initial-admin-password");
                try {
                    if (Files.deleteIfExists(passwordFile)) {
                        logger.info("Removed bootstrap password file {} after admin password change", passwordFile);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to delete bootstrap password file {}: {}", passwordFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update user: " + e.getMessage(), e);
        }
    }

    public void updateMinecraftLink(String username, String minecraftUuid, String minecraftName) {
        try {
            userConfigLoader.updateMinecraftLink(username, minecraftUuid, minecraftName);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update Minecraft link: " + e.getMessage(), e);
        }
    }

    public void updateUserAvatar(String username, String avatarPath) {
        try {
            userConfigLoader.updateAvatar(username, avatarPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update avatar: " + e.getMessage(), e);
        }
    }

    public void deleteUser(String username) {
        try {
            userConfigLoader.delete(username);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete user: " + e.getMessage(), e);
        }
    }

    private static void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
        if (username.length() > 32) {
            throw new IllegalArgumentException("Username too long (max 32)");
        }
        if (!username.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid username: must match [a-zA-Z0-9][a-zA-Z0-9._-]*");
        }
    }

    private static String generateRandomPassword() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%";
        SecureRandom random = new SecureRandom();
        var sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public sealed interface LoginOutcome {

        record Success(String token, User user) implements LoginOutcome {}

        record InvalidCredentials() implements LoginOutcome {}

        record Locked(Instant lockedUntil) implements LoginOutcome {}
    }
}
