package me.prexorjustin.prexorcloud.controller.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupScopeTest {

    @TempDir
    Path tempDir;

    @Test
    void scopeIncludesDurableControllerState() {
        BackupScope scope = BackupScope.from(new ControllerConfig(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));

        assertTrue(scope.mongoCollections().contains("groups"));
        assertTrue(scope.mongoCollections().contains("workflow_start_retries"));
        assertTrue(scope.mongoCollections().contains("instance_composition_plans"));
        assertTrue(scope.mongoCollectionPrefixes().contains("platform_"));
        assertTrue(scope.files().contains(Path.of("config", "security", "ca.p12")));
        assertTrue(scope.directories().contains(Path.of("templates")));
        assertTrue(scope.directories().contains(Path.of("modules")));
        assertTrue(scope.directories().contains(Path.of("modules", "data")));
    }

    @Test
    void verifierReportsMissingFilesystemEntries() throws Exception {
        BackupScope scope = BackupScope.from(new ControllerConfig(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));
        var verifier = new BackupVerifier();

        var missing = verifier.verifyFilesystem(scope, tempDir);
        assertFalse(missing.valid());
        assertTrue(missing.missingFiles().contains(Path.of("config", "controller.yml")));

        for (Path file : scope.files()) {
            Path resolved = tempDir.resolve(file);
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, "backup");
        }
        for (Path directory : scope.directories()) {
            Files.createDirectories(tempDir.resolve(directory));
        }

        assertTrue(verifier.verifyFilesystem(scope, tempDir).valid());
    }

    @Test
    void restoreValidatorRequiresMongoArtifacts() throws Exception {
        BackupScope scope = BackupScope.from(new ControllerConfig(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));
        for (Path file : scope.files()) {
            Path resolved = tempDir.resolve(file);
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, "backup");
        }
        for (Path directory : scope.directories()) {
            Files.createDirectories(tempDir.resolve(directory));
        }

        var validator = new RestoreValidator();
        var missing = validator.validate(scope, tempDir);

        assertFalse(missing.valid());
        assertTrue(missing.missingMongoCollections().contains("groups"));
        assertTrue(missing.missingMongoCollectionPrefixes().contains("platform_"));

        Path mongoRoot = tempDir.resolve(Path.of("mongo", scope.mongoDatabase()));
        Files.createDirectories(mongoRoot);
        for (String collection : scope.mongoCollections()) {
            Files.writeString(mongoRoot.resolve(collection + ".jsonl"), "{}\n");
        }
        Files.writeString(
                mongoRoot.resolve("prefixes.txt"),
                scope.mongoCollectionPrefixes().stream().collect(Collectors.joining(System.lineSeparator())));

        assertTrue(validator.validate(scope, tempDir).valid());
    }

    @Test
    void restoreValidatorRejectsEmptyRequiredFiles() throws Exception {
        BackupScope scope = BackupScope.from(new ControllerConfig(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));
        Path controllerConfig = tempDir.resolve(Path.of("config", "controller.yml"));
        Files.createDirectories(controllerConfig.getParent());
        Files.writeString(controllerConfig, "");

        var result = new RestoreValidator().validate(scope, tempDir);

        assertTrue(result.emptyRequiredFiles().contains(Path.of("config", "controller.yml")));
    }
}
