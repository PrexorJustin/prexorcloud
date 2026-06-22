package me.prexorjustin.prexorcloud.controller.recovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates that a backup bundle contains the controller state needed for a
 * restore before the operator starts overwriting local data.
 */
public final class RestoreValidator {

    private static final List<String> MONGO_ARTIFACT_EXTENSIONS = List.of(".bson", ".json", ".jsonl");

    private final BackupVerifier backupVerifier;

    public RestoreValidator() {
        this(new BackupVerifier());
    }

    RestoreValidator(BackupVerifier backupVerifier) {
        this.backupVerifier = backupVerifier;
    }

    public RestoreValidationResult validate(BackupScope scope, Path backupRoot) {
        var filesystem = backupVerifier.verifyFilesystem(scope, backupRoot);
        return new RestoreValidationResult(
                filesystem,
                missingMongoCollections(scope, backupRoot),
                missingMongoCollectionPrefixes(scope, backupRoot),
                emptyRequiredFiles(scope, backupRoot));
    }

    private List<String> missingMongoCollections(BackupScope scope, Path backupRoot) {
        Path mongoRoot =
                backupRoot.resolve("mongo").resolve(scope.mongoDatabase()).normalize();
        var missing = new ArrayList<String>();
        for (String collection : scope.mongoCollections()) {
            if (!hasMongoCollectionArtifact(mongoRoot, collection)) {
                missing.add(collection);
            }
        }
        return missing;
    }

    private boolean hasMongoCollectionArtifact(Path mongoRoot, String collection) {
        if (Files.isDirectory(mongoRoot.resolve(collection))) return true;
        for (String extension : MONGO_ARTIFACT_EXTENSIONS) {
            if (Files.isRegularFile(mongoRoot.resolve(collection + extension))) return true;
        }
        return false;
    }

    private List<String> missingMongoCollectionPrefixes(BackupScope scope, Path backupRoot) {
        Set<String> declaredPrefixes = readMongoPrefixes(scope, backupRoot);
        var missing = new ArrayList<String>();
        for (String prefix : scope.mongoCollectionPrefixes()) {
            if (!declaredPrefixes.contains(prefix.toLowerCase(Locale.ROOT))) {
                missing.add(prefix);
            }
        }
        return missing;
    }

    private Set<String> readMongoPrefixes(BackupScope scope, Path backupRoot) {
        Path mongoRoot =
                backupRoot.resolve("mongo").resolve(scope.mongoDatabase()).normalize();
        Path prefixes = Files.isRegularFile(mongoRoot.resolve("prefixes.txt"))
                ? mongoRoot.resolve("prefixes.txt")
                : mongoRoot.resolve("manifest.txt");
        return readPrefixManifest(prefixes);
    }

    private Set<String> readPrefixManifest(Path prefixes) {
        if (!Files.isRegularFile(prefixes)) return Set.of();
        try {
            var declared = new HashSet<String>();
            for (String line : Files.readAllLines(prefixes)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                declared.add(trimmed.toLowerCase(Locale.ROOT));
            }
            return declared;
        } catch (IOException _) {
            return Set.of();
        }
    }

    private List<Path> emptyRequiredFiles(BackupScope scope, Path backupRoot) {
        var emptyFiles = new ArrayList<Path>();
        for (Path file : scope.files()) {
            Path resolved = backupRoot.resolve(file).normalize();
            try {
                if (Files.isRegularFile(resolved) && Files.size(resolved) == 0) {
                    emptyFiles.add(file);
                }
            } catch (IOException _) {
                emptyFiles.add(file);
            }
        }
        return emptyFiles;
    }

    public record RestoreValidationResult(
            BackupVerifier.VerificationResult filesystem,
            List<String> missingMongoCollections,
            List<String> missingMongoCollectionPrefixes,
            List<Path> emptyRequiredFiles) {

        public RestoreValidationResult {
            missingMongoCollections = List.copyOf(missingMongoCollections);
            missingMongoCollectionPrefixes = List.copyOf(missingMongoCollectionPrefixes);
            emptyRequiredFiles = List.copyOf(emptyRequiredFiles);
        }

        public boolean valid() {
            return filesystem.valid()
                    && missingMongoCollections.isEmpty()
                    && missingMongoCollectionPrefixes.isEmpty()
                    && emptyRequiredFiles.isEmpty();
        }
    }
}
