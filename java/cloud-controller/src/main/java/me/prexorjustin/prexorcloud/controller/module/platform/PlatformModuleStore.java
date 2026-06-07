package me.prexorjustin.prexorcloud.controller.module.platform;

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
import java.util.jar.JarFile;

import me.prexorjustin.prexorcloud.api.module.platform.PlatformModuleManifest;
import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestException;
import me.prexorjustin.prexorcloud.modules.runtime.PlatformModuleManifestParser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Controller-owned content-addressed storage for platform-module jars.
 */
public final class PlatformModuleStore {

    /**
     * Signature sidecar discovered alongside a prepared jar. {@code kind} is the wire form
     * sent to daemons in {@code ModuleInstall.signature_kind} ({@code "sig"} or
     * {@code "cosign-bundle"}); {@code path} is the on-disk location of the sidecar.
     */
    public record SidecarRef(String kind, Path path) {}

    public record PreparedModule(
            Path sourceJar, String sha256, long sizeBytes, PlatformModuleManifest manifest, SidecarRef sidecar) {}

    /**
     * Stored module record. {@code sidecarPath} / {@code sidecarKind} are non-null only
     * when a signature sidecar was committed alongside the jar.
     */
    public record StoredModule(
            String moduleId,
            String version,
            String sha256,
            Path jarPath,
            long sizeBytes,
            Instant storedAt,
            PlatformModuleManifest manifest,
            Path sidecarPath,
            String sidecarKind) {}

    public record ArtifactContent(String fileName, byte[] bytes) {}

    private record StoredModuleIndexEntry(
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
    private static final TypeReference<Map<String, StoredModuleIndexEntry>> INDEX_TYPE = new TypeReference<>() {};

    private final Path rootDirectory;
    private final Path artifactsDirectory;
    private final Path indexFile;

    public PlatformModuleStore(Path rootDirectory) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
        this.artifactsDirectory = rootDirectory.resolve("artifacts");
        this.indexFile = rootDirectory.resolve("modules-index.json");
    }

    public PreparedModule prepare(Path sourceJar) {
        Objects.requireNonNull(sourceJar, "sourceJar");
        if (!Files.isRegularFile(sourceJar)) {
            throw new IllegalArgumentException("module jar does not exist: " + sourceJar);
        }

        try {
            PlatformModuleManifest manifest = parseManifest(sourceJar);
            String sha256 = sha256(sourceJar);
            return new PreparedModule(sourceJar, sha256, Files.size(sourceJar), manifest, discoverSidecar(sourceJar));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to prepare platform module", e);
        }
    }

    /**
     * Locate a signature sidecar by checking the conventional sibling suffixes used by the
     * REST upload route. Returns {@code null} when the upload was unsigned.
     */
    private static SidecarRef discoverSidecar(Path sourceJar) {
        Path cosign = sourceJar.resolveSibling(sourceJar.getFileName() + ".cosign.bundle");
        if (Files.isRegularFile(cosign)) {
            return new SidecarRef("cosign-bundle", cosign);
        }
        Path sig = sourceJar.resolveSibling(sourceJar.getFileName() + ".sig");
        if (Files.isRegularFile(sig)) {
            return new SidecarRef("sig", sig);
        }
        return null;
    }

