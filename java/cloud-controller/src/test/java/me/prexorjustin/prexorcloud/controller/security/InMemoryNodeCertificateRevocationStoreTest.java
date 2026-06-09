package me.prexorjustin.prexorcloud.controller.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryNodeCertificateRevocationStore")
class InMemoryNodeCertificateRevocationStoreTest {

    @Test
    @DisplayName("revoke + check by serial")
    void revokeBySerial() {
        var store = new InMemoryNodeCertificateRevocationStore();
        BigInteger serial = new BigInteger("abcdef", 16);

        assertFalse(store.isRevoked(serial, "node-1"));
        store.revoke(serial, "node-1", Duration.ofMinutes(10));
        assertTrue(store.isRevoked(serial, "node-1"));
    }

    @Test
    @DisplayName("revoke + check by CN only when serial is unknown")
    void revokeByCnOnly() {
        var store = new InMemoryNodeCertificateRevocationStore();
        store.revoke(null, "node-2", Duration.ofMinutes(10));
        assertTrue(store.isRevoked(null, "node-2"));
        assertTrue(store.isRevoked(BigInteger.ONE, "node-2"));
    }

    @Test
    @DisplayName("unrevoke clears the entry")
    void unrevoke() {
        var store = new InMemoryNodeCertificateRevocationStore();
        BigInteger serial = new BigInteger("ff", 16);
        store.revoke(serial, "node-3", Duration.ofMinutes(10));
        assertTrue(store.isRevoked(serial, "node-3"));

        store.unrevoke(serial, "node-3");
        assertFalse(store.isRevoked(serial, "node-3"));
    }

    @Test
    @DisplayName("expired entry is treated as not revoked")
    void expiredEntryIsNotRevoked() throws Exception {
        var store = new InMemoryNodeCertificateRevocationStore();
        BigInteger serial = new BigInteger("01", 16);
        store.revoke(serial, "node-4", Duration.ofMillis(20));
        Thread.sleep(50);
        assertFalse(store.isRevoked(serial, "node-4"));
    }

    @Test
    @DisplayName("revokedSubjectCns reflects only live entries")
    void revokedSubjectCns() {
        var store = new InMemoryNodeCertificateRevocationStore();
        store.revoke(BigInteger.ONE, "alpha", Duration.ofMinutes(10));
        store.revoke(BigInteger.TWO, "beta", Duration.ofMinutes(10));
        var snapshot = store.revokedSubjectCns();
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.contains("alpha"));
        assertTrue(snapshot.contains("beta"));

        store.unrevoke(BigInteger.ONE, "alpha");
        assertFalse(store.revokedSubjectCns().contains("alpha"));
    }
}
