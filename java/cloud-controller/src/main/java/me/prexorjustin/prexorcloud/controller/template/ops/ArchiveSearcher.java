package me.prexorjustin.prexorcloud.controller.template.ops;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Walks a template's file tree and returns case-insensitive substring
 * matches for a query string.
 *
 * <p>Extracted from the inline implementation that lived in the
 * {@code GET /templates/{name}/search} handler in
 * {@code TemplateRoutes.java}. The handler now delegates to
 * {@link #search(Path, String, int)}.
 *
 * <p>Searchable extensions are pinned to text-y formats (config files,
 * scripts, source). Binary blobs are silently skipped — both because
 * grepping a JAR is meaningless and because reading binary as UTF-8 throws
 * {@link MalformedInputException}, which the visitor swallows to keep
 * the walk going.
 *
 * <p>Stateless. Safe to call from any thread.
 */
public final class ArchiveSearcher {

    /**
     * File extensions the searcher will read. Anything else is skipped
     * silently. Centralised here (was a duplicate Set in TemplateRoutes)
     * so future additions land in one place.
     */
    public static final Set<String> SEARCHABLE_EXTENSIONS = Set.of(
            "yml",
            "yaml",
            "json",
            "properties",
            "conf",
            "config",
            "toml",
            "ini",
            "txt",
            "md",
            "log",
            "sh",
            "bash",
            "zsh",
            "fish",
            "java",
            "kt",
            "kts",
            "groovy",
            "gradle",
            "js",
            "ts",
            "vue",
            "html",
            "css",
            "scss",
            "py",
            "rb",
            "go",
            "rs",
            "xml");

    private ArchiveSearcher() {}

    /** A single match line, ready to be JSON-serialised back to the client. */
    public record Match(String path, int line, String content, int matchStart, int matchEnd) {

        /** Wire format for the existing REST endpoint (preserves field names). */
        public Map<String, Object> toJson() {
            var m = new LinkedHashMap<String, Object>();
            m.put("path", path);
            m.put("line", line);
            m.put("content", content);
            m.put("matchStart", matchStart);
            m.put("matchEnd", matchEnd);
            return m;
        }
    }

    /**
     * Walk {@code baseDir} and return up to {@code maxResults} matches for
     * {@code query}, case-insensitive. Files whose extension isn't in
     * {@link #SEARCHABLE_EXTENSIONS} are skipped.
     *
     * @return non-null list, possibly empty; never longer than maxResults
     */
    public static List<Match> search(Path baseDir, String query, int maxResults) throws IOException {
        List<Match> matches = new ArrayList<>();
        if (!Files.isDirectory(baseDir) || query == null || query.isEmpty() || maxResults <= 0) {
            return matches;
        }
        String queryLower = query.toLowerCase(Locale.ROOT);
        int limit = maxResults;
        Files.walkFileTree(baseDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (matches.size() >= limit) return FileVisitResult.TERMINATE;
                String fileName = file.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                if (dot < 0 || dot == fileName.length() - 1) return FileVisitResult.CONTINUE;
                String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!SEARCHABLE_EXTENSIONS.contains(ext)) return FileVisitResult.CONTINUE;

                String relativePath = baseDir.relativize(file).toString().replace('\\', '/');
                List<String> lines;
                try {
                    lines = Files.readAllLines(file);
                } catch (MalformedInputException _) {
                    // File isn't UTF-8 (binary, latin-1, etc.) — skip silently.
                    return FileVisitResult.CONTINUE;
                } catch (IOException _) {
                    return FileVisitResult.CONTINUE;
                }
                for (int i = 0; i < lines.size() && matches.size() < limit; i++) {
                    String line = lines.get(i);
                    int idx = line.toLowerCase(Locale.ROOT).indexOf(queryLower);
                    if (idx >= 0) {
                        matches.add(new Match(relativePath, i + 1, line.trim(), idx, idx + query.length()));
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return matches;
    }
}
