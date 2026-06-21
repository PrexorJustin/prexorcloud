package me.prexorjustin.prexorcloud.controller.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import me.prexorjustin.prexorcloud.controller.auth.passwordreset.MongoPasswordResetTokenStore;
import me.prexorjustin.prexorcloud.controller.auth.passwordreset.PasswordResetToken;
import me.prexorjustin.prexorcloud.controller.security.MongoNodeCertificateRevocationStore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for the Phase-6 Mongo-backed durable security stores
 * (JWT revocation, login throttle/lockout, password-reset tokens, node-cert
 * revocation). Needs a real replica-set Mongo for the atomic pipeline / single-use
 * semantics, so it boots a {@link MongoDBContainer} and self-skips without Docker /
 * an external {@code PREXOR_TEST_MONGO_URI}; CI runs it.
 */
final class MongoSecurityStoresTest {

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
        assumeTrue(dockerAvailable(), "Docker not available — skipping Mongo security stores test");
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

    private MongoDatabase freshDb() {
        return client.getDatabase("sec_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Test
    void jwtRevocationRoundTripsAndRespectsExpiry() throws InterruptedException {
        var store = new MongoJwtRevocationStore(freshDb());
        assertFalse(store.isRevoked("unknown"));
        store.revoke("jti-1", Duration.ofMinutes(15));
        assertTrue(store.isRevoked("jti-1"));
        // Past expiry must read as not-revoked even before the TTL sweep runs.
        store.revoke("jti-2", Duration.ofMillis(20));
        Thread.sleep(60);
        assertFalse(store.isRevoked("jti-2"));
    }

    @Test
    void loginFailuresAccumulateWithinWindowThenResetAndLock() throws InterruptedException {
        var store = new MongoLoginAttemptStore(freshDb());
        assertEquals(1, store.recordFailure("bob", Duration.ofMinutes(5)));
        assertEquals(2, store.recordFailure("bob", Duration.ofMinutes(5)));
        assertEquals(3, store.recordFailure("bob", Duration.ofMinutes(5)));

        // Window lapses → counter restarts at 1.
        assertEquals(1, store.recordFailure("alice", Duration.ofMillis(80)));
        Thread.sleep(120);
        assertEquals(1, store.recordFailure("alice", Duration.ofMillis(80)));

        Instant until = Instant.now().plusSeconds(60);
        store.lockUntil("bob", until);
        Optional<Instant> locked = store.lockedUntil("bob");
        assertTrue(locked.isPresent());
        assertEquals(until.toEpochMilli(), locked.get().toEpochMilli());

        // Past lock reads as unlocked; clear wipes the counter.
        store.lockUntil("carol", Instant.now().minusSeconds(1));
        assertTrue(store.lockedUntil("carol").isEmpty());
        store.clear("bob");
        assertTrue(store.lockedUntil("bob").isEmpty());
        assertEquals(1, store.recordFailure("bob", Duration.ofMinutes(5)));
    }

    @Test
    void passwordResetTokenIsSingleUseAndExpires() {
        var store = new MongoPasswordResetTokenStore(freshDb());
        Instant expiresAt = Instant.now().plusSeconds(900);
        store.store(new PasswordResetToken("tok-1", "bob", expiresAt), Duration.ofMinutes(15));

        Optional<PasswordResetToken> first = store.take("tok-1");
        assertTrue(first.isPresent());
        assertEquals("bob", first.get().username());
        // Single-use: a second take must miss.
        assertTrue(store.take("tok-1").isEmpty());
        assertTrue(store.take("nope").isEmpty());

        // Already-expired token must not be consumable.
        store.store(new PasswordResetToken("tok-2", "alice", Instant.now().minusSeconds(1)), Duration.ofMinutes(15));
        assertTrue(store.take("tok-2").isEmpty());
    }

    @Test
    void nodeCertRevocationMatchesBySerialOrCnAndUnrevokes() throws InterruptedException {
        var store = new MongoNodeCertificateRevocationStore(freshDb());
        BigInteger serial = new BigInteger("abc123", 16);
        assertFalse(store.isRevoked(serial, "node-a"));

        store.revoke(serial, "node-a", Duration.ofDays(1));
        assertTrue(store.isRevoked(serial, null));
        assertTrue(store.isRevoked(null, "node-a"));
        assertTrue(store.revokedSubjectCns().contains("node-a"));

        store.unrevoke(serial, "node-a");
        assertFalse(store.isRevoked(serial, "node-a"));
        assertFalse(store.revokedSubjectCns().contains("node-a"));

        // Past expiry reads as not-revoked before the sweep.
        store.revoke(new BigInteger("ff", 16), "node-b", Duration.ofMillis(20));
        Thread.sleep(60);
        assertFalse(store.isRevoked(new BigInteger("ff", 16), "node-b"));
    }
}
