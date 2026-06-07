package me.prexorjustin.prexorcloud.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Utility for computing SHA-256 hashes.
 *
 * <p>
 * All methods are platform-agnostic and safe for use in cross-platform cloud
 * deployments.
 * </p>
 */
public final class HashUtil {

    private static final HexFormat HEX = HexFormat.of();
    private static final int BUFFER_SIZE = 16_384;

    private HashUtil() {}

    /**
     * Computes the SHA-256 hash of the given byte array.
     *
     * @param data
     *            the input bytes
     * @return the lowercase hex-encoded SHA-256 digest
     */
    public static String sha256(byte[] data) {
        return HEX.formatHex(newSha256().digest(data));
    }

    /**
     * Computes the SHA-256 hash of the file at the given path, streaming in chunks
     * to support arbitrarily large files.
     *
     * @param path
     *            the file to hash
     * @return the lowercase hex-encoded SHA-256 digest
     * @throws IOException
     *             if the file cannot be read
     */
    public static String sha256(Path path) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available in every JVM", e);
        }
    }
}