    public synchronized StoredModule commit(PreparedModule preparedModule) {
        Objects.requireNonNull(preparedModule, "preparedModule");

        try {
            Files.createDirectories(artifactsDirectory);

            Path targetPath = artifactsDirectory.resolve(preparedModule.sha256() + ".jar");
            if (!Files.exists(targetPath)) {
                Files.copy(preparedModule.sourceJar(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            String sidecarFileName = null;
            String sidecarKind = null;
            Path sidecarPath = null;
            if (preparedModule.sidecar() != null) {
                sidecarKind = preparedModule.sidecar().kind();
                sidecarFileName = sidecarFileName(preparedModule.sha256(), sidecarKind);
                sidecarPath = artifactsDirectory.resolve(sidecarFileName);
                if (!Files.exists(sidecarPath)) {
                    Files.copy(preparedModule.sidecar().path(), sidecarPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            Instant storedAt = Instant.now();
            Map<String, StoredModuleIndexEntry> index = readIndex();
            index.put(
                    preparedModule.manifest().id(),
                    new StoredModuleIndexEntry(
                            preparedModule.manifest().id(),
                            preparedModule.manifest().version(),
                            preparedModule.sha256(),
                            targetPath.getFileName().toString(),
                            storedAt,
                            sidecarFileName,
                            sidecarKind));
            writeIndex(index);
            garbageCollect(index);

            return new StoredModule(
                    preparedModule.manifest().id(),
                    preparedModule.manifest().version(),
                    preparedModule.sha256(),
                    targetPath,
                    preparedModule.sizeBytes(),
                    storedAt,
                    preparedModule.manifest(),
                    sidecarPath,
                    sidecarKind);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to commit platform module", e);
        }
    }

    private static String sidecarFileName(String sha256, String kind) {
        return switch (kind) {
            case "sig" -> sha256 + ".sig";
            case "cosign-bundle" -> sha256 + ".cosign.bundle";
            default -> throw new IllegalStateException("unknown sidecar kind: " + kind);
        };
    }

    public synchronized List<StoredModule> list() {
        Map<String, StoredModuleIndexEntry> index = readIndex();
        return index.values().stream()
                .map(this::toStoredModule)
                .flatMap(Optional::stream)
                .toList();
    }

    public synchronized Optional<StoredModule> find(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        return Optional.ofNullable(readIndex().get(moduleId)).flatMap(this::toStoredModule);
    }

    public synchronized Optional<StoredModule> remove(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        Map<String, StoredModuleIndexEntry> index = readIndex();
        StoredModuleIndexEntry removed = index.remove(moduleId);
        if (removed == null) {
            return Optional.empty();
        }
        writeIndex(index);
        garbageCollect(index);
        return toStoredModule(removed);
    }

    public synchronized Optional<ArtifactContent> readArtifact(String moduleId, String relativePath) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(relativePath, "relativePath");

        StoredModule storedModule = find(moduleId).orElse(null);
        if (storedModule == null) {
            return Optional.empty();
        }

        try (JarFile jarFile = new JarFile(storedModule.jarPath().toFile())) {
            var artifactEntry = jarFile.getJarEntry(relativePath);
            if (artifactEntry == null || artifactEntry.isDirectory()) {
                return Optional.empty();
            }
            try (InputStream inputStream = jarFile.getInputStream(artifactEntry)) {
                String fileName = Path.of(relativePath).getFileName().toString();
                return Optional.of(new ArtifactContent(fileName, inputStream.readAllBytes()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read platform module artifact " + relativePath, e);
        }
    }

    private Optional<StoredModule> toStoredModule(StoredModuleIndexEntry entry) {
        Path jarPath = artifactsDirectory.resolve(entry.fileName());
        if (!Files.isRegularFile(jarPath)) {
            return Optional.empty();
        }

        Path sidecarPath = null;
        if (entry.sidecarFileName() != null && !entry.sidecarFileName().isBlank()) {
            Path candidate = artifactsDirectory.resolve(entry.sidecarFileName());
            if (Files.isRegularFile(candidate)) {
                sidecarPath = candidate;
            }
        }

        try {
            PlatformModuleManifest manifest = parseManifest(jarPath);
            return Optional.of(new StoredModule(
                    entry.moduleId(),
                    entry.version(),
                    entry.sha256(),
                    jarPath,
                    Files.size(jarPath),
                    entry.storedAt(),
                    manifest,
                    sidecarPath,
                    entry.sidecarKind()));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read stored platform module " + jarPath, e);
        }
    }

    private Map<String, StoredModuleIndexEntry> readIndex() {
        if (!Files.isRegularFile(indexFile)) {
            return new LinkedHashMap<>();
        }

        try {
            Map<String, StoredModuleIndexEntry> index = MAPPER.readValue(indexFile.toFile(), INDEX_TYPE);
            return index == null ? new LinkedHashMap<>() : new LinkedHashMap<>(index);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read platform module index", e);
        }
    }

    private void writeIndex(Map<String, StoredModuleIndexEntry> index) {
        try {
            Files.createDirectories(rootDirectory);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(indexFile.toFile(), index);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write platform module index", e);
        }
    }

    private void garbageCollect(Map<String, StoredModuleIndexEntry> index) {
        try {
            Files.createDirectories(artifactsDirectory);
            var referenced = new java.util.HashSet<String>();
            for (StoredModuleIndexEntry entry : index.values()) {
                referenced.add(entry.fileName());
                if (entry.sidecarFileName() != null && !entry.sidecarFileName().isBlank()) {
                    referenced.add(entry.sidecarFileName());
                }
            }
            try (var stream = Files.list(artifactsDirectory)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    if (!referenced.contains(path.getFileName().toString())) {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to garbage collect platform module artifacts", e);
        }
    }

    private static PlatformModuleManifest parseManifest(Path jarPath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            var manifestEntry = jarFile.getJarEntry(PlatformModuleManifestParser.FILE_NAME);
            if (manifestEntry == null) {
                throw new PlatformModuleManifestException(
                        jarPath.getFileName().toString(), "missing " + PlatformModuleManifestParser.FILE_NAME);
            }
            try (InputStream inputStream = jarFile.getInputStream(manifestEntry)) {
                return PlatformModuleManifestParser.parse(
                        inputStream, jarPath.getFileName().toString());
            }
        }
    }

    private static String sha256(Path sourceJar) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream inputStream = Files.newInputStream(sourceJar)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
