package me.prexorjustin.prexorcloud.common.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("HashUtil")
class HashUtilTest {

    // Known SHA-256 of empty input
    private static final String SHA256_EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    // Known SHA-256 of "hello"
    private static final String SHA256_HELLO = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Nested
    @DisplayName("sha256(byte[])")
    class ByteArrayHash {

        @Test
        @DisplayName("Produces correct hash for empty input")
        void hashesEmptyInput() {
            assertEquals(SHA256_EMPTY, HashUtil.sha256(new byte[0]));
        }

        @Test
        @DisplayName("Produces correct hash for known input")
        void hashesKnownInput() {
            assertEquals(SHA256_HELLO, HashUtil.sha256("hello".getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("Same input produces same hash")
        void deterministicHash() {
            byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
            assertEquals(HashUtil.sha256(data), HashUtil.sha256(data));
        }

        @Test
        @DisplayName("Different input produces different hash")
        void differentInputDifferentHash() {
            assertNotEquals(
                    HashUtil.sha256("input1".getBytes(StandardCharsets.UTF_8)),
                    HashUtil.sha256("input2".getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("Returns lowercase hex string of 64 characters")
        void correctFormat() {
            String hash = HashUtil.sha256("data".getBytes(StandardCharsets.UTF_8));
            assertEquals(64, hash.length());
            assertTrue(hash.matches("^[0-9a-f]{64}$"));
        }
    }

    @Nested
    @DisplayName("sha256(Path)")
    class FileHash {

        @Test
        @DisplayName("Hashes file content correctly")
        void hashesFileContent(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("test.txt");
            Files.writeString(file, "hello");
            assertEquals(SHA256_HELLO, HashUtil.sha256(file));
        }

        @Test
        @DisplayName("Hashes empty file correctly")
        void hashesEmptyFile(@TempDir Path tempDir) throws IOException {
            Path file = tempDir.resolve("empty.txt");
            Files.writeString(file, "");
            assertEquals(SHA256_EMPTY, HashUtil.sha256(file));
        }

        @Test
        @DisplayName("File hash matches byte array hash")
        void fileHashMatchesByteHash(@TempDir Path tempDir) throws IOException {
            String content = "some test content for hashing";
            Path file = tempDir.resolve("content.txt");
            Files.writeString(file, content);

            assertEquals(HashUtil.sha256(content.getBytes(StandardCharsets.UTF_8)), HashUtil.sha256(file));
        }

        @Test
        @DisplayName("Throws IOException for non-existent file")
        void throwsForMissingFile(@TempDir Path tempDir) {
            Path missing = tempDir.resolve("missing.txt");
            assertThrows(IOException.class, () -> HashUtil.sha256(missing));
        }
    }
}
