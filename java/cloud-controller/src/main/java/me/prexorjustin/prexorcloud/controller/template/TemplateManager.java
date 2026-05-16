package me.prexorjustin.prexorcloud.controller.template;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardWatchEventKinds.*;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import me.prexorjustin.prexorcloud.api.event.events.TemplateUpdatedEvent;
import me.prexorjustin.prexorcloud.controller.event.EventBus;
import me.prexorjustin.prexorcloud.controller.state.StateStore;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CRUD for templates. Templates are pure file packages stored on disk:
 * <ul>
 * <li>{@code templates/<name>/files/} -- template files</li>
 * </ul>
 * Metadata is persisted to MongoDB via {@code StateStore}. No YAML config files.
 * <p>
 * A background {@link WatchService} monitors the {@code templates/} directory
 * tree for external filesystem changes (e.g. manual edits, rsync, FTP uploads)
 * and rehashes affected templates automatically.
 */
public final class TemplateManager {

    private static final Logger logger = LoggerFactory.getLogger(TemplateManager.class);

    private final Path filesDir;
    private final StateStore stateStore;
    private final EventBus eventBus;
    private final Map<String, TemplateConfig> templates = new ConcurrentHashMap<>();
    private final Set<String> suppressedTemplates = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> rehashLocks = new ConcurrentHashMap<>();
    private WatchService watchService;

    public TemplateManager(Path filesDir, StateStore stateStore, EventBus eventBus) {
        this.filesDir = filesDir;
        this.stateStore = stateStore;
        this.eventBus = eventBus;
    }

    /**
     * Load all templates from the database and scan files for hash consistency.
     * Also starts the background filesystem watcher.
     */
    public void loadAll() throws IOException {
        Files.createDirectories(filesDir);

        // Load from database
        for (var config : stateStore.getAllTemplates()) {
            templates.put(config.name(), config);
        }

        // Scan and hash all template directories
        scanAndHash();

        // Start watching for external filesystem changes
        startWatcher();

        logger.info("Loaded {} templates", templates.size());
    }

    /**
     * Scan all template directories, compute hashes, and detect changes.
     */
    public void scanAndHash() throws IOException {
        if (!Files.isDirectory(filesDir)) return;

        try (Stream<Path> stream = Files.list(filesDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                try {
                    Path files = dir.resolve("files");
                    if (!Files.isDirectory(files)) return;

                    String hash = computeHash(name);
                    long size = computeSize(name);
                    TemplateConfig existing = templates.get(name);

                    if (existing != null && !existing.hash().equals(hash)) {
                        // Hash changed -- record new version
                        var updated = new TemplateConfig(name, existing.description(), existing.platform(), hash, size);
                        templates.put(name, updated);
                        stateStore.runInTransaction(() -> {
                            stateStore.saveTemplate(updated);
                            stateStore.recordTemplateVersion(name, hash, size);
                        });
                        logger.debug("Template {} hash updated: {} -> {}", name, existing.hash(), hash);
                    } else if (existing == null) {
                        // New template found on disk but not in DB
                        var config = new TemplateConfig(name, "", "", hash, size);
                        templates.put(name, config);
                        stateStore.runInTransaction(() -> {
                            stateStore.saveTemplate(config);
                            stateStore.recordTemplateVersion(name, hash, size);
                        });
                        logger.debug("Discovered template on disk: {}", name);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to scan template {}: {}", name, e.getMessage());
                }
            });
        }
    }

    /**
     * Create or update a template (metadata only).
     */
    public void save(TemplateConfig config) throws IOException {
        validateName(config.name());
        Path templateFilesDir = filesDir.resolve(config.name()).resolve("files");
        Files.createDirectories(templateFilesDir);

        String hash = computeHash(config.name());
        long size = computeSize(config.name());
        var withHash = new TemplateConfig(config.name(), config.description(), config.platform(), hash, size);

        templates.put(config.name(), withHash);
        stateStore.runInTransaction(() -> {
            stateStore.saveTemplate(withHash);
            if (!hash.isEmpty()) {
                stateStore.recordTemplateVersion(config.name(), hash, size);
            }
        });

        if (!hash.isEmpty()) {
            createSnapshot(config.name(), hash);
        }

        logger.debug("Template saved: {}", config.name());
    }

