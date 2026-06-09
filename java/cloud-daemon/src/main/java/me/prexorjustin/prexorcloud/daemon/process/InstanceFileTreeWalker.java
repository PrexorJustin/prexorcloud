package me.prexorjustin.prexorcloud.daemon.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.protocol.FileEntry;
import me.prexorjustin.prexorcloud.protocol.InstanceFileTree;
import me.prexorjustin.prexorcloud.protocol.WalkInstanceFiles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks an instance working directory to produce a structure-only filetree
 * (path / size / isDir / mtime — no file contents). Directory children that
 * exceed {@code summarize_threshold} are collapsed into a single
 * {@code summary=true} entry holding the recursive size and direct child
 * count, so a Minecraft {@code world/region/} folder (30k+ leaves) doesn't
 * inflate the paste body.
 *
 * <p>Symlinks are recorded as leaves and never followed. Unreadable entries
 * are skipped (best-effort); an unreadable root produces a typed error tag.</p>
 */
public final class InstanceFileTreeWalker {

    private static final Logger logger = LoggerFactory.getLogger(InstanceFileTreeWalker.class);

    /** Default caps when the request omits them (request fields zero == "use default"). */
    public static final int DEFAULT_MAX_ENTRIES = 5000;

    public static final int DEFAULT_MAX_DEPTH = 24;
    public static final int DEFAULT_SUMMARIZE_THRESHOLD = 500;

    private final Path instancesDir;

    public InstanceFileTreeWalker(Path instancesDir) {
        this.instancesDir = instancesDir.toAbsolutePath().normalize();
    }

    public InstanceFileTree walk(WalkInstanceFiles request) {
        InstanceFileTree.Builder reply = InstanceFileTree.newBuilder().setRequestId(request.getRequestId());

        try {
            InputValidator.requireSafeName(request.getGroup(), "group");
            InputValidator.requireSafeName(request.getInstanceId(), "instance_id");
        } catch (IllegalArgumentException e) {
            return reply.setError("INVALID_REQUEST").build();
        }

        Path target = instancesDir
                .resolve(request.getGroup())
                .resolve(request.getInstanceId())
                .toAbsolutePath()
                .normalize();
        if (!target.startsWith(instancesDir)) {
            return reply.setError("INVALID_REQUEST").build();
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return reply.setError("INSTANCE_NOT_FOUND").build();
        }

        int maxEntries = pick(request.getMaxEntries(), DEFAULT_MAX_ENTRIES);
        int maxDepth = pick(request.getMaxDepth(), DEFAULT_MAX_DEPTH);
        int summarizeThreshold = pick(request.getSummarizeThreshold(), DEFAULT_SUMMARIZE_THRESHOLD);

        var collected = new ArrayList<FileEntry>();
        boolean[] truncated = {false};
        try {
            walkDir(target, target, 0, maxDepth, maxEntries, summarizeThreshold, collected, truncated);
        } catch (IOException e) {
            logger.warn("InstanceFileTreeWalker: root unreadable for {}: {}", target, e.getMessage());
            return reply.setError("DIR_UNREADABLE").build();
        }
        reply.addAllEntries(collected);
        reply.setTruncated(truncated[0]);
        return reply.build();
    }

    private void walkDir(
            Path root,
            Path dir,
            int depth,
            int maxDepth,
            int maxEntries,
            int summarizeThreshold,
            List<FileEntry> out,
            boolean[] truncated)
            throws IOException {
        if (out.size() >= maxEntries) {
            truncated[0] = true;
            return;
        }
        List<Path> children;
        try (Stream<Path> stream = Files.list(dir)) {
            children = stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            if (dir.equals(root)) throw e;
            logger.debug("Skipping unreadable directory {}: {}", dir, e.getMessage());
            return;
        }

        if (children.size() > summarizeThreshold) {
            out.add(summarizeDirectory(root, dir, children.size()));
            return;
        }

        for (Path child : children) {
            if (out.size() >= maxEntries) {
                truncated[0] = true;
                return;
            }
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                logger.debug("Skipping unreadable entry {}: {}", child, e.getMessage());
                continue;
            }
            boolean isDir = attrs.isDirectory() && !attrs.isSymbolicLink();
            out.add(FileEntry.newBuilder()
                    .setPath(relativize(root, child))
                    .setSizeBytes(attrs.isRegularFile() ? attrs.size() : 0L)
                    .setIsDir(isDir)
                    .setModifiedAtMs(attrs.lastModifiedTime().toMillis())
                    .build());
            if (isDir) {
                if (depth + 1 <= maxDepth) {
                    walkDir(root, child, depth + 1, maxDepth, maxEntries, summarizeThreshold, out, truncated);
                } else {
                    truncated[0] = true;
                }
            }
        }
    }

    private static FileEntry summarizeDirectory(Path root, Path dir, int directChildCount) {
        long totalSize = recursiveSize(dir);
        long mtimeMs = 0L;
        try {
            mtimeMs = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .lastModifiedTime()
                    .toMillis();
        } catch (IOException ignored) {
            // mtime is best-effort
        }
        return FileEntry.newBuilder()
                .setPath(relativize(root, dir))
                .setSizeBytes(totalSize)
                .setIsDir(true)
                .setModifiedAtMs(mtimeMs)
                .setSummary(true)
                .setChildCount(directChildCount)
                .build();
    }

    private static long recursiveSize(Path dir) {
        long total = 0L;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path entry : (Iterable<Path>) walk::iterator) {
                if (entry.equals(dir)) continue;
                try {
                    BasicFileAttributes attrs =
                            Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isRegularFile()) total += attrs.size();
                } catch (IOException ignored) {
                    // skip unreadable entry; best-effort total
                }
            }
        } catch (IOException e) {
            logger.debug("Failed to summarize {}: {}", dir, e.getMessage());
        }
        return total;
    }

    private static String relativize(Path root, Path entry) {
        Path rel = root.relativize(entry);
        String s = rel.toString().replace('\\', '/');
        return s.isEmpty() ? "." : s;
    }

    private static int pick(int requested, int fallback) {
        return requested > 0 ? requested : fallback;
    }
}
