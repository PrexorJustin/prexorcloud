package me.prexorjustin.prexorcloud.controller.recovery;

import java.nio.file.Path;
import java.util.List;

import me.prexorjustin.prexorcloud.controller.config.ControllerConfig;

/**
 * Canonical controller backup scope used by recovery tooling.
 */
public record BackupScope(
        String mongoDatabase,
        List<String> mongoCollections,
        List<String> mongoCollectionPrefixes,
        List<Path> files,
        List<Path> directories) {

    private static final List<String> MONGO_COLLECTIONS = List.of(
            "users",
            "roles",
            "catalog",
            "groups",
            "templates",
            "deployments",
            "crashes",
            "audit_log",
            "nodes",
            "user_preferences",
            "workflow_transfers",
            "workflow_drains",
            "workflow_healing",
            "workflow_start_retries",
            "instance_composition_plans",
            "counters");

    private static final List<String> MONGO_COLLECTION_PREFIXES = List.of("platform_");

    private static final List<Path> SECURITY_FILES = List.of(
            Path.of("config", "controller.yml"),
            Path.of("config", "security", "ca.p12"),
            Path.of("config", "security", "ca.pem"),
            Path.of("config", "security", ".ca-password"),
            Path.of("config", "security", "server.p12"),
            Path.of("config", "security", "join-tokens.json"),
            Path.of("config", "security", "forwarding.secret"));

    /**
     * Scope files that are backed up when present but whose absence is NOT a
     * restore-blocking validation failure. {@code join-tokens.json} is only
     * written when cluster join tokens are persisted, so a controller with no
     * pending joins legitimately has none — the backup must still verify valid.
     */
    public static final List<Path> OPTIONAL_FILES = List.of(Path.of("config", "security", "join-tokens.json"));

    public static BackupScope from(ControllerConfig config) {
        return new BackupScope(
                config.database().database(),
                MONGO_COLLECTIONS,
                MONGO_COLLECTION_PREFIXES,
                SECURITY_FILES,
                List.of(
                        Path.of("templates"),
                        Path.of(config.modules().directory()),
                        Path.of(config.modules().dataDirectory())));
    }

    public BackupScope(
            String mongoDatabase,
            List<String> mongoCollections,
            List<Path> files,
            List<Path> directories) {
        this(mongoDatabase, mongoCollections, List.of(), files, directories);
    }
}
