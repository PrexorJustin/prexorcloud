package me.prexorjustin.prexorcloud.controller.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads, saves, and manages user accounts from {@code config/users.yml}.
 * Creates an empty file if missing.
 */
public final class UserConfigLoader implements UserStore {

    private static final Logger logger = LoggerFactory.getLogger(UserConfigLoader.class);
    private static final TypeReference<List<User>> LIST_TYPE = new TypeReference<>() {};

    private final Path usersFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public UserConfigLoader(Path configDir) throws IOException {
        this.usersFile = configDir.resolve("users.yml");
        ensureFile();
    }

    public List<User> loadAll() throws IOException {
        lock.readLock().lock();
        try {
            var users = YamlConfigLoader.mapper().readValue(usersFile.toFile(), LIST_TYPE);
            return users != null ? users : List.of();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<User> getByUsername(String username) throws IOException {
        return loadAll().stream().filter(u -> u.username().equals(username)).findFirst();
    }

    public User create(String username, String passwordHash, String role) throws IOException {
        lock.writeLock().lock();
        try {
            var users = new ArrayList<>(loadAllInternal());
            var user = new User(
                    username,
                    passwordHash,
                    role,
                    null,
                    null,
                    null,
                    Instant.now().toString());
            users.add(user);
            writeAll(users);
            logger.debug("Created user: {}", username);
            return user;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void update(String username, String newUsername, String role, String passwordHash) throws IOException {
        lock.writeLock().lock();
        try {
            var users = new ArrayList<>(loadAllInternal());
            for (int i = 0; i < users.size(); i++) {
                var u = users.get(i);
                if (!u.username().equals(username)) continue;
                users.set(
                        i,
                        new User(
                                newUsername != null ? newUsername : u.username(),
                                passwordHash != null ? passwordHash : u.passwordHash(),
                                role != null ? role : u.role(),
                                u.avatarPath(),
                                u.minecraftUuid(),
                                u.minecraftName(),
                                u.createdAt(),
                                u.email()));
                break;
            }
            writeAll(users);
            logger.debug("Updated user: {}", username);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateAvatar(String username, String avatarPath) throws IOException {
        lock.writeLock().lock();
        try {
            var users = new ArrayList<>(loadAllInternal());
            for (int i = 0; i < users.size(); i++) {
                var u = users.get(i);
                if (!u.username().equals(username)) continue;
                users.set(
                        i,
                        new User(
                                u.username(),
                                u.passwordHash(),
                                u.role(),
                                avatarPath,
                                u.minecraftUuid(),
                                u.minecraftName(),
                                u.createdAt(),
                                u.email()));
                break;
            }
            writeAll(users);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void updateMinecraftLink(String username, String minecraftUuid, String minecraftName) throws IOException {
        lock.writeLock().lock();
        try {
            var users = new ArrayList<>(loadAllInternal());
            for (int i = 0; i < users.size(); i++) {
                var u = users.get(i);
                if (!u.username().equals(username)) continue;
                users.set(
                        i,
                        new User(
                                u.username(),
                                u.passwordHash(),
                                u.role(),
                                u.avatarPath(),
                                minecraftUuid,
                                minecraftName,
                                u.createdAt(),
                                u.email()));
                break;
            }
            writeAll(users);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void updateEmail(String username, String email) throws IOException {
        String normalized = (email == null || email.isBlank()) ? null : email.toLowerCase();
        lock.writeLock().lock();
        try {
            var users = new ArrayList<>(loadAllInternal());
            for (int i = 0; i < users.size(); i++) {
                var u = users.get(i);
                if (!u.username().equals(username)) continue;
                users.set(
                        i,
                        new User(
                                u.username(),
                                u.passwordHash(),
                                u.role(),
                                u.avatarPath(),
                                u.minecraftUuid(),
                                u.minecraftName(),
                                u.createdAt(),
                                normalized));
                break;
            }
            writeAll(users);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(String username) throws IOException {
        lock.writeLock().lock();
        try {
            var users = new ArrayList<>(loadAllInternal());
            users.removeIf(u -> u.username().equals(username));
            writeAll(users);
            logger.debug("Deleted user: {}", username);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<User> loadAllInternal() throws IOException {
        var users = YamlConfigLoader.mapper().readValue(usersFile.toFile(), LIST_TYPE);
        return users != null ? users : List.of();
    }

    private void writeAll(List<User> users) throws IOException {
        YamlConfigLoader.mapper().writeValue(usersFile.toFile(), users);
    }

    private void ensureFile() throws IOException {
        if (Files.exists(usersFile)) return;
        Files.createDirectories(usersFile.getParent());
        writeAll(List.of());
        logger.debug("Created empty users config at {}", usersFile);
    }
}
