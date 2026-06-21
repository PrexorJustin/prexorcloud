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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

import me.prexorjustin.prexorcloud.common.cache.CacheEntries.JarCacheInfo;
import me.prexorjustin.prexorcloud.common.util.HashUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Disk-backed jar cache. Prevents re-downloading the same server jar for every
 * new instance.
 *
 * <p>
 * Cache layout:
 *
 * <pre>
 *   cache/jars/&lt;platform&gt;/&lt;version&gt;/&lt;jarFile&gt;
 *   cache/jars/&lt;platform&gt;/&lt;version&gt;/&lt;jarFile&gt;.meta  -- JSON: { url, sha256, cachedAt }
 * </pre>
 *
 * <p>
 * Invalidation: if the download URL changes for the same (platform, version,
 * jarFile), re-downloads.
 */
public final class JarCache {

    private static final Logger logger = LoggerFactory.getLogger(JarCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path cacheDir;
    private final ConcurrentHashMap<String, Semaphore> locks = new ConcurrentHashMap<>();

    public JarCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Resolves a cached jar for the given (platform, version, jarFile) key.
     * Downloads from {@code downloadUrl} if not yet cached or if the URL changed.
     *
     * @return path to the ready-to-use cached jar
     */
    public Path resolve(String platform, String platformVersion, String jarFile, String downloadUrl) throws Exception {
        return resolve(platform, platformVersion, jarFile, downloadUrl, null);
    }

    public Path resolve(
            String platform, String platformVersion, String jarFile, String downloadUrl, String expectedHash)
            throws Exception {
        String key = platform + "/" + platformVersion + "/" + jarFile;
        Path jarDir = cacheDir.resolve(platform).resolve(platformVersion);
        Path cachedJar = jarDir.resolve(jarFile);
        Path metaFile = jarDir.resolve(jarFile + ".meta");

        Files.createDirectories(jarDir);

        // Fast path (no lock): cache hit with matching URL
        if (Files.exists(cachedJar) && Files.exists(metaFile)) {
            try {
                JarMeta meta = JSON.readValue(metaFile.toFile(), JarMeta.class);
                if (downloadUrl.equals(meta.url())) {
                    logger.debug("Jar cache hit: {}", key);
                    return cachedJar;
                }
            } catch (IOException _) {
            }
        }

        // Serialize downloads per cache key so concurrent callers don't race on the
        // .tmp file
        Semaphore lock = locks.computeIfAbsent(key, k -> new Semaphore(1));
        lock.acquireUninterruptibly();
        try {
            // Re-check after acquiring lock — another thread may have completed the
            // download
            if (Files.exists(cachedJar) && Files.exists(metaFile)) {
                try {
                    JarMeta meta = JSON.readValue(metaFile.toFile(), JarMeta.class);
                    if (downloadUrl.equals(meta.url())) {
                        logger.debug("Jar cache hit (after lock): {}", key);
                        return cachedJar;
                    }
                    logger.debug("Jar cache stale (URL changed) for {} -- re-downloading", key);
                } catch (IOException e) {
                    logger.warn("Corrupt jar cache meta for {}: {}", key, e.getMessage());
                }
            }

            // Download to a temp file and atomically move into place so that
            // running instances hardlinked to the old file are not disturbed.
            Path tempJar = jarDir.resolve(jarFile + ".tmp");
            logger.info("Downloading {} for {}/{} from {}", jarFile, platform, platformVersion, downloadUrl);
            try (var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    // Java's HttpClient defaults to Redirect.NEVER: without this a 30x redirect
                    // (e.g. geysermc's .../versions/latest/builds/latest/... alias) writes the empty
                    // redirect body as a 0-byte jar -> "Invalid or corrupt jarfile" crash-loop.
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()) {
                var req = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .timeout(Duration.ofMinutes(10))
                        .GET()
                        .build();
                var response = client.send(req, HttpResponse.BodyHandlers.ofFile(tempJar));
                if (response.statusCode() / 100 != 2) {
                    Files.deleteIfExists(tempJar);
                    throw new IOException("Download of " + downloadUrl + " returned HTTP " + response.statusCode());
                }
            }

            String sha256 = sha256(tempJar);
            if (expectedHash != null && !expectedHash.isBlank()) {
                if (!expectedHash.equalsIgnoreCase(sha256)) {
                    Files.deleteIfExists(tempJar);
                    throw new SecurityException(
                            "JAR hash mismatch for " + jarFile + ": expected " + expectedHash + " but got " + sha256);
                }
            } else {
                // No pinned hash: integrity rests solely on the download URL's TLS, so a poisoned
                // cache/mirror would be executed with the node's identity. Surfaced loudly until the
                // controller pins a hash for every runtime/platform version (then this becomes
                // fail-closed). See audit F-D6.
                logger.warn(
                        "SECURITY: cached {} for {}/{} from {} WITHOUT an integrity hash (sha256={}); "
                                + "download integrity is unverified",
                        jarFile,
                        platform,
                        platformVersion,
                        downloadUrl,
                        sha256);
            }
            Files.move(tempJar, cachedJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            JSON.writeValue(
                    metaFile.toFile(),
                    new JarMeta(downloadUrl, sha256, Instant.now().toString()));
            logger.debug("Cached {} ({} bytes, sha256={})", jarFile, Files.size(cachedJar), sha256);

            return cachedJar;
        } finally {
            lock.release();
        }
    }

    private static String sha256(Path file) throws Exception {
        return HashUtil.sha256(file);
    }

    /**
     * Lists all cached JAR entries by walking the cache directory structure.
     */
    public List<JarCacheInfo> listEntries() {
        var entries = new ArrayList<JarCacheInfo>();
        if (!Files.isDirectory(cacheDir)) return entries;

        try (Stream<Path> platforms = Files.list(cacheDir)) {
            platforms.filter(Files::isDirectory).forEach(platformDir -> {
                String platform = platformDir.getFileName().toString();
                try (Stream<Path> versions = Files.list(platformDir)) {
                    versions.filter(Files::isDirectory).forEach(versionDir -> {
                        String version = versionDir.getFileName().toString();
                        try (Stream<Path> files = Files.list(versionDir)) {
                            files.filter(f -> f.toString().endsWith(".meta")).forEach(metaFile -> {
                                try {
                                    var meta = JSON.readValue(metaFile.toFile(), JarMeta.class);
                                    String jarFileName =
                                            metaFile.getFileName().toString().replaceAll("\\.meta$", "");
                                    Path jarFile = versionDir.resolve(jarFileName);
                                    long size = Files.exists(jarFile) ? Files.size(jarFile) : 0;
                                    entries.add(new JarCacheInfo(
                                            platform, version, jarFileName, size, meta.sha256(), meta.cachedAt()));
                                } catch (IOException _) {
                                    logger.debug("Skipping corrupt jar meta: {}", metaFile);
                                }
                            });
                        } catch (IOException _) {
                            logger.debug("Failed to list jar version dir: {}", versionDir);
                        }
                    });
                } catch (IOException _) {
                    logger.debug("Failed to list jar platform dir: {}", platformDir);
                }
            });
        } catch (IOException e) {
            logger.warn("Failed to list jar cache entries: {}", e.getMessage());
        }
        return entries;
    }

    private record JarMeta(String url, String sha256, String cachedAt) {}
}
