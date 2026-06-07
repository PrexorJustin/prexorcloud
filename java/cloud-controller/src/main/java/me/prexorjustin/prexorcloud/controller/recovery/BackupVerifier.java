package me.prexorjustin.prexorcloud.controller.recovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates file-system portions of a controller backup before restore.
 */
public final class BackupVerifier {

    public record VerificationResult(List<Path> missingFiles, List<Path> missingDirectories) {

        public VerificationResult {
            missingFiles = List.copyOf(missingFiles);
            missingDirectories = List.copyOf(missingDirectories);
        }

        public boolean valid() {
            return missingFiles.isEmpty() && missingDirectories.isEmpty();
        }
    }

    public VerificationResult verifyFilesystem(BackupScope scope, Path backupRoot) {
        var missingFiles = new ArrayList<Path>();
        var missingDirectories = new ArrayList<Path>();

        for (Path file : scope.files()) {
            Path resolved = backupRoot.resolve(file).normalize();
            if (!Files.isRegularFile(resolved)) {
                missingFiles.add(file);
            }
        }
        for (Path directory : scope.directories()) {
            Path resolved = backupRoot.resolve(directory).normalize();
            if (!Files.isDirectory(resolved)) {
                missingDirectories.add(directory);
            }
        }

        return new VerificationResult(missingFiles, missingDirectories);
    }
}
