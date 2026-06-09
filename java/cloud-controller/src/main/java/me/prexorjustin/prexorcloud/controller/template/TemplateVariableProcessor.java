package me.prexorjustin.prexorcloud.controller.template;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure utility class for template variable processing. Variables use the
 * {{variable_name}} syntax where names match [a-zA-Z_][a-zA-Z0-9_]*.
 */
public final class TemplateVariableProcessor {

    private static final Logger logger = LoggerFactory.getLogger(TemplateVariableProcessor.class);

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}");

    public static final Set<String> TEXT_EXTENSIONS = Set.of(
            "yml",
            "yaml",
            "json",
            "toml",
            "properties",
            "conf",
            "cfg",
            "ini",
            "txt",
            "md",
            "log",
            "xml",
            "html",
            "css",
            "js",
            "ts",
            "sh",
            "bat");

    private TemplateVariableProcessor() {}

    /**
     * Extracts all variable names from the given content.
     *
     * @param content
     *            the text to scan for {{variable}} patterns
     * @return set of variable names found (without the {{ }} delimiters)
     */
    public static Set<String> extractVariables(String content) {
        if (content == null || content.isEmpty()) return Set.of();

        var variables = new LinkedHashSet<String>();
        var matcher = VARIABLE_PATTERN.matcher(content);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return Collections.unmodifiableSet(variables);
    }

    /**
     * Resolves variables in the content by replacing {{var}} with the corresponding
     * value. Unresolved variables (no matching key in the map) are left as-is.
     *
     * @param content
     *            the text containing {{variable}} placeholders
     * @param variables
     *            map of variable name → value
     * @return the content with resolved variables substituted
     */
    public static String resolve(String content, Map<String, String> variables) {
        if (content == null || content.isEmpty()) return content;
        if (variables == null || variables.isEmpty()) return content;

        var matcher = VARIABLE_PATTERN.matcher(content);
        var result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = variables.get(name);
            if (replacement != null) {
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
            } else {
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Walks a directory tree, scanning all text files for template variables.
     *
     * @param dir
     *            the root directory to scan
     * @return set of all variable names discovered across all text files
     * @throws IOException
     *             if an I/O error occurs while walking the directory
     */
    public static Set<String> scanDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return Set.of();

        var variables = new LinkedHashSet<String>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isTextFile(file)) {
                    try {
                        String content = Files.readString(file);
                        variables.addAll(extractVariables(content));
                    } catch (IOException e) {
                        logger.warn("Failed to read file for variable scanning: {}", file, e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.warn("Failed to visit file during variable scan: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }
        });
        return Collections.unmodifiableSet(variables);
    }

    private static boolean isTextFile(Path file) {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return false;
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.contains(ext);
    }
}
