package me.prexorjustin.prexorcloud.controller.module;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import me.prexorjustin.prexorcloud.api.module.frontend.FrontendManifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts, caches, and serves frontend assets from module JARs.
 * <p>
 * When a module JAR contains {@code META-INF/frontend/module-frontend.json},
 * this manager extracts all frontend files to
 * {@code modules/data/<name>/_frontend/} and makes them available for serving
 * via the REST API.
 */
public final class ModuleFrontendManager {

    private static final Logger logger = LoggerFactory.getLogger(ModuleFrontendManager.class);
    private static final String MANIFEST_PATH = "META-INF/frontend/module-frontend.json";
    private static final String FRONTEND_PREFIX = "META-INF/frontend/";
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(".js", ".mjs", ".css", ".json", ".svg", ".png", ".jpg", ".woff", ".woff2", ".map");

    private final ObjectMapper objectMapper;
    private final Path dataDirectory;
    private final Map<String, LoadedFrontend> frontends = new ConcurrentHashMap<>();

    public record LoadedFrontend(
            String moduleName, FrontendManifest manifest, String contentHash, Path assetDirectory) {}

    public ModuleFrontendManager(ObjectMapper objectMapper, Path dataDirectory) {
        this.objectMapper = objectMapper;
        this.dataDirectory = dataDirectory;
    }

    /**
     * Attempts to extract a frontend bundle from the given module JAR.
     *
     * @return {@code true} if a frontend was found and extracted
     */
    public boolean extractFrontend(String moduleName, Path jarPath) {
        try (var jar = new JarFile(jarPath.toFile())) {
            JarEntry manifestEntry = jar.getJarEntry(MANIFEST_PATH);
            if (manifestEntry == null) return false;

            FrontendManifest manifest;
            try (InputStream is = jar.getInputStream(manifestEntry)) {
                manifest = objectMapper.readValue(is, FrontendManifest.class);
            }

            Path targetDir = dataDirectory.resolve(moduleName).resolve("_frontend");
            if (Files.exists(targetDir)) {
                deleteRecursive(targetDir);
            }
            Files.createDirectories(targetDir);

            // Extract all frontend files
            Enumeration<JarEntry> entries = entries(jar);
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.startsWith(FRONTEND_PREFIX)) continue;

                String relativePath = name.substring(FRONTEND_PREFIX.length());
                if (relativePath.isEmpty()) continue;

                // Security: reject path traversal
                if (relativePath.contains("..") || relativePath.startsWith("/")) {
                    logger.warn("Skipping suspicious path in module {}: {}", moduleName, relativePath);
                    continue;
                }

                // Only allow known file types
                String ext = relativePath.contains(".") ? relativePath.substring(relativePath.lastIndexOf('.')) : "";
                if (!ALLOWED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
                    logger.warn("Skipping disallowed file type in module {}: {}", moduleName, relativePath);
                    continue;
                }

                Path targetFile = targetDir.resolve(relativePath);
                Files.createDirectories(targetFile.getParent());
                try (InputStream is = jar.getInputStream(entry)) {
                    Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            // Compute content hash of the entry file
            Path entryFile = targetDir.resolve(manifest.entry());
            String contentHash = computeHash(entryFile);

            LoadedFrontend loaded = new LoadedFrontend(moduleName, manifest, contentHash, targetDir);
            frontends.put(moduleName, loaded);

            logger.debug(
                    "Extracted frontend for module '{}': {} routes, hash={}",
                    moduleName,
                    manifest.routes().size(),
                    contentHash.substring(0, 8));
            return true;

        } catch (Exception e) {
            logger.error("Failed to extract frontend from module '{}': {}", moduleName, e.getMessage());
            return false;
        }
    }

    /**
     * Removes the frontend for the given module.
     */
    public void removeFrontend(String moduleName) {
        LoadedFrontend removed = frontends.remove(moduleName);
        if (removed != null) {
            try {
                deleteRecursive(removed.assetDirectory());
                logger.debug("Removed frontend for module '{}'", moduleName);
            } catch (IOException e) {
                logger.warn("Failed to clean up frontend files for module '{}': {}", moduleName, e.getMessage());
            }
        }
    }

    public Optional<LoadedFrontend> getFrontend(String moduleName) {
        return Optional.ofNullable(frontends.get(moduleName));
    }

    public Collection<LoadedFrontend> allFrontends() {
        return Collections.unmodifiableCollection(frontends.values());
    }

    /**
     * Resolves a frontend asset file, validating it exists and is within the asset
     * directory.
     */
    public Optional<Path> resolveAsset(String moduleName, String filepath) {
        LoadedFrontend frontend = frontends.get(moduleName);
        if (frontend == null) return Optional.empty();

        // Security: reject path traversal
        if (filepath.contains("..") || filepath.startsWith("/")) return Optional.empty();

        Path resolved = frontend.assetDirectory().resolve(filepath).normalize();

        // Ensure the resolved path is still within the asset directory
        if (!resolved.startsWith(frontend.assetDirectory())) return Optional.empty();

        if (Files.exists(resolved) && Files.isRegularFile(resolved)) {
            return Optional.of(resolved);
        }
        return Optional.empty();
    }

    private static String computeHash(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file);
        byte[] hash = digest.digest(bytes);
        return HexFormat.of().formatHex(hash).substring(0, 16);
    }

    @SuppressWarnings("unchecked")
    private static Enumeration<JarEntry> entries(JarFile jar) {
        return jar.entries();
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException _) {
                }
            });
        }
    }
}
