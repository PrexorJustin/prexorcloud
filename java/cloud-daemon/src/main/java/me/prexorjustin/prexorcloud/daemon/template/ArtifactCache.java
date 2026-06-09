package me.prexorjustin.prexorcloud.daemon.template;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import me.prexorjustin.prexorcloud.common.util.HashUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic disk-backed cache for controller-resolved workload artifacts.
 */
public final class ArtifactCache {

    private static final Logger logger = LoggerFactory.getLogger(ArtifactCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path cacheDir;
    private final ConcurrentHashMap<String, Semaphore> locks = new ConcurrentHashMap<>();

    public ArtifactCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public Path resolve(String namespace, String key, String fileName, String downloadUrl, String expectedHash)
            throws Exception {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IllegalArgumentException("artifact download URL is required");
        }
        if (expectedHash == null || expectedHash.isBlank()) {
            throw new IllegalArgumentException("artifact sha256 is required");
        }

        String safeNamespace = sanitizeSegment(namespace, "namespace");
        String safeKey = sanitizeSegment(key, "key");
        Path artifactDir = cacheDir.resolve(safeNamespace).resolve(safeKey);
        Path cachedArtifact = artifactDir.resolve(fileName);
        Path metaFile = artifactDir.resolve(fileName + ".meta");
        String lockKey = safeNamespace + "/" + safeKey + "/" + fileName;

        Files.createDirectories(artifactDir);

        if (Files.exists(cachedArtifact) && Files.exists(metaFile) && isCacheHit(metaFile, downloadUrl, expectedHash)) {
            return cachedArtifact;
        }

        Semaphore lock = locks.computeIfAbsent(lockKey, k -> new Semaphore(1));
        lock.acquireUninterruptibly();
        try {
            if (Files.exists(cachedArtifact)
                    && Files.exists(metaFile)
                    && isCacheHit(metaFile, downloadUrl, expectedHash)) {
                return cachedArtifact;
            }

            Path tempArtifact = artifactDir.resolve(fileName + ".tmp");
            logger.info("Downloading cached artifact {} from {}", lockKey, downloadUrl);
            try (var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build()) {
                var req = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .timeout(Duration.ofMinutes(10))
                        .GET()
                        .build();
                client.send(req, HttpResponse.BodyHandlers.ofFile(tempArtifact));
            }

            String actualHash = HashUtil.sha256(tempArtifact);
            if (!expectedHash.equalsIgnoreCase(actualHash)) {
                Files.deleteIfExists(tempArtifact);
                throw new SecurityException("artifact hash mismatch for " + fileName + ": expected " + expectedHash
                        + " but got " + actualHash);
            }

            Files.move(
                    tempArtifact, cachedArtifact, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            JSON.writeValue(
                    metaFile.toFile(),
                    new ArtifactMeta(downloadUrl, actualHash, Instant.now().toString()));
            return cachedArtifact;
        } finally {
            lock.release();
        }
    }

    private static boolean isCacheHit(Path metaFile, String downloadUrl, String expectedHash) {
        try {
            ArtifactMeta meta = JSON.readValue(metaFile.toFile(), ArtifactMeta.class);
            return downloadUrl.equals(meta.url()) && expectedHash.equalsIgnoreCase(meta.sha256());
        } catch (IOException _) {
            return false;
        }
    }

    private static String sanitizeSegment(String value, String field) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException("invalid artifact cache " + field + ": " + value);
        }
        return value;
    }

    private record ArtifactMeta(String url, String sha256, String cachedAt) {}
}
