package me.prexorjustin.prexorcloud.controller.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads, saves, and deletes role configs from {@code config/roles.yml}. Creates
 * the file from classpath defaults if missing.
 */
public final class RoleConfigLoader implements RoleStore {

    private static final Logger logger = LoggerFactory.getLogger(RoleConfigLoader.class);
    private static final TypeReference<List<RoleConfig>> LIST_TYPE = new TypeReference<>() {};

    private final Path rolesFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public RoleConfigLoader(Path configDir) throws IOException {
        this.rolesFile = configDir.resolve("roles.yml");
        ensureFile();
    }

    /**
     * Load all roles from the YAML file.
     */
    public List<RoleConfig> loadAll() throws IOException {
        lock.readLock().lock();
        try {
            return YamlConfigLoader.mapper().readValue(rolesFile.toFile(), LIST_TYPE);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get a single role by name.
     */
    public Optional<RoleConfig> get(String name) throws IOException {
        return loadAll().stream().filter(r -> r.name().equals(name)).findFirst();
    }

    /**
     * Save (create or update) a role. Upserts by name.
     */
    public void save(RoleConfig role) throws IOException {
        lock.writeLock().lock();
        try {
            var roles = new ArrayList<>(YamlConfigLoader.mapper().readValue(rolesFile.toFile(), LIST_TYPE));
            roles.removeIf(r -> r.name().equals(role.name()));
            roles.add(role);
            YamlConfigLoader.mapper().writeValue(rolesFile.toFile(), roles);
            logger.debug("Saved role: {}", role.name());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Delete a role by name.
     */
    public void delete(String name) throws IOException {
        lock.writeLock().lock();
        try {
            var roles = new ArrayList<>(YamlConfigLoader.mapper().readValue(rolesFile.toFile(), LIST_TYPE));
            roles.removeIf(r -> r.name().equals(name));
            YamlConfigLoader.mapper().writeValue(rolesFile.toFile(), roles);
            logger.debug("Deleted role: {}", name);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void ensureFile() throws IOException {
        if (Files.exists(rolesFile)) return;
        Files.createDirectories(rolesFile.getParent());
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("defaults/roles.yml")) {
            if (in == null) throw new IOException("Default roles.yml not found on classpath");
            Files.copy(in, rolesFile);
            logger.info("Created default roles config at {}", rolesFile);
        }
    }
}
