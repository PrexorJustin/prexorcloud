package me.prexorjustin.prexorcloud.daemon.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs {@code %VARIABLE%} substitution on text files in a template
 * directory. Only processes files with known text extensions.
 */
public final class VariableSubstitution {

    private static final Logger logger = LoggerFactory.getLogger(VariableSubstitution.class);

    private static final Set<String> TEXT_EXTENSIONS =
            Set.of(".properties", ".yml", ".yaml", ".toml", ".json", ".cfg", ".conf", ".txt");

    private VariableSubstitution() {}

    /**
     * Process all text files in the directory, replacing {@code %VAR%} with values
     * from the map.
     */
    public static void processDirectory(Path directory, Map<String, String> variables) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (isTextFile(file)) {
                    processFile(file, variables);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void processFile(Path file, Map<String, String> variables) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String original = content;

        for (var entry : variables.entrySet()) {
            content = content.replace("%" + entry.getKey() + "%", entry.getValue());
        }

        if (!content.equals(original)) {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            logger.debug("Variable substitution in {}", file.getFileName());
        }
    }

    private static boolean isTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
