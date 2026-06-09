package me.prexorjustin.prexorcloud.common.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sets POSIX file permissions. No-op on Windows.
 */
public final class FilePermissions {

    private static final Logger logger = LoggerFactory.getLogger(FilePermissions.class);
    private static final boolean IS_POSIX = FileSystems_isPosix();

    private FilePermissions() {}

    /**
     * Set file to owner-read-write only (600).
     */
    public static void setOwnerReadWrite(Path path) {
        setPosix(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    /**
     * Set directory to owner-read-write-execute (700).
     */
    public static void setOwnerOnly(Path path) {
        setPosix(
                path,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
    }

    private static void setPosix(Path path, Set<PosixFilePermission> perms) {
        if (!IS_POSIX) return;
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (IOException | UnsupportedOperationException e) {
            logger.warn("Failed to set permissions on {}: {}", path, e.getMessage());
        }
    }

    private static boolean FileSystems_isPosix() {
        try {
            return java.nio.file.FileSystems.getDefault()
                    .supportedFileAttributeViews()
                    .contains("posix");
        } catch (Exception ignored) {
            return false;
        }
    }
}
