package me.prexorjustin.prexorcloud.daemon.template;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts a tar.gz template archive to an instance directory.
 *
 * <p>
 * When a config file ({@code .yml}, {@code .yaml}, {@code .properties}) already
 * exists from a lower template layer, it is deep-merged rather than
 * overwritten. This allows higher layers to override specific keys (e.g.
 * {@code misc.enable-nether: false}) without replacing the entire file.
 * Non-config files and binary files are always overwritten by the higher layer.
 */
public final class TemplateUnpacker {

    private static final Logger logger = LoggerFactory.getLogger(TemplateUnpacker.class);
    private static final long MAX_TOTAL_SIZE = 512L * 1024 * 1024;
    private static final long MAX_ENTRY_SIZE = 128L * 1024 * 1024;
    private static final int MAX_ENTRIES = 50_000;

    private TemplateUnpacker() {}

    /**
     * Extract a tar.gz byte array to the target directory. Config files that
     * already exist are deep-merged instead of overwritten.
     */
    public static void unpack(byte[] tarGzData, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        try (var bais = new ByteArrayInputStream(tarGzData);
                var gzip = new GZIPInputStream(bais);
                var tar = new TarArchiveInputStream(gzip)) {

            TarArchiveEntry entry;
            long totalSize = 0;
            int entryCount = 0;
            while ((entry = tar.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IOException("Template archive exceeds maximum entry count (" + MAX_ENTRIES + ")");
                }
                if (entry.getSize() > MAX_ENTRY_SIZE) {
                    throw new IOException("Template entry '" + entry.getName() + "' exceeds maximum size ("
                            + MAX_ENTRY_SIZE + " bytes)");
                }
                totalSize += entry.getSize();
                if (totalSize > MAX_TOTAL_SIZE) {
                    throw new IOException("Template archive exceeds maximum total size (" + MAX_TOTAL_SIZE + " bytes)");
                }

                Path outputPath = targetDir.resolve(entry.getName()).normalize();

                // Guard against zip-slip
                if (!outputPath.startsWith(targetDir)) {
                    throw new IOException("Tar entry outside target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                } else {
                    Files.createDirectories(outputPath.getParent());

                    if (Files.exists(outputPath) && ConfigMerger.isMergeable(outputPath)) {
                        // Deep-merge config files from higher layers
                        byte[] overlayData = tar.readAllBytes();
                        ConfigMerger.merge(outputPath, overlayData);
                    } else {
                        try (OutputStream out = Files.newOutputStream(outputPath)) {
                            tar.transferTo(out);
                        }
                    }
                }
            }
        }

        logger.debug("Unpacked template to {}", targetDir);
    }

    /**
     * Extract a tar.gz byte array to the target directory, skipping files that
     * match protected paths if they already exist (for static instances).
     */
    public static void unpackWithProtectedPaths(byte[] tarGzData, Path targetDir, Set<String> protectedPaths)
            throws IOException {
        Files.createDirectories(targetDir);

        try (var bais = new ByteArrayInputStream(tarGzData);
                var gzip = new GZIPInputStream(bais);
                var tar = new TarArchiveInputStream(gzip)) {

            TarArchiveEntry entry;
            long totalSize = 0;
            int entryCount = 0;
            while ((entry = tar.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IOException("Template archive exceeds maximum entry count (" + MAX_ENTRIES + ")");
                }
                if (entry.getSize() > MAX_ENTRY_SIZE) {
                    throw new IOException("Template entry '" + entry.getName() + "' exceeds maximum size ("
                            + MAX_ENTRY_SIZE + " bytes)");
                }
                totalSize += entry.getSize();
                if (totalSize > MAX_TOTAL_SIZE) {
                    throw new IOException("Template archive exceeds maximum total size (" + MAX_TOTAL_SIZE + " bytes)");
                }

                Path outputPath = targetDir.resolve(entry.getName()).normalize();

                if (!outputPath.startsWith(targetDir)) {
                    throw new IOException("Tar entry outside target directory: " + entry.getName());
                }

                // Skip protected paths that already exist
                String entryName = entry.getName().replace('\\', '/');
                if (Files.exists(outputPath) && isProtected(entryName, protectedPaths)) {
                    logger.debug("Skipping protected path: {}", entryName);
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                } else {
                    Files.createDirectories(outputPath.getParent());

                    if (Files.exists(outputPath) && ConfigMerger.isMergeable(outputPath)) {
                        byte[] overlayData = tar.readAllBytes();
                        ConfigMerger.merge(outputPath, overlayData);
                    } else {
                        try (OutputStream out = Files.newOutputStream(outputPath)) {
                            tar.transferTo(out);
                        }
                    }
                }
            }
        }

        logger.debug("Unpacked template to {} (with {} protected paths)", targetDir, protectedPaths.size());
    }

    private static boolean isProtected(String entryName, Set<String> protectedPaths) {
        for (String pp : protectedPaths) {
            if (entryName.equals(pp) || entryName.startsWith(pp + "/")) {
                return true;
            }
        }
        return false;
    }
}
