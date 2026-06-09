package me.prexorjustin.prexorcloud.controller.group;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import me.prexorjustin.prexorcloud.common.config.YamlConfigLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads, saves, and deletes group configs as YAML files under
 * {@code groups/<name>.yml}. This is the single source of truth for group
 * configuration -- no database involved.
 */
public final class GroupConfigLoader implements GroupStore {

    private static final Logger logger = LoggerFactory.getLogger(GroupConfigLoader.class);

    private final Path groupsDir;

    public GroupConfigLoader(Path groupsDir) {
        this.groupsDir = groupsDir;
    }

    /**
     * Scan the groups directory and load all valid {@code *.yml} files.
     */
    public List<GroupConfig> loadAll() throws IOException {
        Files.createDirectories(groupsDir);
        var configs = new ArrayList<GroupConfig>();

        try (Stream<Path> files = Files.list(groupsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> {
                        try {
                            var config = YamlConfigLoader.mapper().readValue(file.toFile(), GroupConfig.class);
                            configs.add(config);
                            logger.debug("Loaded group config: {}", file.getFileName());
                        } catch (IOException e) {
                            logger.warn("Failed to load group config {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        }

        logger.info("Loaded {} group(s) from {}", configs.size(), groupsDir);
        return configs;
    }

    /**
     * Write a group config to {@code groups/<name>.yml}.
     */
    public void save(GroupConfig config) throws IOException {
        Files.createDirectories(groupsDir);
        Path file = groupsDir.resolve(config.name() + ".yml");
        YamlConfigLoader.mapper().writeValue(file.toFile(), config);
        logger.debug("Saved group config: {}", file.getFileName());
    }

    /**
     * Delete the YAML file for a group.
     */
    public void delete(String name) throws IOException {
        Path file = groupsDir.resolve(name + ".yml");
        Files.deleteIfExists(file);
        logger.debug("Deleted group config: {}", file.getFileName());
    }
}
