package me.prexorjustin.prexorcloud.daemon.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import me.prexorjustin.prexorcloud.common.util.InputValidator;
import me.prexorjustin.prexorcloud.protocol.InstanceFileContent;
import me.prexorjustin.prexorcloud.protocol.ReadInstanceFile;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded reader for a single file under an instance working directory.
 * Sibling to {@link InstanceFileTreeWalker}: where the walker enumerates
 * structure, this reader returns up to {@code max_bytes} of one file's
 * contents (either the head or the tail). Path-traversal is rejected with
 * the same {@code normalize().startsWith(instancesDir.normalize())} guard
 * the walker uses; symlinks are never followed.
 *
 * <p>
 * Reads run on the daemon's virtual-thread executor — keep them simple, never
 * load the entire file when the caller asked for a slice.
 * </p>
 */
public final class InstanceFileReader {

    private static final Logger logger = LoggerFactory.getLogger(InstanceFileReader.class);

    /** Default cap when the request omits {@code max_bytes}. */
    public static final int DEFAULT_MAX_BYTES = 64 * 1024;

    /** Absolute ceiling — even if the caller asks for more, we won't return more than this. */
    public static final int MAX_BYTES_CEILING = 1 * 1024 * 1024;

    private final Path instancesDir;

    public InstanceFileReader(Path instancesDir) {
        this.instancesDir = instancesDir.toAbsolutePath().normalize();
    }

    public InstanceFileContent read(ReadInstanceFile request) {
        InstanceFileContent.Builder reply = InstanceFileContent.newBuilder().setRequestId(request.getRequestId());

        try {
            InputValidator.requireSafeName(request.getGroup(), "group");
            InputValidator.requireSafeName(request.getInstanceId(), "instance_id");
        } catch (IllegalArgumentException e) {
            return reply.setError("INVALID_REQUEST").build();
        }

        Path instanceRoot = instancesDir
                .resolve(request.getGroup())
                .resolve(request.getInstanceId())
                .toAbsolutePath()
                .normalize();
        if (!instanceRoot.startsWith(instancesDir)) {
            return reply.setError("INVALID_REQUEST").build();
        }
        if (!Files.exists(instanceRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(instanceRoot, LinkOption.NOFOLLOW_LINKS)) {
            return reply.setError("INSTANCE_NOT_FOUND").build();
        }

        String rel = request.getPath() == null ? "" : request.getPath();
        if (rel.isBlank() || rel.contains("\0")) {
            return reply.setError("INVALID_REQUEST").build();
        }
        Path target =
                instanceRoot.resolve(rel.replace('\\', '/')).toAbsolutePath().normalize();
        if (!target.startsWith(instanceRoot)) {
            return reply.setError("PATH_OUTSIDE_INSTANCE").build();
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return reply.setError("FILE_NOT_FOUND").build();
        }
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return reply.setError("NOT_REGULAR_FILE").build();
        }

        long totalSize;
        try {
            totalSize = Files.size(target);
        } catch (IOException e) {
            return reply.setError("FILE_UNREADABLE").build();
        }

        int maxBytes = pick(request.getMaxBytes(), DEFAULT_MAX_BYTES);
        if (maxBytes > MAX_BYTES_CEILING) maxBytes = MAX_BYTES_CEILING;

        try {
            byte[] payload;
            boolean truncated;
            if (request.getTail() && totalSize > maxBytes) {
                payload = readTail(target, maxBytes);
                truncated = true;
            } else {
                payload = readHead(target, maxBytes);
                truncated = totalSize > payload.length;
            }
            return reply.setContent(ByteString.copyFrom(payload))
                    .setTotalSizeBytes(totalSize)
                    .setTruncated(truncated)
                    .build();
        } catch (IOException e) {
            logger.debug("read failed for {}: {}", target, e.getMessage());
            return reply.setError("FILE_UNREADABLE").build();
        }
    }

    private static byte[] readHead(Path file, int maxBytes) throws IOException {
        try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
            return in.readNBytes(maxBytes);
        }
    }

    private static byte[] readTail(Path file, int maxBytes) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long pos = Math.max(0, ch.size() - maxBytes);
            ch.position(pos);
            int len = (int) Math.min(maxBytes, ch.size() - pos);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(len);
            int total = 0;
            while (total < len) {
                int n = ch.read(buf);
                if (n < 0) break;
                total += n;
            }
            byte[] out = new byte[total];
            buf.flip();
            buf.get(out);
            return out;
        }
    }

    private static int pick(int requested, int fallback) {
        return requested > 0 ? requested : fallback;
    }
}
