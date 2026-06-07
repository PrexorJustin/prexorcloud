package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Round-trip test for the console scrollback collection. Skipped when a
 * local Mongo is not reachable, matching the pattern in {@code BackupCreatorIT}.
 */
@DisplayName("MongoStateStore — console scrollback")
final class MongoStateStoreConsoleIT {

    @Test
    @DisplayName("persists and reads back console lines in timestamp order")
    void roundTrip() {
        Assumptions.assumeTrue(socketAvailable(resolveMongoUri(), 27017), "Mongo is not reachable");

        String dbName = "prexor-console-it-" + UUID.randomUUID();
        try (var mongoClient = MongoClients.create(resolveMongoUri())) {
            var db = mongoClient.getDatabase(dbName);
            try {
                MongoStateStore store = new MongoStateStore(mongoClient, db);
                store.initialize();

                String instanceA = "lobby-1";
                String instanceB = "lobby-2";
                Instant base = Instant.parse("2026-05-11T10:00:00Z");

                store.appendConsoleLine(instanceA, base, "first");
                store.appendConsoleLine(instanceA, base.plusSeconds(1), "second");
                store.appendConsoleLine(instanceA, base.plusSeconds(2), "third");
                store.appendConsoleLine(instanceB, base.plusSeconds(1), "other instance");

                List<StateStore.ConsoleLineRecord> all = store.getConsoleHistory(instanceA, null, null, 100);
                assertEquals(
                        List.of("first", "second", "third"),
                        all.stream().map(StateStore.ConsoleLineRecord::line).toList());

                List<StateStore.ConsoleLineRecord> windowed =
                        store.getConsoleHistory(instanceA, base.plusSeconds(1), base.plusSeconds(2), 100);
                assertEquals(
                        List.of("second", "third"),
                        windowed.stream()
                                .map(StateStore.ConsoleLineRecord::line)
                                .toList());

                List<StateStore.ConsoleLineRecord> limited = store.getConsoleHistory(instanceA, null, null, 2);
                assertEquals(2, limited.size());
                assertEquals("first", limited.get(0).line());

                List<StateStore.ConsoleLineRecord> isolated = store.getConsoleHistory(instanceB, null, null, 100);
                assertEquals(
                        List.of("other instance"),
                        isolated.stream()
                                .map(StateStore.ConsoleLineRecord::line)
                                .toList());
            } finally {
                mongoClient.getDatabase(dbName).drop();
            }
        }
    }

    @Test
    @DisplayName("appendConsoleLine ignores null line / empty instance id")
    void appendIgnoresInvalidInput() {
        Assumptions.assumeTrue(socketAvailable(resolveMongoUri(), 27017), "Mongo is not reachable");

        String dbName = "prexor-console-it-" + UUID.randomUUID();
        try (var mongoClient = MongoClients.create(resolveMongoUri())) {
            var db = mongoClient.getDatabase(dbName);
            try {
                MongoStateStore store = new MongoStateStore(mongoClient, db);
                store.initialize();

                store.appendConsoleLine("", Instant.now(), "should not persist");
                store.appendConsoleLine("lobby-1", Instant.now(), null);

                assertTrue(store.getConsoleHistory("lobby-1", null, null, 100).isEmpty());
                assertTrue(store.getConsoleHistory("", null, null, 100).isEmpty());
            } finally {
                mongoClient.getDatabase(dbName).drop();
            }
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
