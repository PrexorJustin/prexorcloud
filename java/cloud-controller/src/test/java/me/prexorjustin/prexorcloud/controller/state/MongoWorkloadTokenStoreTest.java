package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for {@link MongoWorkloadTokenStore} — durable plugin tokens +
 * atomic replay-sequence high-water mark. Needs a real replica-set Mongo for the
 * atomic accept-if-greater pipeline, so it boots a {@link MongoDBContainer} and
 * self-skips without Docker / an external {@code PREXOR_TEST_MONGO_URI}.
 */
final class MongoWorkloadTokenStoreTest {

    private static MongoClient client;
    private static MongoDBContainer mongo;

    @BeforeAll
    static void up() {
        String externalUri = System.getenv("PREXOR_TEST_MONGO_URI");
        if (externalUri == null || externalUri.isBlank()) {
            externalUri = System.getProperty("prexor.test.mongoUri");
        }
        if (externalUri != null && !externalUri.isBlank()) {
            client = MongoClients.create(externalUri);
            return;
        }
        assumeTrue(dockerAvailable(), "Docker not available — skipping workload token store test");
        mongo = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
        mongo.start();
        client = MongoClients.create(mongo.getConnectionString());
    }

    @AfterAll
    static void down() {
        if (client != null) client.close();
        if (mongo != null) mongo.stop();
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private MongoWorkloadTokenStore freshStore() {
        return new MongoWorkloadTokenStore(
                client.getDatabase("wts_" + UUID.randomUUID().toString().replace("-", "")));
    }

    @Test
    void tokenRoundTripsAndIsRemovable() {
        var store = freshStore();
        Instant issued = Instant.now();
        var entry = new WorkloadIdentityRegistry.PluginTokenEntry(
                "tid-1", "inst-1", issued, issued.plus(Duration.ofMinutes(15)));
        assertTrue(store.loadToken("ptk_x").isEmpty());

        store.saveToken("ptk_x", entry);
        Optional<WorkloadIdentityRegistry.PluginTokenEntry> loaded = store.loadToken("ptk_x");
        assertTrue(loaded.isPresent());
        assertEquals("tid-1", loaded.get().tokenId());
        assertEquals("inst-1", loaded.get().instanceId());

        store.saveToken(
                "ptk_y",
                new WorkloadIdentityRegistry.PluginTokenEntry(
                        "tid-2", "inst-2", issued, issued.plus(Duration.ofMinutes(15))));
        assertEquals(2, store.loadAllTokens().size());

        store.removeToken("ptk_x");
        assertTrue(store.loadToken("ptk_x").isEmpty());
        assertEquals(1, store.loadAllTokens().size());
    }

    @Test
    void acceptSequenceIsMonotonicAndAtomic() {
        var store = freshStore();
        Duration ttl = Duration.ofMinutes(15);
        assertTrue(store.acceptSequence("inst-1", 1L, ttl)); // first
        assertTrue(store.acceptSequence("inst-1", 2L, ttl)); // advance
        assertFalse(store.acceptSequence("inst-1", 2L, ttl)); // replay (equal)
        assertFalse(store.acceptSequence("inst-1", 1L, ttl)); // replay (lower)
        assertTrue(store.acceptSequence("inst-1", 5L, ttl)); // jump forward

        // Independent per instance.
        assertTrue(store.acceptSequence("inst-2", 1L, ttl));

        // Clearing resets the window so a lower sequence is accepted again (instance restart).
        store.clearSequence("inst-1");
        assertTrue(store.acceptSequence("inst-1", 1L, ttl));
    }
}
