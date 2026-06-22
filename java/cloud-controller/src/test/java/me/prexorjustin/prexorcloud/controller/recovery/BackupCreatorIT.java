package me.prexorjustin.prexorcloud.controller.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trip integration test: BackupCreator writes a bundle, RestoreExecutor
 * reads it back into a fresh Mongo. Skipped when a local Mongo is not reachable.
 */
final class BackupCreatorIT {

    @TempDir
    Path tempDir;

    @Test
    void roundTripCreatesAndRestoresMongoCollections() throws Exception {
        Assumptions.assumeTrue(socketAvailable(resolveMongoUri(), 27017), "Mongo is not reachable");

        Path workingDir = tempDir.resolve("work");
        Path bundleRoot = tempDir.resolve("bundle");
        Files.createDirectories(workingDir.resolve("config"));
        Files.writeString(workingDir.resolve("config/controller.yml"), "uuid: test");
        Files.createDirectories(workingDir.resolve("templates/lobby"));
        Files.writeString(workingDir.resolve("templates/lobby/server.yml"), "name: lobby");

        BackupScope scope = new BackupScope(
                "prexor-backup-it-" + UUID.randomUUID(),
                List.of("groups"),
                List.of(),
                List.of(Path.of("config", "controller.yml")),
                List.of(Path.of("templates")));

        try (var mongoClient = MongoClients.create(resolveMongoUri())) {
            var sourceMongo = mongoClient.getDatabase(scope.mongoDatabase());
            sourceMongo
                    .getCollection("groups")
                    .insertMany(List.of(
                            new Document("_id", "lobby").append("min", 1),
                            new Document("_id", "proxy").append("min", 2)));

            var manifest = new BackupCreator()
                    .create(scope, bundleRoot, workingDir, sourceMongo, "controller-uuid", "0.0.0-it");

            assertNotNull(manifest);
            // The manifest id must match the bundle directory name, else verify/restore/
            // get/delete (which resolve catalog.bundleRoot(manifest.id())) cannot find it.
            assertEquals(bundleRoot.getFileName().toString(), manifest.id(), "manifest id must equal bundle dir name");
            assertEquals(2L, manifest.mongoDocumentCount());
            assertTrue(manifest.fileCount() >= 2, "expected at least controller.yml + templates/lobby/server.yml");
            assertTrue(Files.isRegularFile(bundleRoot.resolve("manifest.json")));
            assertTrue(Files.isRegularFile(
                    bundleRoot.resolve("mongo").resolve(scope.mongoDatabase()).resolve("groups.jsonl")));

            // Drop and restore into the same database
            sourceMongo.drop();

            var restoreReport = new RestoreExecutor()
                    .restoreDatastores(scope, bundleRoot, sourceMongo, RestoreExecutor.RestoreMode.APPLY);
            assertTrue(restoreReport.applied());

            var restored =
                    sourceMongo
                            .getCollection("groups")
                            .find()
                            .sort(new Document("_id", 1))
                            .into(new ArrayList<>())
                            .stream()
                            .map(d -> d.getString("_id"))
                            .toList();
            assertEquals(List.of("lobby", "proxy"), restored);

            sourceMongo.drop();
        }
    }

    private static String resolveMongoUri() {
        String env = System.getenv("PREXOR_TEST_MONGO_URI");
        if (env != null && !env.isBlank()) return env;
        String prop = System.getProperty("prexor.test.mongoUri");
        if (prop != null && !prop.isBlank()) return prop;
        return "mongodb://127.0.0.1:27017";
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
