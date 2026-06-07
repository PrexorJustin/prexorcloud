package me.prexorjustin.prexorcloud.daemon.module;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Daemon-side content-addressed store for platform-module jars pushed from the controller.
 *
 * <p>Layout mirrors the controller's {@code PlatformModuleStore}: {@code artifacts/{sha256}.jar}
 * for binary content plus a flat {@code modules-index.json} with one entry per installed
 * module id. Daemon modules have no Mongo/Redis storage and no REST routes, so the store
 * does not retain a manifest cache — the jar is the only persisted state.
 */
public final class DaemonModuleStore {

    public record StoredModule(
            String moduleId,
            String version,
            String sha256,
            Path jarPath,
            long sizeBytes,
            Instant storedAt,
            Path sidecarPath,
            String sidecarKind) {}

    private record IndexEntry(
            String moduleId,
            String version,
            String sha256,
            String fileName,
            Instant storedAt,
            String sidecarFileName,
            String sidecarKind) {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<Map<String, IndexEntry>> INDEX_TYPE = new TypeReference<>() {};

    private final Path rootDirectory;
    private final Path artifactsDirectory;
    private final Path indexFile;

    public DaemonModuleStore(Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
        this.artifactsDirectory = rootDirectory.resolve("artifacts");
        this.indexFile = rootDirectory.resolve("modules-index.json");
    }

    /**
     * Persist a controller-pushed module. {@code expectedSha256} is the hex digest claimed
     * by the controller; this method verifies the actual jar bytes match before committing
     * to detect transport corruption. Idempotent: re-pushing the same {@code (moduleId,sha256)}
     * pair returns the existing entry without rewriting.
     */
    public synchronized StoredModule commit(
            String moduleId,
            String version,
            String expectedSha256,
            byte[] jarBytes,
            byte[] sidecarBytes,
            String sidecarKind) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        Objects.requireNonNull(jarBytes, "jarBytes");

        String actualSha256 = sha256(jarBytes);
        if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
            throw new IllegalStateException("module sha256 mismatch for '" + moduleId + "': expected " + expectedSha256
                    + " but got " + actualSha256);
        }

        try {
            Files.createDirectories(artifactsDirectory);
            Path targetPath = artifactsDirectory.resolve(actualSha256 + ".jar");
            if (!Files.exists(targetPath)) {
                Path tmp = Files.createTempFile(artifactsDirectory, actualSha256 + "-", ".jar.tmp");
                Files.write(tmp, jarBytes);
                Files.move(tmp, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }

            String sidecarFileName = null;
            Path sidecarPath = null;
            String resolvedKind = null;
            if (sidecarBytes != null && sidecarBytes.length > 0 && sidecarKind != null && !sidecarKind.isBlank()) {
                resolvedKind = sidecarKind;
                sidecarFileName = sidecarFileName(actualSha256, sidecarKind);
                sidecarPath = artifactsDirectory.resolve(sidecarFileName);
                if (!Files.exists(sidecarPath)) {
                    Path tmpSidecar = Files.createTempFile(artifactsDirectory, actualSha256 + "-", ".sidecar.tmp");
                    Files.write(tmpSidecar, sidecarBytes);
                    Files.move(
                            tmpSidecar,
                            sidecarPath,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }

            Instant storedAt = Instant.now();
            Map<String, IndexEntry> index = readIndex();
            index.put(
                    moduleId,
                    new IndexEntry(
                            moduleId,
                            version,
                            actualSha256,
                            targetPath.getFileName().toString(),
                            storedAt,
                            sidecarFileName,
                            resolvedKind));
            writeIndex(index);
            garbageCollect(index);

            return new StoredModule(
                    moduleId, version, actualSha256, targetPath, jarBytes.length, storedAt, sidecarPath, resolvedKind);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to commit daemon module " + moduleId, e);
        }
    }

    private static String sidecarFileName(String sha256, String kind) {
        return switch (kind) {
            case "sig" -> sha256 + ".sig";
            case "cosign-bundle" -> sha256 + ".cosign.bundle";
            default -> throw new IllegalStateException("unknown sidecar kind: " + kind);
        };
    }

    public synchronized Optional<StoredModule> remove(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        Map<String, IndexEntry> index = readIndex();
        IndexEntry removed = index.remove(moduleId);
        if (removed == null) {
            return Optional.empty();
        }
        writeIndex(index);
        garbageCollect(index);
        return Optional.of(toStored(removed));
    }

    public synchronized Optional<StoredModule> find(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        IndexEntry entry = readIndex().get(moduleId);
        return entry == null ? Optional.empty() : Optional.of(toStored(entry));
    }

    public synchronized List<StoredModule> list() {
        return readIndex().values().stream().map(this::toStored).toList();
    }

    private StoredModule toStored(IndexEntry entry) {
        Path jarPath = artifactsDirectory.resolve(entry.fileName());
        long sizeBytes;
        try {
            sizeBytes = Files.exists(jarPath) ? Files.size(jarPath) : 0;
        } catch (IOException _) {
            sizeBytes = 0;
        }
        Path sidecarPath = null;
        if (entry.sidecarFileName() != null && !entry.sidecarFileName().isBlank()) {
            Path candidate = artifactsDirectory.resolve(entry.sidecarFileName());
            if (Files.isRegularFile(candidate)) {
                sidecarPath = candidate;
            }
        }
        return new StoredModule(
                entry.moduleId(),
                entry.version(),
                entry.sha256(),
                jarPath,
                sizeBytes,
                entry.storedAt(),
                sidecarPath,
                entry.sidecarKind());
    }

    private Map<String, IndexEntry> readIndex() {
        if (!Files.isRegularFile(indexFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, IndexEntry> index = MAPPER.readValue(indexFile.toFile(), INDEX_TYPE);
            return index == null ? new LinkedHashMap<>() : new LinkedHashMap<>(index);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read daemon module index", e);
        }
    }

    private void writeIndex(Map<String, IndexEntry> index) {
        try {
            Files.createDirectories(rootDirectory);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(indexFile.toFile(), index);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write daemon module index", e);
        }
    }

    private void garbageCollect(Map<String, IndexEntry> index) {
        try {
            Files.createDirectories(artifactsDirectory);
            var referenced = new java.util.HashSet<String>();
            for (IndexEntry entry : index.values()) {
                referenced.add(entry.fileName());
                if (entry.sidecarFileName() != null && !entry.sidecarFileName().isBlank()) {
                    referenced.add(entry.sidecarFileName());
                }
            }
            try (var stream = Files.list(artifactsDirectory)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    String name = path.getFileName().toString();
                    if (name.endsWith(".tmp") || !referenced.contains(name)) {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to garbage collect daemon module artifacts", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
