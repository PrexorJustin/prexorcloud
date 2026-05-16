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
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));

        assertTrue(scope.mongoCollections().contains("groups"));
        assertTrue(scope.mongoCollections().contains("workflow_start_retries"));
        assertTrue(scope.mongoCollections().contains("instance_composition_plans"));
        assertTrue(scope.mongoCollectionPrefixes().contains("platform_"));
        assertTrue(
                scope.redisKeyPrefixes().contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.LEASE_PREFIX));
        assertTrue(scope.redisKeyPrefixes()
                .contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.LEASE_TOKEN_PREFIX));
        assertTrue(
                scope.redisKeyPrefixes().contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.NODE_PREFIX));
        assertTrue(scope.redisKeyPrefixes()
                .contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.INSTANCE_PREFIX));
        assertTrue(scope.redisKeyPrefixes()
                .contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.PLAYER_PREFIX));
        assertTrue(scope.redisKeyPrefixes()
                .contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.PLUGIN_TOKEN_PREFIX));
        assertTrue(scope.redisKeyPrefixes()
                .contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.PLATFORM_MODULE_PREFIX));
        assertTrue(scope.redisKeyPrefixes()
                .contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.RATE_LIMIT_PREFIX));
        assertTrue(scope.redisKeyPrefixes()
                .contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.COOLDOWN_PREFIX));
        assertTrue(scope.redisKeyPrefixes()
                .contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.WORKLOAD_SEQUENCE_PREFIX));
        assertTrue(scope.redisKeyPrefixes()
                .contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.CONSOLE_PREFIX));
        assertTrue(
                scope.redisKeyPrefixes().contains(me.prexorjustin.prexorcloud.controller.redis.RedisKeys.SSE_PREFIX));
        assertTrue(scope.files().contains(Path.of("config", "security", "ca.p12")));
        assertTrue(scope.directories().contains(Path.of("templates")));
        assertTrue(scope.directories().contains(Path.of("modules")));
        assertTrue(scope.directories().contains(Path.of("modules", "data")));
    }

    @Test
    void verifierReportsMissingFilesystemEntries() throws Exception {
        BackupScope scope = BackupScope.from(new ControllerConfig(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
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
    void restoreValidatorRequiresMongoArtifactsAndRedisPrefixManifest() throws Exception {
        BackupScope scope = BackupScope.from(new ControllerConfig(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
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
        assertTrue(missing.missingRedisPrefixes().contains("prexor:v1:lease:"));

        Path mongoRoot = tempDir.resolve(Path.of("mongo", scope.mongoDatabase()));
        Files.createDirectories(mongoRoot);
        for (String collection : scope.mongoCollections()) {
            Files.writeString(mongoRoot.resolve(collection + ".jsonl"), "{}\n");
        }
        Files.writeString(
                mongoRoot.resolve("prefixes.txt"),
                scope.mongoCollectionPrefixes().stream().collect(Collectors.joining(System.lineSeparator())));
        Path redisRoot = tempDir.resolve("redis");
        Files.createDirectories(redisRoot);
        Files.writeString(
                redisRoot.resolve("prefixes.txt"),
                scope.redisKeyPrefixes().stream().collect(Collectors.joining(System.lineSeparator())));

        assertTrue(validator.validate(scope, tempDir).valid());
    }

    @Test
    void restoreValidatorRejectsEmptyRequiredFiles() throws Exception {
        BackupScope scope = BackupScope.from(new ControllerConfig(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null));
        Path controllerConfig = tempDir.resolve(Path.of("config", "controller.yml"));
        Files.createDirectories(controllerConfig.getParent());
        Files.writeString(controllerConfig, "");

        var result = new RestoreValidator().validate(scope, tempDir);

        assertTrue(result.emptyRequiredFiles().contains(Path.of("config", "controller.yml")));
    }
}
