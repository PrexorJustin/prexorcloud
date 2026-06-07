package me.prexorjustin.prexorcloud.security.token;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import me.prexorjustin.prexorcloud.common.util.FilePermissions;
import me.prexorjustin.prexorcloud.common.util.HashUtil;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-backed join token store. Tokens are SHA-256 hashed at rest. Stored in a
 * JSON file (e.g. config/security/join-tokens.json).
 */
public final class FileJoinTokenStore implements JoinTokenStore {

    private static final Logger logger = LoggerFactory.getLogger(FileJoinTokenStore.class);
    private static final String TOKEN_PREFIX = "pxr_";
    private static final int TOKEN_RANDOM_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path filePath;
    private final ObjectMapper mapper;
    private final List<JoinToken> tokens;

    public FileJoinTokenStore(Path filePath) {
        this.filePath = filePath;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.tokens = new CopyOnWriteArrayList<>(loadFromDisk());
    }

    @Override
    public synchronized JoinTokenResult create(String nodeId, int ttlSeconds) {
        String tokenId = "tok_" + UUID.randomUUID();
        String plaintext = generatePlaintext();
        String hash = HashUtil.sha256(plaintext.getBytes());
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);

        JoinToken token = new JoinToken(tokenId, nodeId, hash, plaintext, expiresAt);
        tokens.add(token);
        saveToDisk();

        logger.info("Created join token {} for node {} (expires {})", tokenId, nodeId, expiresAt);
        return new JoinTokenResult(token, plaintext);
    }

    @Override
    public synchronized Optional<JoinToken> validate(String plaintextToken) {
        String hash = HashUtil.sha256(plaintextToken.getBytes());
        return tokens.stream()
                .filter(t -> t.tokenHash().equals(hash))
                .filter(t -> !t.isExpired())
                .findFirst();
    }

    @Override
    public synchronized void consume(String tokenId) {
        tokens.removeIf(t -> t.tokenId().equals(tokenId));
        saveToDisk();
        logger.debug("Consumed join token {}", tokenId);
    }

    @Override
    public List<JoinToken> list() {
        return List.copyOf(tokens);
    }

    @Override
    public synchronized void cleanup() {
        int before = tokens.size();
        tokens.removeIf(JoinToken::isExpired);
        int removed = before - tokens.size();
        if (removed > 0) {
            saveToDisk();
            logger.debug("Cleaned up {} expired join tokens", removed);
        }
    }

    private List<JoinToken> loadFromDisk() {
        if (!Files.exists(filePath)) return new ArrayList<>();
        try {
            return mapper.readValue(filePath.toFile(), new TypeReference<List<JoinToken>>() {});
        } catch (IOException e) {
            logger.warn("Failed to load join tokens from {}: {}", filePath, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void saveToDisk() {
        try {
            Files.createDirectories(filePath.getParent());
            // Write to temp file, then atomic rename for crash safety
            Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            mapper.writeValue(tempFile.toFile(), tokens);
            Files.move(
                    tempFile,
                    filePath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            FilePermissions.setOwnerReadWrite(filePath);
        } catch (IOException e) {
            logger.error("Failed to save join tokens to {}: {}", filePath, e.getMessage());
        }
    }

    private static String generatePlaintext() {
        StringBuilder sb = new StringBuilder(TOKEN_PREFIX);
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < TOKEN_RANDOM_LENGTH; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
