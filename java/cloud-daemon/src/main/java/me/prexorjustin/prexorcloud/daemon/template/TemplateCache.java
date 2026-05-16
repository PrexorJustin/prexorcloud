package me.prexorjustin.prexorcloud.daemon.template;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import me.prexorjustin.prexorcloud.common.cache.CacheEntries.TemplateCacheInfo;
import me.prexorjustin.prexorcloud.common.io.FileTrees;
import me.prexorjustin.prexorcloud.daemon.grpc.DaemonGrpcClient;
import me.prexorjustin.prexorcloud.protocol.DaemonMessage;
import me.prexorjustin.prexorcloud.protocol.TemplateRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Disk-backed template cache. Persists cached templates to:
 *
 * <pre>
 *   cache/templates/{name}/
 *     .meta   -- JSON: { "hash": "...", "sizeBytes": ..., "lastUsed": "..." }
 *     files/  -- extracted template files
 * </pre>
 *
 * Uses CompletableFuture coalescing for concurrent requests to the same template.
 */
public final class TemplateCache {

    private static final Logger logger = LoggerFactory.getLogger(TemplateCache.class);
    private static final long REQUEST_TIMEOUT_SECONDS = 120;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path cacheDir;
    private final Map<String, CompletableFuture<TemplateResult>> inflight = new ConcurrentHashMap<>();

    public record TemplateResult(byte[] data, String hash, boolean upToDate) {}

    public TemplateCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Get template data, requesting from controller if not cached or hash is stale.
     * Multiple concurrent requests for the same template share a single gRPC request.
     *
     * @param templateName
     *            the template to fetch
     * @param expectedHash
     *            the hash expected by the controller (from TemplateRef)
     * @param client
     *            gRPC client to request from
     * @return tar.gz bytes, or null if not available
     */
    public byte[] getOrRequest(String templateName, String expectedHash, DaemonGrpcClient client) {
        // 1. Check disk cache (no lock)
        byte[] cached = checkDiskCache(templateName, expectedHash);
        if (cached != null) return cached;

        // 2. Coalesce via computeIfAbsent — first thread creates the future and sends gRPC request
        CompletableFuture<TemplateResult> future = inflight.computeIfAbsent(templateName, name -> {
            String knownHash = readKnownHash(name);
            client.sendMessage(DaemonMessage.newBuilder()
                    .setTemplateRequest(
                            TemplateRequest.newBuilder().setTemplateName(name).setKnownHash(knownHash))
                    .build());
            return new CompletableFuture<>();
        });

        try {
            // 3. All threads share one future per template
            TemplateResult result = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 4. Process result
            if (result.upToDate()) {
                logger.debug("Template {} is up to date", templateName);
                return readCachedData(cacheDir.resolve(templateName));
            }

            if (result.data() != null) {
                writeDiskCache(templateName, result.hash(), result.data());
                return result.data();
            }

            return readCachedData(cacheDir.resolve(templateName));

        } catch (TimeoutException _) {
            logger.warn("Template request timed out: {}", templateName);
            return readCachedData(cacheDir.resolve(templateName));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return readCachedData(cacheDir.resolve(templateName));
        } catch (Exception e) {
            logger.error("Template request failed for {}: {}", templateName, e.getMessage(), e);
            return readCachedData(cacheDir.resolve(templateName));
        } finally {
            // 5. Atomic CAS removal — only remove if it's still our future
            inflight.remove(templateName, future);
        }
    }

    /**
     * Legacy overload for backward compatibility (no expected hash).
     */
    public byte[] getOrRequest(String templateName, DaemonGrpcClient client) {
        return getOrRequest(templateName, "", client);
    }

    /**
     * Called when TemplateData is received from controller.
     */
    public void onTemplateData(String templateName, String hash, byte[] data) {
        CompletableFuture<TemplateResult> future = inflight.get(templateName);
        if (future != null) {
            future.complete(new TemplateResult(data, hash, false));
        } else {
            // Unsolicited template data — cache to disk
            try {
                Path templateDir = cacheDir.resolve(templateName);
                Files.createDirectories(templateDir);
                Files.write(templateDir.resolve("data.tar.gz"), data);
                var meta = new CacheMeta(hash, data.length, Instant.now().toString());
                JSON.writeValue(templateDir.resolve(".meta").toFile(), meta);
            } catch (IOException e) {
                logger.error("Failed to cache unsolicited template {}: {}", templateName, e.getMessage());
            }
        }
    }

