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
import io.lettuce.core.RedisClient;
import org.bson.Document;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trip integration test: BackupCreator writes a bundle, RestoreExecutor
 * reads it back into a fresh Mongo + Redis. Skipped when a local Mongo or
 * Redis is not reachable.
 */
final class BackupCreatorIT {

    @TempDir
    Path tempDir;

    @Test
    void roundTripCreatesAndRestoresMongoCollectionsAndRedisKeys() throws Exception {
        Assumptions.assumeTrue(socketAvailable(resolveMongoUri(), 27017), "Mongo is not reachable");
        Assumptions.assumeTrue(socketAvailable(resolveRedisUri(), 6379), "Redis is not reachable");

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
                List.of("prexor:lease:"),
                List.of(Path.of("config", "controller.yml")),
                List.of(Path.of("templates")));

        try (var mongoClient = MongoClients.create(resolveMongoUri());
                var redisClient = RedisClient.create(resolveRedisUri());
                var redisConn = redisClient.connect()) {
            var sourceMongo = mongoClient.getDatabase(scope.mongoDatabase());
            var redis = redisConn.sync();
            sourceMongo
                    .getCollection("groups")
                    .insertMany(List.of(
                            new Document("_id", "lobby").append("min", 1),
                            new Document("_id", "proxy").append("min", 2)));
            redis.set("prexor:lease:scheduler", "owner-a");
            redis.setex("prexor:lease:placement", 90, "owner-b");
            redis.set("prexor:other:noise", "ignored");

            var manifest = new BackupCreator()
                    .create(scope, bundleRoot, workingDir, sourceMongo, redis, "controller-uuid", "0.0.0-it");

            assertNotNull(manifest);
            assertEquals(2L, manifest.mongoDocumentCount());
            assertEquals(2L, manifest.redisKeyCount());
            assertTrue(manifest.fileCount() >= 2, "expected at least controller.yml + templates/lobby/server.yml");
            assertTrue(Files.isRegularFile(bundleRoot.resolve("manifest.json")));
            assertTrue(Files.isRegularFile(
                    bundleRoot.resolve("mongo").resolve(scope.mongoDatabase()).resolve("groups.jsonl")));
            assertTrue(Files.isRegularFile(bundleRoot.resolve("redis").resolve("keys.jsonl")));
            assertTrue(Files.isRegularFile(bundleRoot.resolve("redis").resolve("prefixes.txt")));

            // Drop and restore into the same database
            sourceMongo.drop();
            for (String prefix : scope.redisKeyPrefixes()) {
                redis.eval(
                        "for _,k in ipairs(redis.call('keys', ARGV[1])) do redis.call('del', k) end return 1",
                        io.lettuce.core.ScriptOutputType.INTEGER,
                        new String[0],
                        prefix + "*");
            }

            var restoreReport = new RestoreExecutor()
                    .restoreDatastores(scope, bundleRoot, sourceMongo, redis, RestoreExecutor.RestoreMode.APPLY);
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
            assertEquals("owner-a", redis.get("prexor:lease:scheduler"));
            assertEquals("owner-b", redis.get("prexor:lease:placement"));
            assertTrue(redis.ttl("prexor:lease:placement") > 0);
            assertEquals("ignored", redis.get("prexor:other:noise"));

            sourceMongo.drop();
            for (String prefix : scope.redisKeyPrefixes()) {
                redis.eval(
                        "for _,k in ipairs(redis.call('keys', ARGV[1])) do redis.call('del', k) end return 1",
                        io.lettuce.core.ScriptOutputType.INTEGER,
                        new String[0],
                        prefix + "*");
            }
            redis.del("prexor:other:noise");
        }
    }

    private static String resolveMongoUri() {
        String env = System.getenv("PREXOR_TEST_MONGO_URI");
        if (env != null && !env.isBlank()) return env;
        String prop = System.getProperty("prexor.test.mongoUri");
        if (prop != null && !prop.isBlank()) return prop;
        return "mongodb://127.0.0.1:27017";
    }

    private static String resolveRedisUri() {
        String env = System.getenv("PREXOR_TEST_REDIS_URI");
        if (env != null && !env.isBlank()) return env;
        String prop = System.getProperty("prexor.test.redisUri");
        if (prop != null && !prop.isBlank()) return prop;
        return "redis://127.0.0.1:6379";
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
