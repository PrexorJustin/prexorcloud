package me.prexorjustin.prexorcloud.common.io;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Utility helpers for {@link java.nio.file.Files#walkFileTree} use cases that
 * we end up writing as one-off anonymous {@link SimpleFileVisitor} subclasses.
 */
public final class FileTrees {

    private FileTrees() {}

    /**
     * Recursively delete {@code root} (file or directory). No-op if it does not
     * exist. Files that fail to delete propagate the original {@link IOException}.
     */
    public static void deleteRecursively(Path root) throws IOException {
        deleteRecursivelyInternal(root, false);
    }

    /**
     * Like {@link #deleteRecursively(Path)} but uses {@link Files#deleteIfExists}
     * so files disappearing mid-walk (e.g. concurrent test cleanup) are tolerated.
     */
    public static void deleteRecursivelyTolerant(Path root) throws IOException {
        deleteRecursivelyInternal(root, true);
    }

    private static void deleteRecursivelyInternal(Path root, boolean tolerant) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (tolerant) {
                    Files.deleteIfExists(file);
                } else {
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null && !tolerant) {
                    throw exc;
                }
                if (tolerant) {
                    Files.deleteIfExists(dir);
                } else {
                    Files.delete(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