    /**
     * Called when TemplateUpToDate is received from controller.
     */
    public void onTemplateUpToDate(String templateName) {
        CompletableFuture<TemplateResult> future = inflight.get(templateName);
        if (future != null) {
            future.complete(new TemplateResult(null, null, true));
        }
    }

    private byte[] checkDiskCache(String templateName, String expectedHash) {
        Path templateDir = cacheDir.resolve(templateName);
        Path metaFile = templateDir.resolve(".meta");

        if (Files.exists(metaFile)) {
            try {
                var meta = JSON.readValue(metaFile.toFile(), CacheMeta.class);
                if (meta.hash().equals(expectedHash)) {
                    updateLastUsed(metaFile, meta);
                    logger.debug("Template {} cache hit (hash={})", templateName, expectedHash);
                    Path tarFile = templateDir.resolve("data.tar.gz");
                    if (Files.exists(tarFile)) {
                        return Files.readAllBytes(tarFile);
                    }
                }
            } catch (IOException e) {
                logger.warn("Failed to read cache meta for {}: {}", templateName, e.getMessage());
            }
        }
        return null;
    }

    private String readKnownHash(String templateName) {
        Path metaFile = cacheDir.resolve(templateName).resolve(".meta");
        if (Files.exists(metaFile)) {
            try {
                var meta = JSON.readValue(metaFile.toFile(), CacheMeta.class);
                return meta.hash();
            } catch (IOException _) {
            }
        }
        return "";
    }

    private void writeDiskCache(String templateName, String hash, byte[] data) {
        Path templateDir = cacheDir.resolve(templateName);
        Path metaFile = templateDir.resolve(".meta");
        try {
            Files.createDirectories(templateDir);
            Path tarFile = templateDir.resolve("data.tar.gz");
            Files.write(tarFile, data);

            // Extract files
            Path filesDir = templateDir.resolve("files");
            FileTrees.deleteRecursively(filesDir);
            Files.createDirectories(filesDir);
            TemplateUnpacker.unpack(data, filesDir);

            // Write meta
            var meta = new CacheMeta(hash, data.length, Instant.now().toString());
            JSON.writeValue(metaFile.toFile(), meta);

            logger.debug("Template {} cached to disk (hash={})", templateName, hash);
        } catch (IOException e) {
            logger.error("Failed to write template cache for {}: {}", templateName, e.getMessage(), e);
        }
    }

    private byte[] readCachedData(Path templateDir) {
        Path tarFile = templateDir.resolve("data.tar.gz");
        if (Files.exists(tarFile)) {
            try {
                return Files.readAllBytes(tarFile);
            } catch (IOException e) {
                logger.warn("Failed to read cached template data: {}", e.getMessage());
            }
        }
        return null;
    }

    private void updateLastUsed(Path metaFile, CacheMeta meta) {
        try {
            var updated =
                    new CacheMeta(meta.hash(), meta.sizeBytes(), Instant.now().toString());
            JSON.writeValue(metaFile.toFile(), updated);
        } catch (IOException _) {
        }
    }

    /**
     * Lists all cached template entries by reading .meta files from each
     * subdirectory.
     */
    public List<TemplateCacheInfo> listEntries() {
        var entries = new ArrayList<TemplateCacheInfo>();
        if (!Files.isDirectory(cacheDir)) return entries;

        try (Stream<Path> dirs = Files.list(cacheDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path metaFile = dir.resolve(".meta");
                if (Files.exists(metaFile)) {
                    try {
                        var meta = JSON.readValue(metaFile.toFile(), CacheMeta.class);
                        entries.add(new TemplateCacheInfo(
                                dir.getFileName().toString(), meta.hash(), meta.sizeBytes(), meta.lastUsed()));
                    } catch (IOException _) {
                        logger.debug("Skipping corrupt cache meta: {}", metaFile);
                    }
                }
            });
        } catch (IOException e) {
            logger.warn("Failed to list template cache entries: {}", e.getMessage());
        }
        return entries;
    }

    private record CacheMeta(String hash, long sizeBytes, String lastUsed) {}
}