    /**
     * Delete a template, including its files and snapshots from disk.
     */
    public void delete(String name) {
        templates.remove(name);
        stateStore.deleteTemplate(name);

        // Remove the template directory (files + snapshots) from disk
        Path templateDir = filesDir.resolve(name);
        if (Files.isDirectory(templateDir)) {
            try {
                Files.walkFileTree(templateDir, new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
                logger.info("Template deleted (including disk files): {}", name);
            } catch (IOException e) {
                logger.warn(
                        "Template {} removed from DB/memory but failed to delete disk files: {}", name, e.getMessage());
            }
        } else {
            logger.info("Template deleted: {}", name);
        }
    }

    /**
     * Recompute hash and record new version if changed. Called after file writes
     * (API) and by the filesystem watcher (external changes). Publishes
     * {@link TemplateUpdatedEvent} when the hash changes.
     */
    public void rehash(String name) throws IOException {
        Object lock = rehashLocks.computeIfAbsent(name, k -> new Object());
        synchronized (lock) {
            TemplateConfig existing = templates.get(name);
            if (existing == null) return;

            String newHash = computeHash(name);
            long newSize = computeSize(name);

            if (!newHash.equals(existing.hash())) {
                String oldHash = existing.hash();
                var updated = new TemplateConfig(name, existing.description(), existing.platform(), newHash, newSize);
                templates.put(name, updated);
                stateStore.runInTransaction(() -> {
                    stateStore.saveTemplate(updated);
                    stateStore.recordTemplateVersion(name, newHash, newSize);
                });
                createSnapshot(name, newHash);
                logger.debug("Template {} rehashed: {} -> {}", name, oldHash, newHash);
                eventBus.publish(new TemplateUpdatedEvent(name, oldHash, newHash));
            }
        }
    }

    /**
     * Compute SHA-256 hash over all files in templates/{name}/files/.
     */
    public String computeHash(String name) throws IOException {
        Path dir = getTemplateFilesDir(name);
        if (!Files.isDirectory(dir)) return "";

        // Retry up to 3 times to handle files disappearing mid-walk
        // (e.g. watcher fires for a deletion while we're still reading)
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                var paths = new ArrayList<Path>();
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        paths.add(file);
                        return FileVisitResult.CONTINUE;
                    }
                });

                // No files → no hash
                if (paths.isEmpty()) return "";

                // Sort for deterministic hash
                paths.sort(Comparator.comparing(p -> dir.relativize(p).toString()));

                for (Path file : paths) {
                    String relative = dir.relativize(file).toString().replace('\\', '/');
                    digest.update(relative.getBytes());
                    digest.update(Files.readAllBytes(file));
                }

                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchFileException _) {
                // File disappeared between walkFileTree and readAllBytes — retry
                logger.debug("File vanished during hash of template {}, retrying (attempt {})", name, attempt + 1);
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 not available", e);
            }
        }

        // All retries exhausted — fall back to current hash or empty
        logger.debug("Could not compute stable hash for template {} after retries", name);
        TemplateConfig existing = templates.get(name);
        return existing != null ? existing.hash() : "";
    }

    private long computeSize(String name) throws IOException {
        Path dir = getTemplateFilesDir(name);
        if (!Files.isDirectory(dir)) return 0;

        long[] total = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                total[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    public Optional<TemplateConfig> get(String name) {
        return Optional.ofNullable(templates.get(name));
    }

    public Collection<TemplateConfig> getAll() {
        return Collections.unmodifiableCollection(templates.values());
    }

    /**
     * Create a tar.gz snapshot of the current template files under
     * {@code templates/<name>/snapshots/<hash>.tar.gz}. No-ops if the snapshot file
     * already exists.
     *
     * Files are first copied to a temporary directory to avoid race conditions
     * where file sizes change between tar header creation and byte streaming (e.g.
     * plugin JARs being written concurrently).
     */
    public void createSnapshot(String name, String hash) throws IOException {
        if (hash == null || hash.isEmpty()) return;
        Path dir = getTemplateFilesDir(name);
        if (!Files.isDirectory(dir)) return;

        Path snapshotDir = filesDir.resolve(name).resolve("snapshots");
        Files.createDirectories(snapshotDir);
        Path snapshot = snapshotDir.resolve(hash + ".tar.gz");
        if (Files.exists(snapshot)) return; // already have this version

        // Copy files to a stable temp directory to avoid size-mismatch races
        Path tempDir = Files.createTempDirectory("snapshot-" + name + "-");
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(tempDir.resolve(dir.relativize(d)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, tempDir.resolve(dir.relativize(file)), REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });

            // Collect files from the stable copy
            var filePaths = new ArrayList<Path>();
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    filePaths.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            filePaths.sort(Comparator.comparing(p -> tempDir.relativize(p).toString()));

            // Create tar.gz from stable copy
            try (var fos = new BufferedOutputStream(Files.newOutputStream(snapshot));
                    var gzip = new GzipCompressorOutputStream(fos);
                    var tar = new TarArchiveOutputStream(gzip)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                for (Path file : filePaths) {
                    String entryName = tempDir.relativize(file).toString().replace('\\', '/');
                    var entry = new TarArchiveEntry(file.toFile(), entryName);
                    tar.putArchiveEntry(entry);
                    Files.copy(file, tar);
                    tar.closeArchiveEntry();
                }
                tar.finish();
            }
        } finally {
            // Cleanup temp directory
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        logger.debug("Snapshot created for template {} hash={}", name, hash.substring(0, 8));
    }

    /**
     * Delete a snapshot archive and its version record. Refuses to delete the hash
     * the template is currently on.
     *
     * @throws IllegalArgumentException
     *             if the hash is the current template hash, or no snapshot file
     *             exists for it
     */
    public void deleteSnapshot(String name, String hash) throws IOException {
        TemplateConfig current = templates.get(name);
        if (current != null && current.hash().equals(hash)) {
            throw new IllegalArgumentException("Cannot delete the current active version");
        }

        Path snapshotDir = filesDir.resolve(name).resolve("snapshots");
        Path snapshot = snapshotDir.resolve(hash + ".tar.gz");
        if (!Files.exists(snapshot)) {
            throw new IllegalArgumentException("No snapshot found for hash: " + hash);
        }

        Files.delete(snapshot);
        stateStore.deleteTemplateVersion(name, hash);
        logger.debug("Snapshot deleted for template {} hash={}", name, hash.substring(0, Math.min(8, hash.length())));
    }

    /**
     * Restore template files from a previously stored snapshot. Clears the current
     * {@code files/} directory, extracts the archive, then rehashes.
     *
     * @throws IllegalArgumentException
     *             if no snapshot exists for the given hash
     */
    public void restoreSnapshot(String name, String targetHash) throws IOException {
        Path snapshotDir = filesDir.resolve(name).resolve("snapshots");
        Path snapshot = snapshotDir.resolve(targetHash + ".tar.gz");
        if (!Files.exists(snapshot)) {
            throw new IllegalArgumentException("No snapshot found for hash: " + targetHash);
        }

        Path dir = getTemplateFilesDir(name);
        // Clear existing files
        if (Files.isDirectory(dir)) {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    if (!d.equals(dir)) Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Files.createDirectories(dir);

        // Extract snapshot
        try (var fis = Files.newInputStream(snapshot);
                var gzip = new GzipCompressorInputStream(fis);
                var tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path target = dir.resolve(entry.getName()).normalize();
                if (!target.startsWith(dir)) continue; // zip-slip guard
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(tar, target, REPLACE_EXISTING);
                }
            }
        }

        rehash(name);
        logger.debug("Template {} restored to hash={}", name, targetHash.substring(0, 8));
    }

    /**
     * Get the files directory for a specific template.
     */
    public Path getTemplateFilesDir(String name) {
        return filesDir.resolve(name).resolve("files");
    }

    /**
     * List files inside a snapshot archive at the given sub-path (depth 1). Returns
     * entries in the same shape as the live file listing.
     *
     * @throws IllegalArgumentException
     *             if no snapshot exists for the given hash
     */
    public List<Map<String, Object>> listSnapshotFiles(String name, String hash, String subPath) throws IOException {
        Path snapshot = filesDir.resolve(name).resolve("snapshots").resolve(hash + ".tar.gz");
        if (!Files.exists(snapshot)) {
            throw new IllegalArgumentException("No snapshot found for hash: " + hash);
        }

        String prefix = (subPath != null && !subPath.isBlank()) ? subPath.replace('\\', '/') + "/" : "";

        // Collect all entries from the archive
        var dirs = new LinkedHashMap<String, Long>(); // name -> 0
        var fileEntries = new LinkedHashMap<String, Long>(); // name -> size

        try (var fis = Files.newInputStream(snapshot);
                var gzip = new GzipCompressorInputStream(fis);
                var tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.contains("..")) continue; // zip-slip guard

                if (!entryName.startsWith(prefix)) continue;
                String remainder = entryName.substring(prefix.length());
                if (remainder.isEmpty()) continue;

                int slash = remainder.indexOf('/');
                if (slash == -1) {
                    // Direct child file
                    fileEntries.put(remainder, entry.getSize());
                } else {
                    // Child directory (derive from path)
                    String dirName = remainder.substring(0, slash);
                    dirs.putIfAbsent(dirName, 0L);
                }
            }
        }

        var result = new ArrayList<Map<String, Object>>();
        // Directories first
        dirs.keySet().stream().sorted().forEach(d -> result.add(Map.of("name", d, "isDirectory", true, "size", 0L)));
        // Then files
        fileEntries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> result.add(Map.of("name", e.getKey(), "isDirectory", false, "size", e.getValue())));

        return result;
    }

    /**
     * Read a single file's content from a snapshot archive.
     *
     * @return the file content as a UTF-8 string, or {@code null} if not found or
     *         binary
     * @throws IllegalArgumentException
     *             if no snapshot exists for the given hash
     */
    public String readSnapshotFileContent(String name, String hash, String filePath) throws IOException {
        Path snapshot = filesDir.resolve(name).resolve("snapshots").resolve(hash + ".tar.gz");
        if (!Files.exists(snapshot)) {
            throw new IllegalArgumentException("No snapshot found for hash: " + hash);
        }

        String normalized = filePath.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid file path: " + filePath);
        }

        try (var fis = Files.newInputStream(snapshot);
                var gzip = new GzipCompressorInputStream(fis);
                var tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.equals(normalized)) {
                    byte[] bytes = tar.readNBytes((int) entry.getSize());
                    try {
                        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception _) {
                        return null; // binary file
                    }
                }
            }
        }
        return null;
    }

    public boolean exists(String name) {
        return templates.containsKey(name);
    }

    /**
     * Suppress watcher-triggered rehashes for the given template. Use this before
     * programmatic file writes to avoid race conditions and redundant rehashes
     * while files are still being written.
     */
    public void suppressWatcher(String name) {
        suppressedTemplates.add(name);
    }

    /**
     * Re-enable watcher for the given template. Does not rehash — the caller is
     * responsible for ensuring the template hash is up-to-date (e.g. via
     * {@link #save}).
     */
    public void unsuppressWatcher(String name) {
        suppressedTemplates.remove(name);
    }

    // -------------------------------------------------------------------------
    // Filesystem watcher
    // -------------------------------------------------------------------------

    /**
     * Start a {@link WatchService} on the {@code templates/} directory tree. Runs
     * on a virtual thread -- no need to manage its lifecycle explicitly.
     */
    private void startWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerRecursive(filesDir);
        } catch (IOException e) {
            logger.warn("Could not start template file watcher: {}", e.getMessage());
            return;
        }
        Thread.ofVirtual().name("template-watcher").start(this::runWatchLoop);
        logger.debug("Template file watcher started on {}", filesDir);
    }

    /**
     * Recursively register {@code dir} and all its subdirectories with the
     * WatchService.
     */
    private void registerRecursive(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                d.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void runWatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException _) {
                break;
            }

            Path watchedDir = (Path) key.watchable();

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                Path changed = watchedDir.resolve(((WatchEvent<Path>) event).context());

                // Register newly created subdirectories so we watch them too
                if (event.kind() == ENTRY_CREATE && Files.isDirectory(changed)) {
                    try {
                        registerRecursive(changed);
                    } catch (IOException e) {
                        logger.warn("Failed to register watcher for {}: {}", changed, e.getMessage());
                    }
                }

                // Determine which template was affected and rehash it
                String templateName = extractTemplateName(changed);
                if (templateName != null) {
                    if (suppressedTemplates.contains(templateName)) {
                        logger.debug("Watcher suppressed for template {} -- skipping rehash", templateName);
                        continue;
                    }
                    try {
                        rehash(templateName);
                    } catch (IOException e) {
                        logger.warn("Failed to rehash template {} after fs change: {}", templateName, e.getMessage());
                    }
                }
            }

            if (!key.reset()) {
                // Directory was deleted; no longer watching it
            }
        }
    }

    /**
     * Returns the template name for any path under {@code templates/}, or
     * {@code null} if the path is not inside a template directory.
     */
    private String extractTemplateName(Path path) {
        if (!path.startsWith(filesDir)) return null;
        Path relative = filesDir.relativize(path);
        if (relative.getNameCount() == 0) return null;
        return relative.getName(0).toString();
    }

    /**
     * Returns the cluster-wide forwarding secret. The file at
     * {@code config/security/forwarding.secret} is created at controller startup.
     */
    public String getForwardingSecret() throws IOException {
        Path secretFile = Path.of("config", "security", "forwarding.secret");
        return Files.readString(secretFile).trim();
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Template name cannot be blank");
        }
        if (name.length() > 32) {
            throw new IllegalArgumentException("Template name too long (max 32): " + name);
        }
        if (!name.matches("[a-z0-9_][a-z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid template name: " + name + " (must match [a-z0-9_][a-z0-9_-]*)");
        }
    }
}
