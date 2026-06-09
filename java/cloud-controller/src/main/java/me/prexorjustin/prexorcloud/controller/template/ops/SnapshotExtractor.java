package me.prexorjustin.prexorcloud.controller.template.ops;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts a ZIP stream into a target directory, with zip-slip protection
 * pinned to a separate "base" path so callers can choose how strict the
 * containment check is.
 *
 * <p>The extractor was previously inlined twice inside
 * {@code TemplateRoutes.java} — once in the multipart upload handler when
 * {@code ?extract=true} is set, and once in the standalone
 * {@code POST /templates/{name}/files/extract} route — and the two copies
 * had subtly different containment-check pins. This class normalises that:
 * the {@code zipSlipBase} parameter is the absolute path that every
 * extracted entry must remain under, and is the single knob both call
 * sites pass.
 *
 * <p>Stateless. Safe to call from any thread.
 */
public final class SnapshotExtractor {

    private SnapshotExtractor() {}

    /**
     * Extract a ZIP stream into {@code targetDir}, rejecting any entry that
     * would resolve outside {@code zipSlipBase}.
     *
     * @param zipStream     the open ZIP input stream — caller owns closing
     * @param targetDir     directory the entries should land under;
     *                      created if it doesn't exist
     * @param zipSlipBase   absolute path that every extracted entry must
     *                      stay under (typically the template's root files
     *                      directory). Pass {@code targetDir} for the
     *                      strictest containment check.
     * @return the number of files (not directories) successfully written
     * @throws IOException on IO failure or malformed ZIP entries
     */
    public static int extract(InputStream zipStream, Path targetDir, Path zipSlipBase) throws IOException {
        Files.createDirectories(targetDir);
        int fileCount = 0;
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                // Zip-slip protection — reject any entry whose normalised
                // path leaves the configured containment base.
                if (!entryPath.startsWith(zipSlipBase)) {
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    fileCount++;
                }
                zis.closeEntry();
            }
        }
        return fileCount;
    }
}
