package me.prexorjustin.prexorcloud.controller.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.recovery.RestoreExecutor.DataRestoreReport;
import me.prexorjustin.prexorcloud.controller.recovery.RestoreExecutor.RestoreMode;

import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RestoreExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void dryRunReportsFilesystemEntriesWithoutWritingTarget() throws Exception {
        BackupScope scope = smallScope();
        Path backupRoot = tempDir.resolve("backup");
        Path targetRoot = tempDir.resolve("target");
        createValidBackup(scope, backupRoot);

        var report = executor().restoreFilesystem(scope, backupRoot, targetRoot, RestoreMode.DRY_RUN);

        assertFalse(report.applied());
        assertEquals(2, report.entries().size());
        assertFalse(Files.exists(targetRoot.resolve(Path.of("config", "controller.yml"))));
    }

    @Test
    void dryRunSkipsOptionalScopeFilesAbsentFromBundle() throws Exception {
        // join-tokens.json is optional: when absent at backup time it isn't in the
        // bundle. Validation must still pass and the restore must skip it rather than
        // throwing NoSuchFileException on Files.size().
        Path optional = Path.of("config", "security", "join-tokens.json");
        BackupScope scope = new BackupScope(
                "db",
                List.of(),
                List.of(Path.of("config", "controller.yml"), optional),
                List.of(Path.of("templates")));
        Path backupRoot = tempDir.resolve("backup");
        Path targetRoot = tempDir.resolve("target");
        createValidBackup(scope, backupRoot);
        Files.deleteIfExists(backupRoot.resolve(optional));

        var report = executor().restoreFilesystem(scope, backupRoot, targetRoot, RestoreMode.DRY_RUN);

        assertFalse(report.applied());
        // controller.yml + templates dir = 2 entries; the absent optional file is skipped.
        assertEquals(2, report.entries().size());
    }

    @Test
    void applyRestoresFilesAndDirectoriesAndKeepsRollbackSnapshot() throws Exception {
        BackupScope scope = smallScope();
        Path backupRoot = tempDir.resolve("backup");
        Path targetRoot = tempDir.resolve("target");
        createValidBackup(scope, backupRoot);
        Files.createDirectories(targetRoot.resolve("config"));
        Files.writeString(targetRoot.resolve(Path.of("config", "controller.yml")), "old-config");
        Files.createDirectories(targetRoot.resolve(Path.of("templates", "old")));
        Files.writeString(targetRoot.resolve(Path.of("templates", "old", "template.yml")), "old-template");

        var report = executor().restoreFilesystem(scope, backupRoot, targetRoot, RestoreMode.APPLY);

        assertTrue(report.applied());
        assertEquals("new-config", Files.readString(targetRoot.resolve(Path.of("config", "controller.yml"))));
        assertEquals("new-template", Files.readString(targetRoot.resolve(Path.of("templates", "lobby.yml"))));
        assertEquals(
                "old-config", Files.readString(report.rollbackRoot().resolve(Path.of("config", "controller.yml"))));
        assertEquals(
                "old-template",
                Files.readString(report.rollbackRoot().resolve(Path.of("templates", "old", "template.yml"))));
    }

    @Test
    void rejectsInvalidBackupBeforeWritingTarget() throws Exception {
        BackupScope scope = smallScope();
        Path backupRoot = tempDir.resolve("backup");
        Path targetRoot = tempDir.resolve("target");
        Files.createDirectories(backupRoot);

        assertThrows(
                RestoreExecutor.RestoreRejectedException.class,
                () -> executor().restoreFilesystem(scope, backupRoot, targetRoot, RestoreMode.APPLY));
        assertFalse(Files.exists(targetRoot.resolve(Path.of("config", "controller.yml"))));
    }

    @Test
    void rejectsAbsoluteScopePathsEvenIfValidatorCanSeeThem() throws Exception {
        Path outside = tempDir.resolve("outside.yml");
        Files.writeString(outside, "outside");
        BackupScope scope = new BackupScope("db", List.of(), List.of(outside), List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> executor()
                        .restoreFilesystem(
                                scope, tempDir.resolve("backup"), tempDir.resolve("target"), RestoreMode.DRY_RUN));
    }

    @Test
    void datastoreDryRunReportsMongoImportsWithoutMutatingTargets() throws Exception {
        BackupScope scope = datastoreScope();
        Path backupRoot = tempDir.resolve("backup");
        createDatastoreBackup(scope, backupRoot);
        var mongo = new FakeMongoTarget();
        mongo.collections.put("groups", new ArrayList<>(List.of(new Document("_id", "old"))));

        DataRestoreReport report = executor().restoreDatastores(scope, backupRoot, mongo, RestoreMode.DRY_RUN);

        assertFalse(report.applied());
        assertEquals(1, report.mongoImports().size());
        assertEquals(2, report.mongoImports().getFirst().documentCount());
        assertEquals("old", mongo.collections.get("groups").getFirst().getString("_id"));
    }

    @Test
    void datastoreApplyReplacesScopedMongoCollections() throws Exception {
        BackupScope scope = datastoreScope();
        Path backupRoot = tempDir.resolve("backup");
        createDatastoreBackup(scope, backupRoot);
        var mongo = new FakeMongoTarget();
        mongo.collections.put("groups", new ArrayList<>(List.of(new Document("_id", "old"))));

        DataRestoreReport report = executor().restoreDatastores(scope, backupRoot, mongo, RestoreMode.APPLY);

        assertTrue(report.applied());
        assertEquals(
                List.of("lobby", "proxy"),
                mongo.collections.get("groups").stream()
                        .map(doc -> doc.getString("_id"))
                        .toList());
    }

    @Test
    void datastoreApplyReplacesMongoCollectionsByDeclaredPrefix() throws Exception {
        BackupScope scope = new BackupScope("db", List.of("groups"), List.of("platform_"), List.of(), List.of());
        Path backupRoot = tempDir.resolve("backup");
        createDatastoreBackup(scope, backupRoot);
        Path mongoRoot = backupRoot.resolve(Path.of("mongo", scope.mongoDatabase()));
        Files.writeString(mongoRoot.resolve("platform_chat_messages.jsonl"), "{\"_id\":\"msg-1\"}\n");
        Files.writeString(mongoRoot.resolve("prefixes.txt"), "platform_");
        var mongo = new FakeMongoTarget();
        mongo.collections.put("platform_old_messages", new ArrayList<>(List.of(new Document("_id", "old"))));
        mongo.collections.put("other_collection", new ArrayList<>(List.of(new Document("_id", "kept"))));

        DataRestoreReport report = executor().restoreDatastores(scope, backupRoot, mongo, RestoreMode.APPLY);

        assertTrue(report.applied());
        assertEquals(1, report.mongoPrefixImports().getFirst().collectionCount());
        assertFalse(mongo.collections.containsKey("platform_old_messages"));
        assertEquals(
                "msg-1",
                mongo.collections.get("platform_chat_messages").getFirst().getString("_id"));
        assertEquals(
                "kept", mongo.collections.get("other_collection").getFirst().getString("_id"));
    }

    @Test
    void datastoreRestoreRejectsInvalidBackupBeforeMutatingTargets() throws Exception {
        BackupScope scope = datastoreScope();
        Path backupRoot = tempDir.resolve("backup");
        Files.createDirectories(backupRoot);
        var mongo = new FakeMongoTarget();
        mongo.collections.put("groups", new ArrayList<>(List.of(new Document("_id", "old"))));

        assertThrows(
                RestoreExecutor.RestoreRejectedException.class,
                () -> executor().restoreDatastores(scope, backupRoot, mongo, RestoreMode.APPLY));

        assertEquals("old", mongo.collections.get("groups").getFirst().getString("_id"));
    }

    @Test
    void datastoreApplyRestoresLiveMongoPrefixCollections() throws Exception {
        Assumptions.assumeTrue(mongoAvailable(), "Mongo test dependency is not reachable");

        BackupScope scope =
                new BackupScope("db", List.of("groups"), List.of("platform_"), List.of(), List.of());
        Path backupRoot = tempDir.resolve("backup");
        createDatastoreBackup(scope, backupRoot);
        Path mongoRoot = backupRoot.resolve(Path.of("mongo", scope.mongoDatabase()));
        Files.writeString(mongoRoot.resolve("platform_chat_messages.jsonl"), "{\"_id\":\"msg-1\"}\n");
        Files.writeString(mongoRoot.resolve("prefixes.txt"), "platform_");

        String mongoDatabaseName = "prexor-restore-prefix-it-" + UUID.randomUUID();

        try (var mongoClient = MongoClients.create(resolveMongoUri())) {
            var mongoDatabase = mongoClient.getDatabase(mongoDatabaseName);
            mongoDatabase.getCollection("platform_old_messages").insertOne(new Document("_id", "old"));
            mongoDatabase.getCollection("other_collection").insertOne(new Document("_id", "kept"));

            DataRestoreReport report = executor()
                    .restoreDatastores(
                            // Bundle path resolves by scope.mongoDatabase(); keep it consistent with
                            // the db the bundle was written under (random db above is the target only).
                            new BackupScope(
                                    scope.mongoDatabase(),
                                    scope.mongoCollections(),
                                    scope.mongoCollectionPrefixes(),
                                    List.of(),
                                    List.of()),
                            backupRoot,
                            mongoDatabase,
                            RestoreMode.APPLY);

            assertTrue(report.applied());
            assertEquals(1, report.mongoPrefixImports().getFirst().collectionCount());
            assertEquals(
                    0L, mongoDatabase.getCollection("platform_old_messages").countDocuments());
            assertEquals(
                    "msg-1",
                    mongoDatabase
                            .getCollection("platform_chat_messages")
                            .find()
                            .first()
                            .getString("_id"));
            assertEquals(
                    "kept",
                    mongoDatabase
                            .getCollection("other_collection")
                            .find()
                            .first()
                            .getString("_id"));

            mongoDatabase.drop();
        }
    }

    private RestoreExecutor executor() {
        return new RestoreExecutor(
                new RestoreValidator(), Clock.fixed(Instant.parse("2026-04-22T12:00:00Z"), ZoneOffset.UTC));
    }

    private static BackupScope smallScope() {
        return new BackupScope(
                "db", List.of(), List.of(Path.of("config", "controller.yml")), List.of(Path.of("templates")));
    }

    private static BackupScope datastoreScope() {
        return new BackupScope("db", List.of("groups"), List.of(), List.of());
    }

    private static void createValidBackup(BackupScope scope, Path backupRoot) throws Exception {
        for (Path file : scope.files()) {
            Path resolved = backupRoot.resolve(file);
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, "new-config");
        }
        Files.createDirectories(backupRoot.resolve("templates"));
        Files.writeString(backupRoot.resolve(Path.of("templates", "lobby.yml")), "new-template");
    }

    private static void createDatastoreBackup(BackupScope scope, Path backupRoot) throws Exception {
        Path mongoRoot = backupRoot.resolve(Path.of("mongo", scope.mongoDatabase()));
        Files.createDirectories(mongoRoot);
        Files.writeString(mongoRoot.resolve("groups.jsonl"), """
                {"_id":"lobby","minOnline":1}
                {"_id":"proxy","minOnline":1}
                """);
    }

    private static final class FakeMongoTarget implements RestoreExecutor.MongoRestoreTarget {

        final Map<String, List<Document>> collections = new HashMap<>();

        @Override
        public void replaceCollection(String collection, List<Document> documents) {
            collections.put(collection, new ArrayList<>(documents));
        }

        @Override
        public void replaceCollectionPrefix(String prefix, List<RestoreExecutor.MongoCollectionImport> imports) {
            collections.keySet().removeIf(collection -> collection.startsWith(prefix));
            for (RestoreExecutor.MongoCollectionImport collection : imports) {
                collections.put(collection.collection(), new ArrayList<>(collection.documents()));
            }
        }
    }

    private static String resolveMongoUri() {
        String systemProperty = System.getProperty("prexor.test.mongoUri");
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty;
        }
        String environment = System.getenv("PREXOR_TEST_MONGO_URI");
        if (environment != null && !environment.isBlank()) {
            return environment;
        }
        return "mongodb://127.0.0.1:27017";
    }

    private static boolean mongoAvailable() {
        return socketAvailable(resolveMongoUri(), 27017);
    }

    private static boolean socketAvailable(String endpointUri, int defaultPort) {
        URI uri = URI.create(endpointUri);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception _) {
            return false;
        }
    }
}
