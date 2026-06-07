package me.prexorjustin.prexorcloud.controller.auth;

/**
 * Represents a user account.
 */
public record User(
        String username,
        String passwordHash,
        String role,
        String avatarPath,
        String minecraftUuid,
        String minecraftName,
        String createdAt,
        String email) {

    public User(
            String username,
            String passwordHash,
            String role,
            String avatarPath,
            String minecraftUuid,
            String minecraftName,
            String createdAt) {
        this(username, passwordHash, role, avatarPath, minecraftUuid, minecraftName, createdAt, null);
    }
}
