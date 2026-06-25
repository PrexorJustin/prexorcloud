package me.prexorjustin.prexorcloud.controller.template;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import me.prexorjustin.prexorcloud.common.util.HashUtil;
import me.prexorjustin.prexorcloud.controller.group.spec.VariableResolver;
import me.prexorjustin.prexorcloud.controller.state.StateStore;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Packages a single template as tar.gz. No more parent chain merging --
 * templates are individual packages merged on daemons.
 */
public final class TemplateMerger {

    private static final Logger logger = LoggerFactory.getLogger(TemplateMerger.class);

    private final TemplateManager templateManager;
    private final StateStore stateStore;

    public TemplateMerger(TemplateManager templateManager, StateStore stateStore) {
        this.templateManager = templateManager;
        this.stateStore = stateStore;
    }

    /**
     * Package a single template's files as tar.gz. Text files are processed for
     * {{variable}} resolution before packaging.
     *
     * @return MergeResult with tar.gz bytes and SHA-256 hash
     */
    public MergeResult packageTemplate(String templateName) throws IOException {
        Path filesDir = templateManager.getTemplateFilesDir(templateName);
        if (!Files.isDirectory(filesDir)) {
            // Template exists in registry but files directory is missing (e.g. auto-created
            // by group wizard,
            // data dir moved, or first-time use). Create it so an empty template is valid.
            Files.createDirectories(filesDir);
            logger.debug("Created missing files directory for template {}", templateName);
        }

        // Build-time {{var}} substitution draws from the one typed variable model: the template's
        // declared defaults, validated. This is the template-shared binding time (the archive is
        // group-agnostic and cached by content hash), so only template-scoped defaults resolve here;
        // group/instance values land later per-instance via the daemon's %VAR% pass.
        Map<String, String> variables = VariableResolver.resolve(
                        stateStore.getTemplateVariableDefs(templateName), Map.of(), Map.of())
                .resolved();

        byte[] tarGz = packageTarGz(filesDir, variables);
        String hash = HashUtil.sha256(tarGz);

        logger.debug("Packaged template {} (hash={}, variables={})", templateName, hash, variables.size());
        return new MergeResult(tarGz, hash);
    }

    // Fixed epoch for deterministic tar entries — ensures hash stability across
    // restarts.
    private static final long FIXED_EPOCH_MS =
            Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();

    private static byte[] packageTarGz(Path directory, Map<String, String> variables) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(baos);
                var tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            Files.walkFileTree(directory, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.equals(directory)) return FileVisitResult.CONTINUE;
                    String name = directory.relativize(dir).toString().replace('\\', '/') + "/";
                    var entry = new TarArchiveEntry(name);
                    entry.setModTime(FIXED_EPOCH_MS);
                    tar.putArchiveEntry(entry);
                    tar.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = directory.relativize(file).toString().replace('\\', '/');

                    if (!variables.isEmpty() && isTextFile(file)) {
                        // Resolve variables in text files
                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        String resolved = TemplateVariableProcessor.resolve(content, variables);
                        byte[] resolvedBytes = resolved.getBytes(StandardCharsets.UTF_8);

                        var entry = new TarArchiveEntry(name);
                        entry.setSize(resolvedBytes.length);
                        entry.setModTime(FIXED_EPOCH_MS);
                        tar.putArchiveEntry(entry);
                        tar.write(resolvedBytes);
                    } else {
                        // Binary file -- use name-only constructor for deterministic output
                        var entry = new TarArchiveEntry(name);
                        entry.setSize(Files.size(file));
                        entry.setModTime(FIXED_EPOCH_MS);
                        tar.putArchiveEntry(entry);
                        Files.copy(file, tar);
                    }
                    tar.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return baos.toByteArray();
    }

    private static boolean isTextFile(Path file) {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return false;
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TemplateVariableProcessor.TEXT_EXTENSIONS.contains(ext);
    }

    public record MergeResult(byte[] tarGz, String hash) {}
}
