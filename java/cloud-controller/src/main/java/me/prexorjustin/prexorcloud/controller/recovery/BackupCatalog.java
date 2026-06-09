package me.prexorjustin.prexorcloud.controller.recovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads and prunes backup bundles from the on-disk backup directory. The disk
 * manifest is the source of truth — there is no Mongo index in v1.
 */
public final class BackupCatalog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public BackupCatalog(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path bundleRoot(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Backup id must not be blank");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new IllegalArgumentException("Invalid backup id: " + id);
        }
        return root.resolve(id).normalize();
    }

    public List<BackupManifest> list() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        var manifests = new ArrayList<BackupManifest>();
        try (var stream = Files.list(root)) {
            for (Path candidate : stream.filter(Files::isDirectory).toList()) {
                Optional<BackupManifest> manifest =
                        tryLoad(candidate.getFileName().toString());
                manifest.ifPresent(manifests::add);
            }
        }
        manifests.sort(Comparator.comparingLong(BackupManifest::createdAtMs).reversed());
        return List.copyOf(manifests);
    }

    public Optional<BackupManifest> load(String id) throws IOException {
        Path bundle = bundleRoot(id);
        Path manifestFile = bundle.resolve("manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            return Optional.empty();
        }
        return Optional.of(JSON.readValue(manifestFile.toFile(), BackupManifest.class));
    }

    public boolean delete(String id) throws IOException {
        Path bundle = bundleRoot(id);
        if (!Files.isDirectory(bundle)) return false;
        deleteRecursively(bundle);
        return true;
    }

    public List<BackupManifest> prune(int retentionCount) throws IOException {
        if (retentionCount <= 0) return List.of();
        List<BackupManifest> manifests = list();
        if (manifests.size() <= retentionCount) return List.of();
        var pruned = new ArrayList<BackupManifest>();
        for (int i = retentionCount; i < manifests.size(); i++) {
            BackupManifest manifest = manifests.get(i);
            if (delete(manifest.id())) {
                pruned.add(manifest);
            }
        }
        return List.copyOf(pruned);
    }

    private Optional<BackupManifest> tryLoad(String id) {
        try {
            return load(id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path target) throws IOException {
        if (!Files.exists(target)) return;
        try (var stream = Files.walk(target)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
